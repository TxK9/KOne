"""Local-only QA fixture server. Not part of the production entry point."""
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlsplit
ROOT = Path(__file__).resolve().parent.parent / "website"
SHIMS = {
 'legacy': "const nativeMatch=window.matchMedia.bind(window);window.matchMedia=q=>{const m=nativeMatch(q);m.addEventListener=undefined;m.removeEventListener=undefined;return m;};Array.prototype.at=undefined;",
 'low-power': "Object.defineProperty(navigator,'hardwareConcurrency',{get:()=>2});",
 'reduced': "const nativeMatch=window.matchMedia.bind(window);window.matchMedia=q=>q.includes('prefers-reduced-motion')?{matches:true,media:q,addListener(){},removeListener(){},addEventListener(){},removeEventListener(){}}:nativeMatch(q);",
 'broken': '',
 'no-js': '',
}
class Handler(SimpleHTTPRequestHandler):
 def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(ROOT),**kwargs)
 def do_GET(self):
  path=urlsplit(self.path).path
  if path.startswith('/_qa/'):
   name=path.rsplit('/',1)[-1]
   if name not in SHIMS:return self.send_error(404)
   html=(ROOT/'index.html').read_text().replace('<head>','<head><base href="/">')
   if name=='no-js':
    import re
    html=re.sub(r'<script\b[^>]*>.*?</script>','',html,flags=re.S).replace('<noscript>','<div>').replace('</noscript>','</div>')
   else:
    # Apply the same compatibility environment inside the child preview, too.
    html=html.replace('<head>','<head><script>'+SHIMS[name]+'</script>',1)
    html=html.replace('data-src="world/?','data-src="'+('/missing-preview?' if name=='broken' else '/_qa/world-'+name+'?'))
   body=html.encode();self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
  return super().do_GET()
 def log_message(self,*args):pass
# Child fixture routes use the exact public renderer and scripts, with a test shim.
original=Handler.do_GET
def fixture_get(self):
 path=urlsplit(self.path).path
 if path.startswith('/_qa/world-'):
  name=path.split('world-',1)[1]
  if name not in SHIMS:return self.send_error(404)
  html=(ROOT/'world/index.html').read_text().replace('<head>','<head><base href="/world/"><script>'+SHIMS[name]+'</script>',1)
  body=html.encode();self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
 return original(self)
Handler.do_GET=fixture_get
if __name__=='__main__':ThreadingHTTPServer(('127.0.0.1',8768),Handler).serve_forever()
