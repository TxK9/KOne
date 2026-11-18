const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const {babelParse,traverse}=require('./browser-tools.cjs').babelTools();
(async()=>{
 const base=path.resolve(__dirname,'..'), {EN}=await import('../web/js/i18n.js');
 const files=['web/app.js',...fs.readdirSync(base+'/web/js',{recursive:true}).filter(f=>f.endsWith('.js')).map(f=>'web/js/'+f)].filter(f=>f!=='web/js/i18n.js');
 let calls=0,raw=[];
 for(const file of files){
  const source=fs.readFileSync(base+'/'+file,'utf8');
  traverse(babelParse(source,file),{StringLiteral(p){
   const n=p.node,parent=p.parent;
   if(parent.type==='CallExpression'&&parent.callee.name==='t'&&parent.arguments[0]===n){assert.ok(Object.hasOwn(EN,n.value),file+': '+n.value);calls++;return;}
   if(/[\u3400-\u9fff]/u.test(n.value)){
    if(file==='web/app.js'&&n.value==='简体中文')return;
    if(n.value==='线路生成'&&parent.type==='BinaryExpression')return;
    if(file==='web/js/pages/records.js'&&n.value==='完成'&&parent.type==='BinaryExpression')return;
    // A fallback semantic key (not display text) used by systemText.
    if(['系统记录','系统消息不可用','操作没有完成'].includes(n.value)&&parent.type==='CallExpression'&&parent.arguments[1]===n)return;
    raw.push(file+': '+n.value);
   }
  }});
 }
 assert.deepEqual(raw,[]);console.log('PASS '+calls+' explicit localized call sites; '+Object.keys(EN).length+' English catalog entries; no unwrapped Chinese UI literals');
})();
