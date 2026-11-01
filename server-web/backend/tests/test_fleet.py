import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import ConsoleStore
from fleet import Fleet, FleetError

spec = importlib.util.spec_from_file_location("kone_agent", Path(__file__).resolve().parents[1]/"node"/"agent.py")
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class FleetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="kone-fleet-test-")
        self.root = Path(self.tmp.name)
        self.store = ConsoleStore(self.root/"console")
        self.fleet = Fleet(self.store)
        self.runners = []

    def tearDown(self):
        for runner in self.runners:
            runner.close()
        self.tmp.cleanup()

    def enroll(self, name, mode="stage"):
        ticket = self.fleet.enrollment({"name":name,"address":"127.0.0.1"})
        directory = self.root/ticket["id"]
        directory.mkdir()
        cert = agent.certificate(directory, "127.0.0.1")
        result = self.fleet.register({"token":ticket["token"],"certificate":cert,"os":"test","arch":"arm64","mode":mode})
        return ticket, result, directory

    def add(self, server_id, kind="vless"):
        return self.fleet.add_node({"serverId":server_id,"kind":kind,"name":kind,"port":free_port()})["id"]

    def test_registration_is_once_and_revocation_denies_access(self):
        ticket, result, directory = self.enroll("A")
        self.assertEqual(self.fleet.authenticate("Bearer "+result["credential"])["id"], ticket["id"])
        with self.assertRaises(FleetError):
            self.fleet.register({"token":ticket["token"],"certificate":(directory/"tls.crt").read_text(),"mode":"stage","os":"x","arch":"x"})
        summary = json.dumps(self.fleet.state())
        self.assertNotIn(result["credential"], summary)
        self.assertNotIn(ticket["token"], summary)
        self.fleet.revoke(ticket["id"])
        with self.assertRaises(FleetError):
            self.fleet.authenticate("Bearer "+result["credential"])

    def test_expired_enrollment_and_unsupported_protocol(self):
        ticket = self.fleet.enrollment({"name":"A","address":"127.0.0.1"})
        with self.store.connect() as c:
            c.execute("UPDATE fleet_enrollments SET expires=0")
        directory=self.root/"cert";directory.mkdir()
        cert=agent.certificate(directory,"127.0.0.1")
        with self.assertRaises(FleetError):
            self.fleet.register({"token":ticket["token"],"certificate":cert,"mode":"stage","os":"x","arch":"x"})
        with self.assertRaises(FleetError):
            self.fleet.add_node({"serverId":ticket["id"],"kind":"vh","name":"VH","port":443})
        self.assertEqual(self.fleet.state()["nodes"], [])

    def test_assignment_atomic_chain_no_loop_and_staging_not_ready(self):
        a, _, directory = self.enroll("A")
        n = self.add(a["id"])
        with self.assertRaises(FleetError):
            self.fleet.add_route({"name":"循环","nodeIds":[n,n]})
        r = self.fleet.add_route({"name":"直接","nodeIds":[n]})["id"]
        user = self.store.add_user({"name":"A"})["id"]
        with self.assertRaises(FleetError):
            self.fleet.assign({"routeId":r,"userIds":[user,"missing"]})
        self.assertEqual(self.fleet.state()["routes"][0]["assignments"], [])
        self.fleet.assign({"routeId":r,"userIds":[user]})
        job = self.fleet.poll(a["id"])["job"]
        self.assertIsNone(self.fleet.poll(a["id"])["job"])
        with self.assertRaises(FleetError):
            self.fleet.complete(a["id"], {"jobId":job["id"],"lease":job["lease"],"status":"running"})
        self.fleet.complete(a["id"], {"jobId":job["id"],"lease":job["lease"],"status":"staged","code":"staged"})
        self.assertEqual(self.store.state()["profiles"], [])
        self.assertEqual(self.fleet.state()["routes"][0]["assignments"][0]["status"], "pending")

    def test_results_are_bound_to_node_and_revision(self):
        a, _, _ = self.enroll("A", "process")
        b, _, _ = self.enroll("B", "process")
        self.add(a["id"])
        old = self.fleet.poll(a["id"])["job"]
        self.fleet.retry(a["id"])
        with self.assertRaises(FleetError):
            self.fleet.complete(b["id"], {"jobId":old["id"],"lease":old["lease"],"status":"running"})
        self.fleet.complete(a["id"], {"jobId":old["id"],"lease":old["lease"],"status":"running"})
        s = next(s for s in self.fleet.state()["servers"] if s["id"] == a["id"])
        self.assertEqual(s["status"], "pending")
        new = self.fleet.poll(a["id"])["job"]
        self.assertGreater(new["revision"], old["revision"])

    def test_external_exit_secret_not_in_summary(self):
        self.fleet.add_exit({"name":"ISP","kind":"socks","address":"127.0.0.1","port":1080,
                            "username":"name","password":"private-example"})
        self.assertNotIn("private-example", json.dumps(self.fleet.state()))

    def test_probe_waits_for_path_and_stale_result_cannot_certify_new_revision(self):
        a, _, _ = self.enroll("入口", "process")
        n = self.add(a["id"])
        r = self.fleet.add_route({"name":"线路","nodeIds":[n]})["id"]
        with self.assertRaises(FleetError):
            self.fleet.probe(r)
        job = self.fleet.poll(a["id"])["job"]
        self.fleet.complete(a["id"], {"jobId":job["id"],"lease":job["lease"],"status":"running"})
        self.fleet.probe(r)
        check = self.fleet.poll(a["id"])["job"]
        self.assertEqual(check["kind"],"probe")
        self.assertEqual(check["outbound"]["server"],"127.0.0.1")
        with self.assertRaises(FleetError):
            self.fleet.complete(a["id"], {"jobId":check["id"],"lease":check["lease"],"status":"probed","ip":"127.0.0.1"})
        self.fleet.retry(a["id"])
        self.fleet.complete(a["id"], {"jobId":check["id"],"lease":check["lease"],"status":"probed","ip":"1.1.1.1"})
        probe = self.fleet.state()["routes"][0]["probe"]
        self.assertIsNone(probe["ip"])
        self.assertEqual(probe["status"],"stale")

    def test_assignment_does_not_restart_unrelated_server(self):
        a, _, _ = self.enroll("A","process")
        b, _, _ = self.enroll("B","process")
        na,nb=self.add(a["id"]),self.add(b["id"])
        r=self.fleet.add_route({"name":"A","nodeIds":[na]})["id"]
        before=next(s["desired"] for s in self.fleet.state()["servers"] if s["id"]==b["id"])
        user=self.store.add_user({"name":"user"})["id"]
        self.fleet.assign({"routeId":r,"userIds":[user]})
        after=next(s["desired"] for s in self.fleet.state()["servers"] if s["id"]==b["id"])
        self.assertEqual(before,after)

    def test_certificate_retry_and_registered_address_mismatch(self):
        ticket=self.fleet.enrollment({"name":"A","address":"127.0.0.1"})
        directory=self.root/"node";directory.mkdir()
        cert=agent.certificate(directory,"127.0.0.1")
        self.assertEqual(agent.certificate(directory,"127.0.0.1"),cert)
        with self.assertRaises(FleetError):
            self.fleet.register({"token":ticket["token"],"certificate":cert,"address":"other.invalid","mode":"stage","os":"x","arch":"x"})
        result=self.fleet.register({"token":ticket["token"],"certificate":cert,"address":"127.0.0.1","mode":"stage","os":"x","arch":"x"})
        self.assertIn("credential",result)

    @unittest.skipUnless(os.environ.get("KONE_TEST_ENGINE"), "set KONE_TEST_ENGINE for real core test")
    def test_real_vless_to_hy2_chain_and_customer_specific_credentials(self):
        engine=os.environ["KONE_TEST_ENGINE"]
        a, _, da=self.enroll("入口","process")
        b, _, db=self.enroll("出口","process")
        na, nb=self.add(a["id"]),self.add(b["id"],"hysteria2")
        r=self.fleet.add_route({"name":"VLESS → HY2","nodeIds":[na,nb]})["id"]
        u1,u2=self.store.add_user({"name":"甲"})["id"],self.store.add_user({"name":"乙"})["id"]
        self.fleet.assign({"routeId":r,"userIds":[u1,u2]})
        for identifier,directory in [(a["id"],da),(b["id"],db)]:
            runner=agent.Runner(directory,"process",engine,"127.0.0.1");self.runners.append(runner)
            job=self.fleet.poll(identifier)["job"]
            result=runner.execute(job)
            diagnostic=""
            if result["status"]!="running":
                diagnostic=subprocess.run([engine,"check","-c","candidate.json"],cwd=str(directory),capture_output=True,text=True).stderr
            self.assertEqual(result["status"],"running",str(result)+" "+diagnostic)
            self.fleet.complete(identifier,{"jobId":job["id"],"lease":job["lease"],**result})
        state=self.store.state()
        self.assertEqual(len(state["profiles"]),2)
        profile_ids={u["id"]:u["profile"] for u in state["users"]}
        config=self.store.config(profile_ids[u1])[1]
        other=self.store.config(profile_ids[u2])[1]
        self.assertNotEqual(config["outbounds"][0]["uuid"],other["outbounds"][0]["uuid"])
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                data=b"kone-real-chain-ok"
                self.send_response(200);self.send_header("Content-Length",str(len(data)));self.end_headers();self.wfile.write(data)
            def log_message(self,*args):pass
        target=ThreadingHTTPServer(("127.0.0.1",0),Handler)
        worker=threading.Thread(target=target.serve_forever,daemon=True);worker.start()
        socks=free_port()
        config["inbounds"]=[{"type":"mixed","tag":"local","listen":"127.0.0.1","listen_port":socks}]
        cfg=self.root/"client.json";cfg.write_text(json.dumps(config))
        client=subprocess.Popen([engine,"run","-c",str(cfg)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        try:
            time.sleep(.6)
            response=subprocess.run(["curl","--silent","--show-error","--fail","--max-time","12","--noproxy","","--proxy","socks5h://127.0.0.1:"+str(socks),"http://127.0.0.1:"+str(target.server_port)],capture_output=True,timeout=15)
            self.assertEqual(response.returncode,0,response.stderr.decode())
            self.assertEqual(response.stdout,b"kone-real-chain-ok")
            # Probe uses the complete managed path. A local endpoint returns a synthetic
            # global IP so this test sends no external network request.
            class ProbeHandler(BaseHTTPRequestHandler):
                def do_GET(self):
                    data=b"1.1.1.1"
                    self.send_response(200);self.send_header("Content-Length",str(len(data)));self.end_headers();self.wfile.write(data)
                def log_message(self,*args):pass
            checker=ThreadingHTTPServer(("127.0.0.1",0),ProbeHandler)
            checker_thread=threading.Thread(target=checker.serve_forever,daemon=True);checker_thread.start()
            try:
                self.fleet.probe(r)
                check=self.fleet.poll(a["id"])["job"]
                result=self.runners[0].probe(check, "http://127.0.0.1:"+str(checker.server_port))
                self.assertEqual(result["status"],"probed")
                self.fleet.complete(a["id"],{"jobId":check["id"],"lease":check["lease"],**result})
                self.assertEqual(self.fleet.state()["routes"][0]["probe"]["ip"],"1.1.1.1")
            finally:
                checker.shutdown();checker.server_close();checker_thread.join()
            # A failed service switch restores the old bytes and invokes restart.
            previous=(da/"active.json").read_text()
            self.fleet.retry(a["id"])
            retry=self.fleet.poll(a["id"])["job"]
            with patch.object(self.runners[0],"start",side_effect=[False,True]) as start:
                result=self.runners[0].execute(retry)
                self.assertEqual(result["code"],"start_failed")
                self.assertEqual(start.call_count,2)
            self.assertEqual((da/"active.json").read_text(),previous)
            self.fleet.complete(a["id"],{"jobId":retry["id"],"lease":retry["lease"],**result})
            self.assertEqual(next(s["status"] for s in self.fleet.state()["servers"] if s["id"]==a["id"]),"failed")
        finally:
            client.terminate();client.wait(timeout=5);target.shutdown();target.server_close();worker.join()


if __name__=="__main__":
    unittest.main()
