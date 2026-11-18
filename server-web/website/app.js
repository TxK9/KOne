(() => {
'use strict';
const product=document.documentElement.dataset.site==='product';
document.querySelectorAll('[data-brand-name]').forEach(n=>n.textContent=product?'KOne':'qingwanli');
document.querySelectorAll('[data-site-address]').forEach(n=>n.textContent=product?'kone.qingwanli.com':'qingwanli.com');
const action=document.getElementById('hero-action');
function sync(){action.href=product?'#download':'#brand-products';action.firstElementChild.textContent=KOneLocale.t(product?'getKone':'applications');const skip=document.querySelector('.skip');skip.href=action.href;skip.textContent=KOneLocale.t(product?'skip':'applications');}
sync();window.addEventListener('kone:locale',sync);
const returnFocus=new WeakMap();
function show(dialog){returnFocus.set(dialog,document.activeElement);if(typeof dialog.showModal==='function')dialog.showModal();else{dialog.setAttribute('open','');dialog.classList.add('fallback-modal');dialog.setAttribute('aria-modal','true');document.body.classList.add('fallback-open');dialog.querySelector('button,a,select')?.focus();}}
function close(dialog){if(typeof dialog.close==='function')dialog.close();else{dialog.removeAttribute('open');dialog.classList.remove('fallback-modal');document.body.classList.remove('fallback-open');dialog.dispatchEvent(new Event('close'));returnFocus.get(dialog)?.focus();}}
const intro=document.getElementById('intro');
function openIntro(){show(intro);document.body.classList.add('modal-opening');document.getElementById('enter-mountains').focus();}
intro.addEventListener('close',()=>{document.body.classList.remove('modal-opening');try{sessionStorage.setItem('kone.intro.seen','1');}catch(_){};});
document.getElementById('replay').addEventListener('click',openIntro);
document.getElementById('close-intro').addEventListener('click',()=>close(intro));
document.getElementById('enter-mountains').addEventListener('click',()=>close(intro));
document.querySelectorAll('.skip,.intro-skip').forEach(n=>n.addEventListener('click',()=>{if(intro.hasAttribute('open'))close(intro);}));
// Reading is never interrupted by a timer or an automatic change of scene.
let seen=false;try{seen=sessionStorage.getItem('kone.intro.seen')==='1';}catch(_){}
if(!seen&&!location.hash)openIntro();
for(const [button,id]of [['installation-help','help-dialog'],['source-info','source-dialog']])document.getElementById(button).addEventListener('click',()=>show(document.getElementById(id)));
document.querySelectorAll('[data-close]').forEach(n=>n.addEventListener('click',()=>close(document.getElementById(n.dataset.close))));
document.querySelectorAll('dialog').forEach(dialog=>{
 dialog.addEventListener('click',event=>{if(event.target!==dialog||dialog===intro)return;const r=dialog.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)close(dialog);});
 dialog.addEventListener('keydown',event=>{if(!dialog.classList.contains('fallback-modal'))return;if(event.key==='Escape'){event.preventDefault();close(dialog);}if(event.key==='Tab'){const controls=[...dialog.querySelectorAll('button,a[href],select')];const first=controls[0],last=controls[controls.length-1];if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}}});
});
})();
