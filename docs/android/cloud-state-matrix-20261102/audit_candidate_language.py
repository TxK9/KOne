from pathlib import Path
import subprocess,re,json,tokenize,io,unicodedata
import argparse
parser=argparse.ArgumentParser()
parser.add_argument('--server-root',type=Path,required=True)
parser.add_argument('--android-root',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
specs=[('server',args.server_root,'b37275d9ec568c73a92b6bc0e7ef6805061b4ed9',['server-web']),('android',args.android_root,'0dd7a9ff0d78743d8910e05041bcbb07c7d598a5',['android/app/src','android/settings.gradle.kts','tools','docs/android'])]

def han(c):return 0x3400<=ord(c)<=0x9fff or 0xf900<=ord(c)<=0xfaff or 0x20000<=ord(c)<=0x323af
def has_han(t):return any(map(han,t))
def escaped_han(t):return any(han(chr(int(m,16))) for m in re.findall(r'\\u([0-9a-fA-F]{4})',t))
results=[]
for name,folder,base,scope in specs:
 root=Path(folder);diff=subprocess.check_output(['git','diff','--unified=0',base,'--',*scope],cwd=root,text=True);path='';added={};nonlatin={};escapes={}
 for line in diff.splitlines():
  if line.startswith('+++ b/'):path=line[6:]
  elif line.startswith('+') and not line.startswith('+++'):
   if has_han(line):added[path]=added.get(path,0)+1
   if escaped_han(line):escapes[path]=escapes.get(path,0)+1
   if any(unicodedata.category(c).startswith('L') and ord(c)>127 and 'LATIN' not in unicodedata.name(c,'') for c in line):nonlatin[path]=nonlatin.get(path,0)+1
 files=subprocess.check_output(['git','ls-files','--',*scope],cwd=root,text=True).splitlines();matched=[];docs=[];pycomments={};codecomments={};resources=[]
 for f in files:
  p=root/f
  if not p.is_file():continue
  try:t=p.read_text()
  except (UnicodeDecodeError,OSError):continue
  if not has_han(t) and not escaped_han(t):continue
  matched.append(f)
  if '/res/values' in f:resources.append(f)
  if p.suffix.lower()=='.md' and has_han(t):docs.append(f)
  if p.suffix=='.py':
   try:
    count=sum(tok.type==tokenize.COMMENT and has_han(tok.string) for tok in tokenize.generate_tokens(io.StringIO(t).readline))
    if count:pycomments[f]=count
   except tokenize.TokenError:pass
  if p.suffix in ('.kt','.java','.js','.mjs','.cjs'):
   # Token-oriented sampling; strings are consumed separately from comments.
   tokens=re.findall(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/',t)
   count=sum(tok.startswith(('//','/*')) and has_han(tok) for tok in tokens)
   if count:codecomments[f]=count
 results.append({'candidate':name,'baseline':base,'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'scope':scope,'introduced_actual_han_lines':added,'introduced_escaped_han_lines':escapes,'introduced_non_latin_letter_lines':nonlatin,'current_text_files_containing_han_or_escaped_han':matched,'current_markdown_files_with_han':docs,'current_resource_files_with_han':resources,'current_python_comment_tokens_with_han':pycomments,'current_code_comment_token_samples_with_han':codecomments})
args.output.write_text(json.dumps({'method':'Tracked candidate files and added diff lines within named scopes; no session histories or secrets read. Non-Latin detection does not determine the language of every Latin-script word. Current code-comment token sampling is a review aid, not a language parser.','candidates':results},indent=2)+'\n')
for r in results:print(json.dumps({k:r[k] for k in ('candidate','head','introduced_actual_han_lines','introduced_escaped_han_lines','introduced_non_latin_letter_lines','current_markdown_files_with_han','current_python_comment_tokens_with_han','current_code_comment_token_samples_with_han')},indent=2))
