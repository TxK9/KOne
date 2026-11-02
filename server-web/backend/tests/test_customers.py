"""Disposable customer APIs, policy/credential isolation and honest usage semantics."""
import http.client
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

BASE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BASE))
from server import create_server,ConsoleStore
from customers import Customers,DEFAULT_GROUP,period
from fleet import Fleet
from deploy.maintenance import backup,restore,verify
spec=importlib.util.spec_from_file_location("customer_test_agent",BASE/"node/agent.py")
agent=importlib.util.module_from_spec(spec);spec.loader.exec_module(agent)


class CustomerAPITest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix="kone-customer-api-")
        self.root=Path(self.tmp.name)
        self.server=create_server(port=0,data_dir=self.root/"data")
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.cookie=None;self.csrf=None
        code,data,headers=self.request('/api/auth/setup',{'password':'local-customer-test-only'},admin=False)
        self.assertEqual(code,200)
        self.cookie=headers['Set-Cookie'].split(';')[0];self.csrf=data['csrfToken']
        self.profile=self.request('/api/profiles',{'name':'测试配置 A','config':{
            'outbounds':[{'type':'vless','tag':'test','server':'node.invalid','server_port':443,
                          'uuid':'11111111-1111-4111-8111-111111111111','tls':{'enabled':True}}]}})[1]['id']
        self.group=self.request('/api/customer-groups',{'name':'测试分组','profileId':self.profile,
            'quotaBytes':1000,'deviceLimit':1,'validDays':30})[1]['id']
        self.user=self.new_user('测试客户',self.group)

    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join(timeout=3);self.tmp.cleanup()

    def request(self,path,payload=None,bearer=None,admin=True,method=None,extra=None):
        c=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
        headers={'Content-Type':'application/json'}
        if admin and self.cookie:headers.update({'Cookie':self.cookie,'X-CSRF-Token':self.csrf})
        if bearer:headers['Authorization']='Bearer '+bearer
        if extra:headers.update(extra)
        try:
            c.request(method or ('POST' if payload is not None else 'GET'),path,
                      json.dumps(payload) if payload is not None else None,headers)
            response=c.getresponse();raw=response.read();hdr=dict(response.getheaders())
            return response.status,json.loads(raw) if raw else None,hdr
        finally:c.close()

    def new_user(self,name,group=None):
        code,data,_=self.request('/api/users',{'name':name,'groupId':group or DEFAULT_GROUP})
        self.assertEqual(code,200);return data['id']

    def invite(self,user=None):
        code,data,_=self.request('/api/customer-users/'+(user or self.user)+'/invite',{})
        self.assertEqual(code,200);return data['inviteCode']

    def claim(self,user=None,name='本地测试设备'):
        code,data,_=self.request('/api/client/claim',{'inviteCode':self.invite(user),'deviceName':name},admin=False)
        self.assertEqual(code,200);return data

    def fetch(self,device):
        return self.request('/api/client/config',bearer=device['credential'],admin=False)

    def enroll(self,name):
        fleet=self.server.app.fleet
        ticket=fleet.enrollment({'name':name,'address':'127.0.0.1'})
        directory=self.root/ticket['id'];directory.mkdir()
        return fleet.register({'token':ticket['token'],'certificate':agent.certificate(directory,'127.0.0.1'),
            'os':'synthetic-test','arch':'test','mode':'process'})

    def test_group_inheritance_and_individual_override_without_node_jobs(self):
        device=self.claim();first=self.fetch(device)[1]
        self.assertEqual(first['policy']['quotaScope'],'per_customer')
        profile2=self.request('/api/profiles',{'name':'测试配置 B','config':{'outbounds':[{'type':'direct','tag':'direct'}]}})[1]['id']
        self.assertEqual(self.request('/api/customer-groups/'+self.group+'/update',{'profileId':profile2,'quotaBytes':2000})[0],200)
        second=self.fetch(device)[1]
        self.assertNotEqual(first['revision'],second['revision'])
        self.assertEqual(second['configName'],'测试配置 B')
        self.assertEqual(second['policy']['quotaBytes'],2000)
        self.request('/api/customer-users/'+self.user+'/profile',{'profileId':self.profile})
        self.assertEqual(self.fetch(device)[1]['configName'],'测试配置 A')
        self.assertEqual(self.server.app.fleet.state()['jobs'],[])

    def test_app_only_claim_never_delivers_group_nodes_and_can_later_assign(self):
        code,data,_=self.request('/api/users',{'name':'App only','groupId':self.group,'appOnly':True})
        self.assertEqual(code,200);user=data['id']
        detail=self.server.app.customers.detail(user)
        self.assertTrue(detail['appOnly']);self.assertIsNone(detail['profileId'])
        device=self.claim(user)
        code,payload,_=self.fetch(device)
        self.assertEqual(code,409);self.assertNotIn('config',payload)
        self.assertIn('App 已开通',payload['error'])
        self.assertEqual(self.request('/api/client/claim',{'inviteCode':self.invite(user),'deviceName':'second'},admin=False)[0],409)
        archive=self.root/'app-only-backup.zip';backup(self.root/'data',archive);restore(archive,self.root/'app-only-restored')
        store=ConsoleStore(self.root/'app-only-restored');Fleet(store);restored=Customers(store)
        self.assertTrue(restored.detail(user)['appOnly'])
        self.assertEqual(restored.authenticate('Bearer '+device['credential']),device['deviceId'])
        self.assertEqual(self.request('/api/customer-groups/'+self.group+'/update',{'profileId':self.profile})[0],200)
        self.assertEqual(self.fetch(device)[0],409)
        self.assertEqual(self.request('/api/customer-users/'+user+'/configure',{'groupId':self.group,'profileId':None,'appOnly':False})[0],200)
        code,payload,_=self.fetch(device);self.assertEqual(code,200)
        self.assertEqual(payload['configName'],'测试配置 A')

    def test_app_only_pause_revoke_and_no_silent_conversion_of_bound_devices(self):
        code,data,_=self.request('/api/users',{'name':'App permission','appOnly':True})
        self.assertEqual(code,200);user=data['id'];ticket=self.invite(user)
        self.assertEqual(self.request('/api/customer-users/'+user+'/pause',{'paused':True})[0],200)
        self.assertEqual(self.request('/api/client/claim',{'inviteCode':ticket,'deviceName':'test'},admin=False)[0],403)
        self.assertEqual(self.request('/api/customer-users/'+user+'/pause',{'paused':False})[0],200)
        device=self.claim(user)
        self.assertEqual(self.request('/api/customer-users/'+user+'/revoke',{})[0],200)
        self.assertEqual(self.fetch(device)[0],401)
        normal=self.claim()
        self.assertEqual(self.request('/api/customer-users/'+self.user+'/configure',{'groupId':self.group,'profileId':None,'appOnly':True})[0],409)
        self.assertEqual(self.fetch(normal)[0],200)
        self.assertEqual(self.request('/api/users',{'name':'bad flag','appOnly':'true'})[0],400)

    def test_once_expiring_invites_and_per_customer_device_limits(self):
        old=self.invite();new=self.invite()
        self.assertEqual(self.request('/api/client/claim',{'inviteCode':old,'deviceName':'test'},admin=False)[0],401)
        first=self.request('/api/client/claim',{'inviteCode':new,'deviceName':'test'},admin=False)
        self.assertEqual(first[0],200)
        self.assertEqual(self.request('/api/client/claim',{'inviteCode':new,'deviceName':'test'},admin=False)[0],401)
        token=self.invite()
        self.assertEqual(self.request('/api/client/claim',{'inviteCode':token,'deviceName':'extra'},admin=False)[0],409)
        with self.server.app.store.connect() as c:c.execute('UPDATE customer_invites SET expires=0')
        self.assertEqual(self.request('/api/client/claim',{'inviteCode':token,'deviceName':'extra'},admin=False)[0],401)

    def test_claim_is_atomic_under_concurrent_redemption(self):
        token=self.invite();statuses=[]
        def redeem():statuses.append(self.request('/api/client/claim',{'inviteCode':token,'deviceName':'concurrent'},admin=False)[0])
        workers=[threading.Thread(target=redeem) for _ in range(2)]
        for worker in workers:worker.start()
        for worker in workers:worker.join(timeout=5)
        self.assertEqual(sorted(statuses),[200,401])
        self.assertEqual(self.server.app.customers.detail(self.user)['deviceCount'],1)

    def test_devices_nodes_and_admin_roles_cannot_impersonate_each_other(self):
        device=self.claim();node=self.enroll('模拟节点，仅验证凭据')
        for endpoint in ('/api/customers','/api/state','/api/fleet','/api/customer-users/'+self.user+'/detail'):
            self.assertEqual(self.request(endpoint,bearer=device['credential'],admin=False)[0],401)
        self.assertEqual(self.request('/api/node/poll',{},bearer=device['credential'],admin=False)[0],401)
        self.assertEqual(self.request('/api/client/config',bearer=node['credential'],admin=False)[0],401)
        self.assertEqual(self.request('/api/client/config')[0],401)
        self.assertEqual(self.fetch(device)[0],200)
        self.assertEqual(self.request('/api/client/config',bearer=device['credential'],admin=False,method='HEAD')[0],405)
        self.assertEqual(self.request('/api/customer-groups',{'name':'invalid csrf'},extra={'X-CSRF-Token':'invalid'})[0],403)

    def test_acknowledgements_require_current_issued_revision_and_preserve_last_good(self):
        device=self.claim();body=self.fetch(device)[1];credential=device['credential']
        endpoint='/api/client/ack'
        ack={'revision':body['revision'],'status':'applied','code':'ok'}
        self.assertEqual(self.request(endpoint,ack,bearer=credential,admin=False)[0],200)
        self.assertEqual(self.request(endpoint,ack,bearer=credential,admin=False)[0],200)
        self.request(endpoint,{**ack,'status':'received'},bearer=credential,admin=False)
        detail=self.server.app.customers.detail(self.user)
        self.assertEqual(detail['appliedCount'],1)
        self.assertEqual(sum(e['code']=='applied' for e in detail['events']),1)
        self.request('/api/customer-groups/'+self.group+'/update',{'quotaBytes':2000})
        self.assertEqual(self.request(endpoint,ack,bearer=credential,admin=False)[0],409)
        changed=self.fetch(device)[1]
        self.assertEqual(changed['configRevision'],body['configRevision'],'quota-only update must not require changing proxy config')
        self.request(endpoint,{'revision':changed['revision'],'status':'failed','code':'config_invalid'},bearer=credential,admin=False)
        detail=self.server.app.customers.detail(self.user)
        self.assertEqual(detail['appliedCount'],0)
        self.assertEqual(detail['devices'][0]['applied_revision'],body['revision'])
        self.assertEqual(self.request(endpoint,{'revision':changed['revision'],'status':'failed','code':[]},bearer=credential,admin=False)[0],400)

    def test_own_configuration_and_redacted_diagnostics_do_not_include_other_customers_or_secrets(self):
        device=self.claim();other=self.new_user('其他客户',self.group)
        self.request('/api/customer-users/'+other+'/profile',{'profileId':self.request('/api/profiles',{
            'name':'其他客户私有配置','config':{'outbounds':[{'type':'direct'}]}})[1]['id']})
        fetched=self.request('/api/client/config?userId='+other,bearer=device['credential'],admin=False)
        self.assertEqual(fetched[1]['configName'],'测试配置 A')
        self.assertEqual(self.request('/api/client/diagnostics',{'appVersion':'0.0-test','network':'wifi',
            'downloadBytes':999999,'uploadBytes':777,'events':['dns_failed'],
            'rawLog':'vless://do-not-store-sensitive-log','config':{'secret':'never-store'}},bearer=device['credential'],admin=False)[0],200)
        detail=self.request('/api/customer-users/'+self.user+'/diagnostics')[1]
        encoded=json.dumps(detail)
        for value in ('do-not-store-sensitive-log','never-store',device['credential'],'11111111-1111-4111-8111-111111111111'):
            self.assertNotIn(value,encoded)
        self.assertEqual(detail['usage']['totalBytes'],None)
        self.assertEqual(detail['usage']['source'],'not_collected')
        self.assertEqual(detail['devices'][0]['report_rx'],999999)
        self.assertEqual(self.fetch(device)[0],200,'client report must not consume quota')
        self.assertEqual(self.request('/api/client/diagnostics',{'events':['secret raw text']},bearer=device['credential'],admin=False)[0],400)

    def test_pause_expiry_revoke_and_limit_reduction_gate_delivery_without_proxy_changes(self):
        self.request('/api/customer-groups/'+self.group+'/update',{'deviceLimit':2})
        first=self.claim(name='first');second=self.claim(name='second')
        self.request('/api/customer-groups/'+self.group+'/update',{'deviceLimit':1})
        self.assertEqual(self.fetch(first)[0],200);self.assertEqual(self.fetch(second)[0],403)
        self.request('/api/customer-users/'+self.user+'/pause',{'paused':True})
        self.assertEqual(self.fetch(first)[0],403)
        self.request('/api/customer-users/'+self.user+'/pause',{'paused':False})
        with self.server.app.store.connect() as c:c.execute('UPDATE customer_accounts SET joined=? WHERE user_id=?',(time.time()-31*86400,self.user))
        self.assertEqual(self.fetch(first)[0],403)
        self.request('/api/customer-groups/'+self.group+'/update',{'validDays':0})
        self.assertEqual(self.fetch(first)[0],200)
        self.request('/api/customer-users/'+self.user+'/devices/'+first['deviceId']+'/revoke',{})
        self.assertEqual(self.fetch(first)[0],401)
        self.assertEqual(self.server.app.fleet.state()['jobs'],[])

    def test_atomic_configuration_rejects_invalid_profile_and_invalid_group_user_creation(self):
        before=self.server.app.customers.detail(self.user)
        code,_,_=self.request('/api/customer-users/'+self.user+'/configure',{'groupId':DEFAULT_GROUP,'profileId':'missing'})
        self.assertEqual(code,404)
        self.assertEqual(self.server.app.customers.detail(self.user)['groupId'],before['groupId'])
        count=len(self.server.app.store.state()['users'])
        self.assertEqual(self.request('/api/users',{'name':'invalid','groupId':{'bad':'value'}})[0],400)
        self.assertEqual(len(self.server.app.store.state()['users']),count)
        self.assertEqual(self.request('/api/customer-groups',{'name':'bad','quotaBytes':True})[0],400)

    def test_entry_only_usage_dedup_reset_and_individual_quota(self):
        first=self.enroll('模拟入口');last=self.enroll('模拟出口')
        fleet=self.server.app.fleet
        node=fleet.add_node({'serverId':first['serverId'],'name':'入口','kind':'vless','port':33443})['id']
        exit_node=fleet.add_node({'serverId':last['serverId'],'name':'出口','kind':'hysteria2','port':33443})['id']
        route=fleet.add_route({'name':'本地逻辑计量样本','nodeIds':[node,exit_node]})['id']
        other=self.new_user('同组另一个客户',self.group)
        fleet.assign({'routeId':route,'userIds':[self.user,other]})
        for server in (first,last):
            job=fleet.poll(server['serverId'])['job']
            fleet.complete(server['serverId'],{'jobId':job['id'],'lease':job['lease'],'status':'running'})
        samples=[{'userId':u,'routeId':route,'counterEpoch':'qa-cycle','downloadBytes':1000,'uploadBytes':500} for u in (self.user,other)]
        self.assertEqual(self.request('/api/node/usage',{'samples':samples},bearer=last['credential'],admin=False)[0],403)
        self.assertEqual(self.request('/api/node/usage',{'samples':samples},bearer=first['credential'],admin=False)[0],200)
        samples[0]['downloadBytes']=2000;samples[1]['downloadBytes']=1100
        self.assertEqual(self.request('/api/node/usage',{'samples':samples},bearer=first['credential'],admin=False)[0],200)
        self.request('/api/node/usage',{'samples':samples},bearer=first['credential'],admin=False)
        self.assertEqual(self.server.app.customers.detail(self.user)['usage']['totalBytes'],1000)
        self.assertEqual(self.server.app.customers.detail(other)['usage']['totalBytes'],100)
        self.assertEqual(self.server.app.customers.detail(self.user)['accessState'],'quota_reached')
        self.assertEqual(self.server.app.customers.detail(other)['accessState'],'active')
        snapshots=self.server.app.customers.detail(other)['usage']['totalBytes']
        samples[0]['downloadBytes']=2100;samples[1]['downloadBytes']=1000
        self.assertEqual(self.request('/api/node/usage',{'samples':samples},bearer=first['credential'],admin=False)[0],409)
        self.assertEqual(self.server.app.customers.detail(self.user)['usage']['totalBytes'],1000,'whole batch must roll back')
        reset=[{**samples[1],'counterEpoch':'qa-new-cycle','downloadBytes':0,'uploadBytes':0}]
        self.request('/api/node/usage',{'samples':reset},bearer=first['credential'],admin=False)
        self.assertEqual(self.server.app.customers.detail(other)['usage']['totalBytes'],snapshots)
        self.assertIsNotNone(self.server.app.customers.detail(other)['usage']['coverageStart'])

    def test_new_schema_backup_restore_preserves_delivery_credentials_and_default_policy(self):
        device=self.claim();envelope=self.fetch(device)[1]
        self.request('/api/client/ack',{'revision':envelope['revision'],'status':'received'},bearer=device['credential'],admin=False)
        snapshot=self.server.app.customers.detail(self.user)
        archive=self.root/'private-backup.zip';meta=backup(self.root/'data',archive)
        self.assertEqual(meta['user_version'],6);self.assertEqual(verify(archive),meta)
        restore(archive,self.root/'restored')
        store=ConsoleStore(self.root/'restored');Fleet(store);customers=Customers(store)
        self.assertEqual(customers.authenticate('Bearer '+device['credential']),device['deviceId'])
        self.assertEqual(customers.detail(self.user),snapshot)
        self.assertEqual(customers.configuration(device['deviceId'])['revision'],envelope['revision'])
        with store.connect() as c:self.assertEqual(c.execute('PRAGMA user_version').fetchone()[0],6)

    def test_upgrade_version_two_preserves_old_records_and_does_not_downgrade_schema(self):
        directory=self.root/'old-version-two'
        old=ConsoleStore(directory);Fleet(old)
        old.setup('old-version-test-only')
        user=old.add_user({'name':'历史客户'})['id']
        profile=old.import_profile({'name':'旧配置','config':{'outbounds':[{'type':'direct'}]},'userIds':[user]})['id']
        before=old.state();password=old.password_hash()
        customers=Customers(old)
        self.assertEqual(old.state(),before);self.assertEqual(old.password_hash(),password)
        self.assertEqual(customers.detail(user)['profileId'],profile)
        self.assertEqual(customers.detail(user)['groupId'],DEFAULT_GROUP)
        Fleet(old)
        with old.connect() as c:self.assertEqual(c.execute('PRAGMA user_version').fetchone()[0],5)

    def test_new_month_has_its_own_baseline_without_charging_previous_month_again(self):
        node=self.enroll('模拟月界入口');fleet=self.server.app.fleet
        protocol=fleet.add_node({'serverId':node['serverId'],'name':'入口','kind':'vless','port':34443})['id']
        route=fleet.add_route({'name':'月界测试','nodeIds':[protocol]})['id']
        fleet.assign({'routeId':route,'userIds':[self.user]})
        job=fleet.poll(node['serverId'])['job']
        fleet.complete(node['serverId'],{'jobId':job['id'],'lease':job['lease'],'status':'running'})
        sample={'userId':self.user,'routeId':route,'counterEpoch':'same-collector','downloadBytes':1000,'uploadBytes':0}
        meter=self.server.app.customers
        with patch('customers.period',return_value='2026-10'):
            meter.ingest_usage(node['serverId'],{'samples':[sample]})
            sample['downloadBytes']=1400;meter.ingest_usage(node['serverId'],{'samples':[sample]})
            self.assertEqual(meter.detail(self.user)['usage']['totalBytes'],400)
        with patch('customers.period',return_value='2026-11'):
            sample['downloadBytes']=1500;meter.ingest_usage(node['serverId'],{'samples':[sample]})
            self.assertEqual(meter.detail(self.user)['usage']['totalBytes'],0)
            sample['downloadBytes']=1800;meter.ingest_usage(node['serverId'],{'samples':[sample]})
            self.assertEqual(meter.detail(self.user)['usage']['totalBytes'],300)
        with patch('customers.period',return_value='2026-10'):
            self.assertEqual(meter.detail(self.user)['usage']['totalBytes'],400)

    def test_delete_removes_customer_data_and_invalidates_devices_and_codes(self):
        device = self.claim()
        self.assertEqual(self.fetch(device)[0], 200)
        code = self.invite()
        friend = self.new_user('保留客户', self.group)
        friend_device = self.claim(friend)
        path = '/api/customer-users/' + self.user + '/delete'
        self.assertEqual(self.request(path, {}, admin=False, bearer=device['credential'])[0], 401)
        self.assertEqual(self.request(path, {}, extra={'X-CSRF-Token': 'invalid'})[0], 403)
        self.assertEqual(self.request(path, {})[0], 200)
        self.assertEqual(self.fetch(device)[0], 401)
        self.assertEqual(self.fetch(friend_device)[0], 200)
        self.assertEqual(self.request('/api/client/claim', {'inviteCode': code, 'deviceName': '旧领取码'}, admin=False)[0], 401)
        self.assertEqual(self.request('/api/customer-users/' + self.user + '/detail')[0], 404)
        self.assertEqual(self.request('/api/customer-users/' + self.user + '/restore', {})[0], 404)
        self.assertNotIn(self.user, {u['id'] for u in self.request('/api/state')[1]['users']})
        with self.server.app.store.connect() as c:
            for table in ('customer_accounts', 'customer_invites', 'customer_devices', 'customer_events', 'customer_usage', 'customer_service_actions'):
                self.assertEqual(c.execute('SELECT COUNT(*) FROM ' + table + ' WHERE user_id=?', (self.user,)).fetchone()[0], 0)
            self.assertEqual(c.execute('SELECT COUNT(*) FROM customer_deliveries WHERE device_id=?', (device['deviceId'],)).fetchone()[0], 0)
            self.assertIsNone(c.execute('PRAGMA foreign_key_check').fetchone())
            self.assertIsNotNone(c.execute('SELECT id FROM profiles WHERE id=?', (self.profile,)).fetchone())
            self.assertEqual(c.execute("SELECT COUNT(*) FROM records WHERE title='删除客户'").fetchone()[0], 1)

    def test_delete_queues_node_removal_preserves_peers_and_works_with_claude_block(self):
        fleet = self.server.app.fleet
        entry, other = self.enroll('删除入口'), self.enroll('不相关服务器')
        node = fleet.add_node({'serverId': entry['serverId'], 'kind': 'vless', 'name': '测试节点', 'port': 34443})['id']
        route = fleet.add_route({'name': '测试删除线路', 'nodeIds': [node]})['id']
        friend = self.new_user('同线路保留客户', self.group)
        fleet.assign({'routeId': route, 'userIds': [self.user, friend]})
        self.server.app.customers.claude(self.user, {'blocked': True})
        with self.server.app.store.connect() as c:
            other_revision = c.execute('SELECT desired FROM fleet_servers WHERE id=?', (other['serverId'],)).fetchone()[0]
            friend_secret = c.execute('SELECT credential FROM fleet_credentials WHERE principal=?', (friend,)).fetchone()[0]
        result = self.request('/api/customer-users/' + self.user + '/delete', {})
        self.assertEqual(result[0], 200)
        self.assertEqual(result[1]['nodeControl']['status'], 'pending')
        job = fleet.poll(entry['serverId'])['job']
        payload = json.dumps(job['config'])
        self.assertNotIn('/' + self.user, payload)
        self.assertIn('/' + friend, payload)
        self.assertIn(friend_secret, payload)
        with self.server.app.store.connect() as c:
            self.assertEqual(c.execute('SELECT desired FROM fleet_servers WHERE id=?', (other['serverId'],)).fetchone()[0], other_revision)
            self.assertEqual(c.execute('SELECT COUNT(*) FROM fleet_credentials WHERE principal=?', (self.user,)).fetchone()[0], 0)
            self.assertIsNone(c.execute('PRAGMA foreign_key_check').fetchone())
        fleet.complete(entry['serverId'], {'jobId': job['id'], 'lease': job['lease'], 'status': 'running'})
        self.assertEqual({a['userId'] for r in fleet.state()['routes'] for a in r['assignments']}, {friend})

    def test_delete_rolls_back_credentials_records_and_jobs_when_queue_fails(self):
        fleet = self.server.app.fleet
        entry = self.enroll('失败回滚入口')
        node = fleet.add_node({'serverId': entry['serverId'], 'kind': 'vless', 'name': '测试节点', 'port': 34443})['id']
        route = fleet.add_route({'name': '测试线路', 'nodeIds': [node]})['id']
        device = self.claim()
        fleet.assign({'routeId': route, 'userIds': [self.user]})
        with self.server.app.store.connect() as c:
            before = {t: [tuple(r) for r in c.execute('SELECT * FROM ' + t)] for t in ('users', 'customer_accounts', 'customer_invites', 'customer_devices', 'records', 'fleet_jobs', 'fleet_servers')}
        from customers import CustomerError
        with patch.object(fleet, 'queue', side_effect=CustomerError(409, '测试队列故障')):
            self.assertEqual(self.request('/api/customer-users/' + self.user + '/delete', {})[0], 409)
        with self.server.app.store.connect() as c:
            self.assertEqual(before, {t: [tuple(r) for r in c.execute('SELECT * FROM ' + t)] for t in before})
        self.assertEqual(self.server.app.customers.authenticate('Bearer ' + device['credential']), device['deviceId'])

    def test_service_revoke_invalidates_all_codes_devices_and_cannot_be_undone_by_resume(self):
        self.request('/api/customer-groups/'+self.group+'/update',{'deviceLimit':2})
        first=self.claim(name='first');second=self.claim(name='second');pending=self.invite()
        self.assertEqual(self.request('/api/customer-users/'+self.user+'/revoke',{},bearer=first['credential'],admin=False)[0],401)
        self.assertEqual(self.request('/api/customer-users/'+self.user+'/revoke',{})[0],200)
        for device in (first,second):self.assertEqual(self.fetch(device)[0],401)
        self.assertEqual(self.request('/api/client/claim',{'inviteCode':pending,'deviceName':'blocked'},admin=False)[0],401)
        self.request('/api/customer-users/'+self.user+'/pause',{'paused':False})
        self.assertEqual(self.request('/api/customer-users/'+self.user+'/invite',{})[0],409)
        self.request('/api/customer-users/'+self.user+'/revoke',{})
        detail=self.server.app.customers.detail(self.user)
        self.assertTrue(detail['revoked']);self.assertEqual(detail['deviceCount'],0)
        self.assertEqual(sum(e['code']=='service_revoked' for e in detail['events']),1)
        self.assertEqual(len(detail['devices']),2)
        self.assertEqual(self.server.app.fleet.state()['jobs'],[])
        self.request('/api/customer-users/'+self.user+'/restore',{})
        for device in (first,second):self.assertEqual(self.fetch(device)[0],401)
        replacement=self.claim(name='replacement');self.assertEqual(self.fetch(replacement)[0],200)

    def test_managed_revoke_covers_historical_nodes_preserves_other_users_and_rotates_on_restore(self):
        fleet=self.server.app.fleet
        a=self.enroll('旧入口，逻辑测试');b=self.enroll('现入口，逻辑测试');other=self.enroll('不相关服务器')
        protocols=[fleet.add_node({'serverId':s['serverId'],'name':'test','kind':'vless','port':34443})['id'] for s in (a,b,other)]
        routes=[fleet.add_route({'name':'test','nodeIds':[n]})['id'] for n in protocols]
        friend=self.new_user('同节点其他客户',self.group)
        fleet.assign({'routeId':routes[0],'userIds':[self.user]})
        job=fleet.poll(a['serverId'])['job'];fleet.complete(a['serverId'],{'jobId':job['id'],'lease':job['lease'],'status':'running'})
        # Old A removal is deliberately left unacknowledged, simulating an offline old path.
        fleet.assign({'routeId':routes[1],'userIds':[self.user,friend]})
        job=fleet.poll(b['serverId'])['job'];fleet.complete(b['serverId'],{'jobId':job['id'],'lease':job['lease'],'status':'running'})
        device=self.claim()
        with self.server.app.store.connect() as c:
            old_secret=c.execute('SELECT credential FROM fleet_credentials WHERE node_id=? AND principal=?',(protocols[1],self.user)).fetchone()[0]
            friend_secret=c.execute('SELECT credential FROM fleet_credentials WHERE node_id=? AND principal=?',(protocols[1],friend)).fetchone()[0]
            untouched=c.execute('SELECT desired FROM fleet_servers WHERE id=?',(other['serverId'],)).fetchone()[0]
        result=self.request('/api/customer-users/'+self.user+'/revoke',{})
        self.assertEqual(result[0],200)
        self.assertEqual({n['id'] for n in result[1]['nodeControl']['nodes']},{a['serverId'],b['serverId']})
        self.assertEqual(result[1]['nodeControl']['status'],'pending');self.assertEqual(self.fetch(device)[0],401)
        self.assertEqual(self.request('/api/fleet/assignments',{'routeId':routes[1],'userIds':[self.user]})[0],409)
        for s in (a,b):
            job=fleet.poll(s['serverId'])['job']
            payload=json.dumps(job['config'])
            self.assertNotIn('/'+self.user,payload)
            if s==b:self.assertIn('/'+friend,payload);self.assertIn(friend_secret,payload)
            fleet.complete(s['serverId'],{'jobId':job['id'],'lease':job['lease'],'status':'running'})
        self.assertEqual(self.server.app.customers.detail(self.user)['nodeControl']['status'],'applied')
        with self.server.app.store.connect() as c:self.assertEqual(c.execute('SELECT desired FROM fleet_servers WHERE id=?',(other['serverId'],)).fetchone()[0],untouched)
        self.request('/api/customer-users/'+self.user+'/restore',{})
        self.assertEqual(self.request('/api/customer-users/'+self.user+'/invite',{})[0],409)
        for s in (a,b):
            job=fleet.poll(s['serverId'])['job']
            self.assertNotIn(old_secret,json.dumps(job['config']))
            fleet.complete(s['serverId'],{'jobId':job['id'],'lease':job['lease'],'status':'running'})
        replacement=self.claim();self.assertEqual(self.fetch(replacement)[0],200);self.assertEqual(self.fetch(device)[0],401)
        with self.server.app.store.connect() as c:
            new_secret=c.execute('SELECT credential FROM fleet_credentials WHERE node_id=? AND principal=?',(protocols[1],self.user)).fetchone()[0]
            self.assertNotEqual(new_secret,old_secret)
            self.assertEqual(c.execute('SELECT credential FROM fleet_credentials WHERE node_id=? AND principal=?',(protocols[1],friend)).fetchone()[0],friend_secret)
        owned=self.server.app.customers.detail(self.user)['profileId']
        self.assertEqual(self.request('/api/customer-groups/'+self.group+'/update',{'profileId':owned})[0],400)
        self.assertEqual(self.request('/api/customer-users/'+friend+'/profile',{'profileId':owned})[0],400)
        self.assertEqual(self.request('/api/assignments',{'profileId':owned,'userIds':[friend]})[0],400)


if __name__=='__main__':unittest.main()
