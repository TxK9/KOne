"""Combined public hostname, fixed routes and independent node/device roles."""
from contextlib import closing
import http.client
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from urllib.parse import parse_qs,urlsplit
import time
from unittest.mock import patch

BASE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BASE))
from server import APIError, create_server
from deploy.center_common import load_settings
spec=importlib.util.spec_from_file_location('public_api_test_agent',BASE/'node/agent.py')
agent=importlib.util.module_from_spec(spec);spec.loader.exec_module(agent)


class PublicAPITest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='kone-public-api-')
        self.root=Path(self.temp.name)
        self.server=create_server(port=0,data_dir=self.root/'data',api_origin='https://api.example.invalid',
                                  node_origin='https://api.example.invalid')
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.cookie=None;self.csrf=None
        code,result,headers=self.request('POST','/api/auth/setup',{'password':'disposable-test-only'})
        self.assertEqual(code,200)
        self.cookie=headers['Set-Cookie'].split(';',1)[0];self.csrf=result['csrfToken']
        code,p,_=self.request('POST','/api/profiles',{'name':'test profile','config':{'outbounds':[{'type':'direct','tag':'direct'}]}})
        self.assertEqual(code,200)
        code,g,_=self.request('POST','/api/customer-groups',{'name':'test group','profileId':p['id'],'deviceLimit':2})
        self.assertEqual(code,200)
        code,u,_=self.request('POST','/api/users',{'name':'test customer','groupId':g['id']})
        self.assertEqual(code,200);self.user=u['id']

    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join(timeout=3);self.temp.cleanup()

    def request(self,method,path,data=None,public=False,bearer=None,extra=None):
        headers={'Host':'api.example.invalid' if public else '127.0.0.1:'+str(self.server.server_port)}
        if self.cookie:headers.update({'Cookie':self.cookie,'X-CSRF-Token':self.csrf})
        if bearer:headers['Authorization']='Bearer '+bearer
        if extra:headers.update(extra)
        body=None if data is None else json.dumps(data).encode()
        if body is not None:headers['Content-Type']='application/json'
        with closing(http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)) as c:
            c.request(method,path,body,headers)
            response=c.getresponse();raw=response.read()
            try:value=json.loads(raw) if raw else None
            except ValueError:value=raw
            return response.status,value,dict(response.getheaders())

    def claim(self):
        code,invite,_=self.request('POST','/api/customer-users/'+self.user+'/invite',{})
        self.assertEqual(code,200)
        code,device,_=self.request('POST','/api/client/claim',{'inviteCode':invite['inviteCode'],'deviceName':'QA device'},public=True)
        self.assertEqual(code,200)
        return device

    def enroll(self):
        code,ticket,_=self.request('POST','/api/fleet/enrollments',{'name':'QA node','address':'127.0.0.1'})
        self.assertEqual(code,200)
        directory=self.root/'certificate';directory.mkdir()
        cert=agent.certificate(directory,'127.0.0.1')
        code,node,_=self.request('POST','/api/node/register',{'token':ticket['token'],'certificate':cert,
                                 'os':'test-linux','arch':'test','mode':'stage'},public=True)
        self.assertEqual(code,200)
        return node

    def test_admin_cookie_and_forwarded_headers_cannot_expose_management(self):
        for method,path in [('GET','/'),('GET','/index.html'),('GET','/app.js'),('GET','/api/state'),
                ('GET','/api/auth/status'),('POST','/api/auth/login'),('POST','/api/auth/setup'),
                ('POST','/api/users'),('GET','/api/customers'),('GET','/api/customer-users/'+self.user+'/detail'),
                ('GET','/api/client/config?credential=not-permitted'),('GET','/api/node/poll/extra'),
                ('GET','/%61pi/auth/status'),('GET','//api/auth/status')]:
            with self.subTest(method=method,path=path):
                code,result,_=self.request(method,path,{} if method=='POST' else None,public=True,
                    extra={'X-Forwarded-Host':'127.0.0.1:'+str(self.server.server_port),'X-Forwarded-Proto':'https'})
                self.assertEqual(code,404)
                self.assertNotIn('csrfToken',str(result))
        self.assertEqual(self.request('GET','/api/state')[0],200)
        self.assertEqual(self.request('GET','/api/auth/status')[1]['authenticated'],True)

    def test_invitation_link_uses_public_api_and_one_time_redemption(self):
        before=int(time.time())
        status,invite,headers=self.request('POST','/api/customer-users/'+self.user+'/invite',{})
        self.assertEqual(status,200)
        self.assertEqual(headers['Cache-Control'],'no-store')
        link=urlsplit(invite['claimLink'])
        self.assertEqual((link.scheme,link.netloc,link.path),('kone','claim',''))
        fields=parse_qs(link.query)
        self.assertEqual(fields,{'server':['https://api.example.invalid'],'code':[invite['inviteCode']]})
        self.assertGreaterEqual(invite['expiresAt'],before+900)
        self.assertLessEqual(invite['expiresAt'],int(time.time())+900)
        payload={'inviteCode':fields['code'][0],'deviceName':'QR test device'}
        self.assertEqual(self.request('POST','/api/client/claim',payload,public=True)[0],200)
        self.assertEqual(self.request('POST','/api/client/claim',payload,public=True)[0],401)
        self.assertEqual(self.request('POST','/api/customer-users/'+self.user+'/invite',{},public=True)[0],404)

    def test_client_flow_and_revocation_share_public_host_without_admin_rights(self):
        device=self.claim();credential=device['credential']
        code,config,headers=self.request('GET','/api/client/config',public=True,bearer=credential)
        self.assertEqual(code,200);self.assertEqual(config['configName'],'test profile')
        self.assertEqual(headers['Cache-Control'],'no-store')
        self.assertEqual(self.request('GET','/api/client/config',public=True)[0],401)
        self.assertEqual(self.request('POST','/api/node/poll',{},public=True,bearer=credential)[0],401)
        self.assertEqual(self.request('POST','/api/client/ack',{'revision':config['revision'],'status':'applied','code':'ok'},public=True,bearer=credential)[0],200)
        self.assertEqual(self.request('POST','/api/client/diagnostics',{'appVersion':'test','network':'wifi','events':['app_started']},public=True,bearer=credential)[0],200)
        self.assertEqual(self.request('POST','/api/customer-users/'+self.user+'/revoke',{})[0],200)
        self.assertEqual(self.request('GET','/api/client/config',public=True,bearer=credential)[0],401)

    def test_node_enrollment_poll_and_downloads_share_host_but_not_client_role(self):
        node=self.enroll();credential=node['credential']
        self.assertEqual(self.request('POST','/api/node/poll',{'metrics':{'cpuCores':2}},public=True,bearer=credential)[0],200)
        self.assertEqual(self.request('GET','/api/client/config',public=True,bearer=credential)[0],401)
        self.assertEqual(self.request('GET','/api/state',public=True,bearer=credential)[0],404)
        for path in ['/downloads/install-node.sh','/downloads/kone-node.py']:
            self.assertEqual(self.request('GET',path,public=True)[0],200)
            self.assertEqual(self.request('HEAD',path,public=True)[0],200)
            self.assertEqual(self.request('POST',path,{},public=True)[0],405)
        self.assertEqual(self.request('GET','/api/node/poll',public=True)[0],405)
        self.assertEqual(self.request('POST','/api/client/config',{},public=True)[0],405)
        self.assertEqual(self.request('POST','/api/node/usage',{'samples':[]},public=True,bearer=credential)[0],400)
        self.assertEqual(self.request('POST','/api/node/result',{},public=True,bearer=credential)[0],400)

    def test_public_origin_rejects_plaintext_and_nonloopback_source(self):
        for host,origin in [('127.0.0.1','http://api.example.invalid'),('0.0.0.0','https://api.example.invalid'),
                            ('127.0.0.1','https://localhost:8768'),('127.0.0.1','https://api.example.invalid/path'),('127.0.0.1',''),('127.0.0.1',False)]:
            with self.subTest(host=host,origin=origin),self.assertRaises(ValueError):
                create_server(host=host,port=0,data_dir=self.root/'invalid',api_origin=origin)
        self.assertEqual(self.request('POST','/api/client/claim',{'inviteCode':'invalid','deviceName':'test'},public=True,
                          extra={'Origin':'https://other.example.invalid'})[0],403)

    def test_portable_settings_preserve_api_origin_and_legacy_defaults(self):
        path=self.root/'settings.json'
        data={'format':1,'data_dir':'data','node_origin':'https://api.example.invalid','api_origin':'https://api.example.invalid'}
        path.write_text(json.dumps(data))
        settings=load_settings(path)
        self.assertEqual(settings['api_origin'],'https://api.example.invalid')
        self.assertEqual(settings['data_dir'],(self.root/'data').resolve())
        for value in [True,{},[]]:
            path.write_text(json.dumps({**data,'api_origin':value}))
            with self.assertRaises(ValueError):load_settings(path)
        path.write_text(json.dumps({'format':1,'data_dir':'data'}))
        self.assertIsNone(load_settings(path)['api_origin'])

    def test_cloudflare_clients_have_separate_limits_and_forwarded_for_cannot_evade_them(self):
        self.server.app.trusted_proxy='cloudflare-tunnel'
        payload={'inviteCode':'invalid','deviceName':'test'}
        for n in range(10):
            status,_,_=self.request('POST','/api/client/claim',payload,public=True,
                extra={'CF-Connecting-IP':'8.8.8.8','X-Forwarded-For':'9.9.9.'+str(n)})
            self.assertEqual(status,401)
        self.assertEqual(self.request('POST','/api/client/claim',payload,public=True,
            extra={'CF-Connecting-IP':'8.8.8.8','X-Forwarded-For':'1.1.1.1'})[0],429)
        self.assertEqual(self.request('POST','/api/client/claim',payload,public=True,
            extra={'CF-Connecting-IP':'9.9.9.9'})[0],401)
        code,invite,_=self.request('POST','/api/customer-users/'+self.user+'/invite',{})
        self.assertEqual(code,200)
        self.assertEqual(self.request('POST','/api/client/claim',
            {'inviteCode':invite['inviteCode'],'deviceName':'new client'},public=True,
            extra={'CF-Connecting-IP':'1.1.1.1'})[0],200)
        # Ordinary local/admin calls still use the TCP peer, not any IP header.
        self.assertEqual(self.request('POST','/api/auth/login',{'password':'invalid'},
            extra={'CF-Connecting-IP':'8.8.8.8'})[0],401)
        self.assertNotIn('8.8.8.8',self.server.app.login_attempts)

    def test_cloudflare_mode_rejects_missing_invalid_or_duplicated_source(self):
        for proxy in [True,'nginx',{},'']:
            with self.subTest(proxy=proxy),self.assertRaises(ValueError):
                create_server(port=0,data_dir=self.root/'invalid',api_origin='https://api.example.invalid',trusted_proxy=proxy)
        with self.assertRaises(ValueError):
            create_server(port=0,data_dir=self.root/'invalid',trusted_proxy='cloudflare-tunnel')
        self.server.app.trusted_proxy='cloudflare-tunnel'
        payload={'inviteCode':'invalid','deviceName':'test'}
        for value in [None,'','garbage','8.8.8.8, 1.1.1.1','127.0.0.1','10.0.0.1']:
            extra={} if value is None else {'CF-Connecting-IP':value}
            with self.subTest(value=value):
                self.assertEqual(self.request('POST','/api/client/claim',payload,public=True,extra=extra)[0],400)
        self.assertEqual(self.request('POST','/api/client/claim',payload,public=True,
            extra={'CF-Connecting-IP':'2606:4700:4700::1111'})[0],401)
        with closing(http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)) as connection:
            body=json.dumps(payload).encode()
            connection.putrequest('POST','/api/client/claim',skip_host=True)
            connection.putheader('Host','api.example.invalid')
            connection.putheader('Content-Type','application/json')
            connection.putheader('Content-Length',str(len(body)))
            connection.putheader('CF-Connecting-IP','8.8.8.8')
            connection.putheader('CF-Connecting-IP','9.9.9.9')
            connection.endheaders(body)
            response=connection.getresponse();response.read()
            self.assertEqual(response.status,400)
        path=self.root/'proxy-settings.json'
        path.write_text(json.dumps({'format':1,'api_origin':'https://api.example.invalid','trusted_proxy':'cloudflare-tunnel'}))
        self.assertEqual(load_settings(path)['trusted_proxy'],'cloudflare-tunnel')
        path.write_text(json.dumps({'format':1,'trusted_proxy':'cloudflare-tunnel'}))
        with self.assertRaises(ValueError):load_settings(path)

    def test_attempt_memory_is_bounded_and_overloaded_connections_recover(self):
        app=self.server.app
        with patch('server.time.monotonic',return_value=100):
            app.login_attempts={str(n):[0] for n in range(4096)}
            app.check_login_rate('new')
            self.assertEqual(set(app.login_attempts),{'new'})
            app.login_attempts={str(n):[100] for n in range(4096)}
            with self.assertRaises(APIError) as caught:app.check_login_rate('another')
            self.assertEqual(caught.exception.status,429)
            self.assertEqual(len(app.login_attempts),4096)
        slots=self.server.connection_slots
        acquired=0
        try:
            # Wait for the final setup handler to release its slot before
            # saturating the documented 64-connection budget.
            for _ in range(64):
                self.assertTrue(slots.acquire(timeout=2))
                acquired+=1
            self.assertEqual(self.request('GET','/api/auth/status')[0],503)
        finally:
            for _ in range(acquired):slots.release()
        self.assertEqual(self.request('GET','/api/auth/status')[0],200)
