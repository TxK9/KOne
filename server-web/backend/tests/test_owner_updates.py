import hashlib
import http.client
import json
import uuid
import unittest
from test_customers import CustomerAPITest

class OwnerUpdateTest(unittest.TestCase):
    setUp = CustomerAPITest.setUp
    tearDown = CustomerAPITest.tearDown
    request = CustomerAPITest.request
    new_user = CustomerAPITest.new_user

    def configured(self):
        app = self.server.app
        app.public_api_authority = 'updates.example.invalid'
        app.allowed_origins[app.public_api_authority] = {'https://' + app.public_api_authority}
        directory = app.owner_updates.directory
        directory.mkdir()
        content = b'fake apk for route and digest tests only'
        self.path = '/downloads/KOne-owner-b10030.apk'
        (directory / self.path.split('/')[-1]).write_bytes(content)
        manifest = {'updateFormat':1,'state':'ready','approvedForDownload':True,'channel':'owner',
                    'applicationId':'cc.txwd.tianxiawudi.proxy','versionCode':10030,'version':'1.0.0',
                    'url':self.path,'bytes':len(content),'sha256':hashlib.sha256(content).hexdigest()}
        (directory/'release.json').write_text(json.dumps(manifest))
        return content

    def enrollment(self, identifier=None):
        return self.request('/api/owner-update/enroll', {'deviceId':identifier or str(uuid.uuid4()),'name':'测试手机'})

    def raw(self, path, bearer=None, admin=False, public=False, head=False, extra=None):
        headers = dict(extra or {})
        if bearer: headers['Authorization'] = 'Bearer '+bearer
        if admin: headers['Cookie'] = self.cookie
        if public: headers['Host'] = 'updates.example.invalid'
        c = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)
        try:
            c.request('HEAD' if head else 'GET', path, headers=headers)
            r=c.getresponse(); return r.status,r.read(),dict(r.getheaders())
        finally:c.close()

    def test_no_private_artifacts_without_scoped_authority(self):
        content=self.configured()
        self.assertEqual(self.raw(self.path)[0],401)
        self.assertEqual(self.raw(self.path,public=True,admin=True)[0],401)
        self.assertEqual(self.raw('/api/owner/update',public=True)[0],401)
        self.assertEqual(self.raw('/api/owner-release',public=True,admin=True)[0],404)
        code,enrollment,_=self.enrollment();self.assertEqual(code,200)
        credential=enrollment['credential']
        self.assertEqual(self.raw(self.path,credential,public=True)[1],content)
        self.assertEqual(self.raw(self.path,admin=True)[1],content)
        status,body,hdr=self.raw(self.path,credential,public=True,head=True)
        self.assertEqual(status,200);self.assertEqual(body,b'');self.assertEqual(hdr['Content-Length'],str(len(content)))
        self.assertEqual(hdr['Cache-Control'],'private, no-store')
        feed=self.request('/api/owner/update',bearer=credential,admin=False,extra={'Host':'updates.example.invalid'})[1]
        self.assertEqual(feed['channel'],'owner');self.assertFalse(feed['automaticUpdate'])
        for path in ['/api/state','/api/customers','/api/feedback','/api/owner-release']:
            self.assertEqual(self.request(path,bearer=credential,admin=False)[0],401)
        self.assertEqual(self.request('/api/client/config',bearer=credential,admin=False)[0],401)
        with self.server.app.store.connect() as db:
            text=str([tuple(row) for row in db.execute('SELECT * FROM owner_update_devices')])
            self.assertNotIn(credential,text)

    def test_resume_ranges_cover_cookie_and_device_channels(self):
        content = self.configured()
        token = self.enrollment()[1]['credential']
        etag = '"' + hashlib.sha256(content).hexdigest() + '"'
        for authority in [dict(admin=True), dict(bearer=token, public=True)]:
            pieces = []
            for start, end in [(0, 7), (8, len(content)-1)]:
                status, body, hdr = self.raw(self.path, **authority, extra={
                    'Range': 'bytes=%d-%d' % (start,end), 'If-Range': etag})
                self.assertEqual(status,206)
                self.assertEqual(body,content[start:end+1]);pieces.append(body)
                self.assertEqual(hdr['Content-Range'],'bytes %d-%d/%d' % (start,end,len(content)))
                self.assertEqual(hdr['Content-Length'],str(len(body)))
                self.assertEqual(hdr['ETag'],etag);self.assertEqual(hdr['Accept-Ranges'],'bytes')
            self.assertEqual(b''.join(pieces),content)

    def test_suffix_open_ended_and_unsatisfiable_ranges(self):
        content=self.configured()
        for requested,expected in [('bytes=8-',content[8:]),('bytes=-8',content[-8:]),
                                   ('bytes=0-9999',content),('bytes=-9999',content)]:
            status,body,_=self.raw(self.path,admin=True,extra={'Range':requested})
            self.assertEqual(status,206);self.assertEqual(body,expected)
        for requested in ['bytes=9999-','bytes=-0','bytes=8-2']:
            status,body,hdr=self.raw(self.path,admin=True,extra={'Range':requested})
            self.assertEqual(status,416);self.assertEqual(body,b'')
            self.assertEqual(hdr['Content-Range'],'bytes */%d'%len(content))

    def test_mismatched_validator_and_invalid_ranges_cannot_mix_files(self):
        content=self.configured()
        for extra in [{'Range':'bytes=8-','If-Range':'"old-sha"'},
                      {'Range':'bytes=0-7,9-10'}, {'Range':'nonsense'}]:
            status,body,_=self.raw(self.path,admin=True,extra=extra)
            self.assertEqual(status,200);self.assertEqual(body,content)
        status,body,hdr=self.raw(self.path,admin=True,head=True,extra={'Range':'bytes=8-'})
        self.assertEqual(status,200);self.assertEqual(body,b'')
        self.assertEqual(hdr['Content-Length'],str(len(content)))

    def test_resume_still_authenticates_each_request_and_revoke(self):
        self.configured();identifier=str(uuid.uuid4())
        token=self.enrollment(identifier)[1]['credential']
        extra={'Range':'bytes=0-7'}
        self.assertEqual(self.raw(self.path,extra=extra)[0],401)
        self.assertEqual(self.raw(self.path,admin=True,public=True,extra=extra)[0],401)
        self.assertEqual(self.raw(self.path,token,public=True,extra=extra)[0],206)
        self.request('/api/owner-update/revoke',{'deviceId':identifier})
        self.assertEqual(self.raw(self.path,token,public=True,extra=extra)[0],401)

    def test_enrollment_requires_admin_and_csrf_and_revokes_immediately(self):
        self.configured();identifier=str(uuid.uuid4())
        payload={'deviceId':identifier,'name':'手机'}
        self.assertEqual(self.request('/api/owner-update/enroll',payload,admin=False)[0],401)
        self.assertEqual(self.request('/api/owner-update/enroll',payload,extra={'X-CSRF-Token':'bad'})[0],403)
        self.assertEqual(self.request('/api/owner-update/enroll',payload,extra={'Host':'updates.example.invalid'})[0],404)
        old=self.enrollment(identifier)[1]['credential'];new=self.enrollment(identifier)[1]['credential']
        self.assertEqual(self.raw(self.path,old,public=True)[0],401)
        self.assertEqual(self.raw(self.path,new,public=True)[0],200)
        self.assertEqual(self.request('/api/owner-update/revoke',{'deviceId':identifier})[0],200)
        self.assertEqual(self.raw(self.path,new,public=True)[0],401)
        self.assertEqual(self.request('/api/owner-release')[1]['devices'],[])

    def test_corruption_wrong_channel_paths_and_wrong_version_fail_closed(self):
        self.configured();token=self.enrollment()[1]['credential']
        self.assertEqual(self.raw('/downloads/KOne-owner-b10029.apk',token,public=True)[0],404)
        for path in [self.path+'?token=x','/downloads/../owner-updates/release.json','/downloads/KOne-client-b10030.apk']:
            self.assertEqual(self.raw(path,token,public=True)[0],404)
        directory=self.server.app.owner_updates.directory
        manifest=directory/'release.json';original=manifest.read_text()
        value=json.loads(original);value['channel']='invite-client';manifest.write_text(json.dumps(value))
        self.assertEqual(self.raw(self.path,token)[0],503)
        manifest.write_text(original)
        apk=directory/self.path.split('/')[-1];apk.write_bytes(b'x'*apk.stat().st_size)
        self.assertEqual(self.raw(self.path,token)[0],503)

del CustomerAPITest
