// Runs before the page paints, so a chosen light or dark look does not flash the other one first.
try {
  const theme = localStorage.getItem('kone.theme');
  if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
} catch (error) { /* storage unavailable: follow the system */ }

try {
  const saved = localStorage.getItem('kone.admin.locale');
  const locale = ['en', 'zh-CN'].includes(saved) ? saved : (/^zh(?:-|$)/i.test(navigator.language) ? 'zh-CN' : 'en');
  document.documentElement.lang = locale;
  document.title = locale === 'zh-CN' ? 'KOne 管理中心' : 'KOne Management';
} catch {
  document.documentElement.lang = /^zh(?:-|$)/i.test(navigator.language) ? 'zh-CN' : 'en';
  document.title = document.documentElement.lang === 'zh-CN' ? 'KOne 管理中心' : 'KOne Management';
}
