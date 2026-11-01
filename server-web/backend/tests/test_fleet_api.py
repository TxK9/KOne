import http.client
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import create_server

spec=importlib.util.spec_from_file_location("fleet_agent",Path(__file__).resolve().parents[1]/"node"/"agent.py")
agent=importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)


class FleetAPITest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix="fleet-api-")
        self.server=create_server(port=0,data_dir=Path(self.temp.name)/"console")
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
        self.cookie=None
        self.csrf=None

    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join()
        self.temp.cleanup()

    def request(self,path,payload=None,bearer=None,admin=True):
        c=http.client.HTTPConnection("127.0.0.1",self.server.server_port,timeout=5)
        headers={"Content-Type":"application/json"}
        if admin and self.cookie:
            headers["Cookie"]=self.cookie
            headers["X-CSRF-Token"]=self.csrf
        if bearer:
            headers["Authorization"]="Bearer "+bearer
        try:
            c.request("POST" if payload is not None else "GET",path,None if payload is None else json.dumps(payload),headers)
            r=c.getresponse()
            raw=r.read()
            cookie=r.getheader("Set-Cookie")
            data=json.loads(raw) if "json" in (r.getheader("Content-Type") or "") else raw
            return r.status,data,cookie
        finally:c.close()

    def login(self):
        code,data,cookie=self.request("/api/auth/setup",{"password":"test-only-long-pass"},admin=False)
        self.assertEqual(code,200)
        self.cookie=cookie.split(";")[0];self.csrf=data["csrfToken"]

    def enroll(self):
        code,ticket,_=self.request("/api/fleet/enrollments",{"name":"test","address":"127.0.0.1"})
        self.assertEqual(code,200)
        directory=Path(self.temp.name)/"node";directory.mkdir()
        cert=agent.certificate(directory,"127.0.0.1")
        code,result,_=self.request("/api/node/register",{"token":ticket["token"],"address":"127.0.0.1","certificate":cert,"mode":"stage","os":"test","arch":"arm64"},admin=False)
        self.assertEqual(code,200)
        return result

    def test_node_credentials_are_not_admin_credentials(self):
        self.assertEqual(self.request("/api/fleet",admin=False)[0],401)
        self.assertEqual(self.request("/api/fleet/enrollments",{"name":"test","address":"127.0.0.1"},admin=False)[0],401)
        self.login()
        node=self.enroll()
        self.assertEqual(self.request("/api/fleet",bearer=node["credential"],admin=False)[0],401)
        self.assertEqual(self.request("/api/node/poll",{},admin=True)[0],401)
        self.assertEqual(self.request("/api/node/poll",{},bearer=node["credential"],admin=False)[0],200)
        raw=self.request("/api/fleet")[1]
        self.assertNotIn(node["credential"],json.dumps(raw))
        self.assertEqual(self.request("/api/fleet/servers/"+node["serverId"]+"/revoke",{})[0],200)
        self.assertEqual(self.request("/api/node/poll",{},bearer=node["credential"],admin=False)[0],401)

    def test_stage_agent_reports_without_creating_false_customer_profile(self):
        self.login()
        node=self.enroll()
        code,n,_=self.request("/api/fleet/nodes",{"serverId":node["serverId"],"kind":"hysteria2","name":"HY2","port":32443})
        self.assertEqual(code,200)
        code,r,_=self.request("/api/fleet/routes",{"name":"route","nodeIds":[n["id"]]})
        self.assertEqual(code,200)
        user=self.request("/api/users",{"name":"customer"})[1]
        self.assertEqual(self.request("/api/fleet/assignments",{"routeId":r["id"],"userIds":[user["id"]]})[0],200)
        job=self.request("/api/node/poll",{},bearer=node["credential"],admin=False)[1]["job"]
        self.assertEqual(self.request("/api/node/result",{"jobId":job["id"],"lease":job["lease"],"status":"staged"},bearer=node["credential"],admin=False)[0],200)
        self.assertEqual(self.request("/api/state")[1]["profiles"],[])
        self.assertEqual(self.request("/api/fleet/routes/"+r["id"]+"/probe",{})[0],409)
        self.assertEqual(self.request("/downloads/kone-node.py",admin=False)[0],200)
        self.assertEqual(self.request("/downloads/install-node.sh",admin=False)[0],200)

    def test_schema_upgrade_preserves_old_configuration_and_login(self):
        self.login()
        before=self.request("/api/profiles",{"name":"existing","config":{"outbounds":[{"type":"direct","tag":"direct"}]}})[1]["id"]
        user=self.request("/api/users",{"name":"before"})[1]["id"]
        self.request("/api/assignments",{"profileId":before,"userIds":[user]})
        from fleet import Fleet
        Fleet(self.server.app.store)
        data=self.request("/api/state")[1]
        self.assertEqual(data["users"][0]["profile"],before)
        self.assertTrue(self.server.app.store.configured())


if __name__=="__main__":
    unittest.main()
