(() => {
  'use strict';
  const host=location.hostname.toLowerCase();
  const local=['127.0.0.1','localhost','[::1]'].includes(host);
  const preview=local?new URLSearchParams(location.search).get('site'):null;
  const product=preview==='kone'||(!preview&&(host==='kone.qingwanli.com'||host.startsWith('kone-')));
  document.documentElement.dataset.site=product?'product':'brand';
  document.getElementById('site-canonical').href=product?'https://kone.qingwanli.com/':'https://qingwanli.com/';
})();
