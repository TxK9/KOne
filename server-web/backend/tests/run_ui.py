"""Run UI checks with parent-owned disposable state on Windows and POSIX."""
import os
from pathlib import Path
import re
import signal
import shutil
import subprocess
import sys
import tempfile

BASE=Path(__file__).resolve().parents[1]
NODE=os.environ.get('KONE_TEST_NODE') or shutil.which('node')
if not NODE:raise RuntimeError('Install Node.js on PATH or set KONE_TEST_NODE')
env={**os.environ,'PYTHONUTF8':'1'}
with tempfile.TemporaryDirectory(prefix='kone-ui-test-') as temporary:
    server=subprocess.Popen([sys.executable,'-X','utf8','-B',str(BASE/'tests/demo_console.py'),'--port','0','--work-dir',temporary],cwd=BASE,stdout=subprocess.PIPE,text=True,encoding='utf-8',env=env)
    try:
        line=server.stdout.readline()
        match=re.search(r'http://127\.0\.0\.1:(\d+)',line)
        if not match:raise RuntimeError('Disposable test server failed to start')
        env['KONE_TEST_ORIGIN']='http://127.0.0.1:'+match[1]
        for name in ['ui.spec.cjs','art-check.cjs','compatibility.cjs']:
            subprocess.run([NODE,str(BASE/'tests'/name)],cwd=BASE,env=env,check=True)
    finally:
        if server.poll() is None:
            if os.name=='nt':server.terminate()
            else:server.send_signal(signal.SIGINT)
        try:server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill();server.wait()
