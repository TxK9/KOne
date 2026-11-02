import {t, locale, setLocale, number} from './js/i18n.js';
// KOne management center: login, page frame, theme and navigation.

import {h, fill, prefs} from './js/core.js';
import * as api from './js/api.js';
import * as store from './js/store.js';
import {icon, mark} from './js/icons.js';
import {scenery} from './js/ink.js';
import {toast, close as closeSheet, moreMenu, sheet, twoStep} from './js/layer.js';
import overview from './js/pages/overview.js';
import servers from './js/pages/servers.js';
import routes from './js/pages/routes.js';
import customers from './js/pages/customers.js';
import configs from './js/pages/configs.js';
import nodes from './js/pages/nodes.js';
import records from './js/pages/records.js';
import feedback from './js/pages/feedback.js';
import {cultureNote} from './js/widgets.js';
import {createOwnerDownload} from './js/owner-download.js';

const PAGES = [overview, servers, nodes, routes, customers, configs, records, feedback];
const root = document.getElementById('kone');
document.documentElement.lang = locale;

function languageControl(className = '') {
  const control = h('select', {class: 'language-select', 'aria-label': t('选择语言'), title: t('语言')},
    h('option', {value: 'en', lang: 'en', selected: locale === 'en'}, 'English'),
    h('option', {value: 'zh-CN', lang: 'zh-CN', selected: locale === 'zh-CN'}, '简体中文'));
  control.addEventListener('change', () => {
    const target = control.value;
    control.value = locale;
    const change = () => { try { setLocale(target); } catch (error) { toast(error.message, 'error'); } };
    if (current?.hasUnsavedChanges?.()) {
      sheet({title: t('切换语言'), compact: true, body: h('p', null, t('未保存的修改会丢失。')),
        actions: [{text: t('取消')}, {text: t('切换语言'), primary: true, run: change}]});
    } else change();
  });
  return h('div', {class: 'language-control ' + className}, icon('globe'), control);
}

// Theme follows the system unless explicitly selected.

const THEMES = [['system', t('跟随系统')], ['light', t('浅色')], ['dark', t('深色')]];
const systemDark = window.matchMedia('(prefers-color-scheme: dark)');
const themeChoice = () => prefs.get('theme', 'system');
const isDark = () => (themeChoice() === 'system' ? systemDark.matches : themeChoice() === 'dark');

function applyTheme() {
  const choice = themeChoice();
  if (choice === 'system') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = choice;
  document.querySelectorAll('[data-theme-icon]').forEach(el => fill(el, icon(isDark() ? 'moon' : 'sun')));
}

const themeItems = (prefix = '') => THEMES.map(([id, text]) => ({
  text: prefix + text + (themeChoice() === id ? '  ✓' : ''),
  run: () => { prefs.set('theme', id === 'system' ? null : id); applyTheme(); },
}));

function themeMenu(className) {
  const menu = moreMenu(() => themeItems(), t('外观'));
  menu.classList.add(className);
  const trigger = menu.querySelector('button');
  trigger.className = 'rail-item';
  trigger.replaceChildren(h('span', {'data-theme-icon': ''}, icon(isDark() ? 'moon' : 'sun')), h('span', {class: 'rail-text'}, t('外观')));
  return menu;
}

if (systemDark.addEventListener) systemDark.addEventListener('change', applyTheme);
else systemDark.addListener(applyTheme);
applyTheme();

// ---- Login -------------------------------------------------------------------------------------

function showLogin(configured) {
  authEpoch++;
  store.stopPolling();
  unsubscribe?.();
  unsubscribe = null;
  current?.unmount?.();
  current = null;
  frame = null;
  PAGES.forEach(page => page.reset?.());
  store.reset();
  closeSheet(true);
  const password = h('input', {type: 'password', class: 'login-input', id: 'login-password', autocomplete: configured ? 'current-password' : 'new-password', required: true, maxlength: '256'});
  const confirm = configured ? null : h('input', {type: 'password', class: 'login-input', id: 'login-confirm', autocomplete: 'new-password', maxlength: '256'});
  const error = h('p', {class: 'login-error', role: 'alert'});
  const submit = h('button', {type: 'submit', class: 'login-go'}, configured ? t('进入') : t('开始使用'));
  const form = h('form', {class: 'login-form', novalidate: true},
    h('div', {class: 'login-brand'}, mark('login-mark'), h('h1', null, 'KOne'), h('p', null, configured ? t('管理中心') : t('设置管理中心的登录密码'))),
    h('div', {class: 'login-fields'},
      h('div', {class: 'login-field'}, h('label', {for: 'login-password'}, t('密码')), password),
      confirm ? h('div', {class: 'login-field'}, h('label', {for: 'login-confirm'}, t('再输一次')), confirm) : null,
      error, submit));
  form.addEventListener('submit', async event => {
    event.preventDefault();
    error.textContent = '';
    if (!password.value) { error.textContent = t('请输入密码'); password.focus(); return; }
    if (!configured) {
      if (password.value.length < 8) { error.textContent = t('密码至少 8 个字符'); return; }
      if (password.value !== confirm.value) { error.textContent = t('两次输入不一样'); confirm.focus(); return; }
    }
    submit.disabled = true;
    try {
      const result = await api.post(configured ? '/api/auth/login' : '/api/auth/setup', {password: password.value});
      api.setCsrf(result.csrfToken);
      password.value = '';
      if (confirm) confirm.value = '';
      showApp();
    } catch (e) {
      error.textContent = e.message;
      password.select();
    } finally {
      submit.disabled = false;
    }
  });
  fill(root, h('main', {class: 'login'}, scenery('login-scenery'), h('div', {class: 'login-veil', 'aria-hidden': 'true'}), form, languageControl('login-language'), cultureNote('login-quote')));
  document.title = t('KOne 管理中心');
  requestAnimationFrame(() => password.focus());
}

// ---- The frame ---------------------------------------------------------------------------------

let current = null, view = null, titleEl = null, actionsEl = null, offlineEl = null, frame = null;

function navItems(className) {
  return PAGES.map(page => h('a', {class: className, href: '#/' + page.id, 'data-page': page.id, 'aria-label': page.title}, icon(page.icon), h('span', {class: 'rail-text'}, page.id === 'configs' ? t('配置导航') : page.title)));
}

function logout() {
  api.post('/api/auth/logout', {}).catch(() => {}).finally(() => { api.setCsrf(null); showLogin(true); });
}

async function ownerDownload() {
  try {
    const data = await api.get('/api/owner-release');
    const release = data.release;
    const body = h('div', null,
      h('p', null, release.state === 'ready' ? `KOne ${release.version} · ${release.versionCode}` : t('安装包准备中')),
      h('p', {class: 'muted'}, window.koneOwnerUpdates ? t('后续更新可在 App 的应用版本中查看。') : t('首次安装后，在 App 管理中心登录一次，即可接收后续更新。')));
    const progress = h('p', {class: 'muted', role: 'status', 'aria-live': 'polite', hidden: true});
    body.append(progress);
    for (const device of data.devices) {
      const remove = h('button', {type: 'button', class: 'btn'}, t('移除'));
      const row = h('div', {class: 'owner-update-device'}, h('span', null, device.name), remove);
      twoStep(remove, {ask: t('确认移除'), run: async () => { await api.post('/api/owner-update/revoke', {deviceId: device.id}); row.remove(); }});
      body.append(row);
    }
    let controller = null, transfer = null, ready = false;
    const start = () => {
      transfer ||= createOwnerDownload(release);
      controller = new AbortController();
      const active = controller;
      progress.hidden = false; progress.textContent = t('正在连接');
      dialog.setActions([{text: t('取消下载'), run: () => { active.abort(); return false; }}]);
      transfer.run(active.signal, state => {
        progress.textContent = state.phase === 'verify' ? t('正在校验安装包') : state.phase === 'retry' ? t('连接中断，正在续传') :
          `${number(Math.floor(state.received / state.total * 100))}% · ${number(state.received / 1048576, {minimumFractionDigits: 1, maximumFractionDigits: 1})} / ${number(state.total / 1048576, {minimumFractionDigits: 1, maximumFractionDigits: 1})} MB`;
      }).then(blob => {
        if (active.signal.aborted) return;
        ready = true;
        const address = URL.createObjectURL(blob);
        const link = document.createElement('a'); link.href = address; link.download = release.url.split('/').pop();
        document.body.append(link); link.click(); link.remove();
        setTimeout(() => URL.revokeObjectURL(address), 60000);
        progress.textContent = t('安装包已就绪，请在浏览器下载记录中打开');
      }).catch(error => {
        if (dialog.panel.isConnected) {
          progress.textContent = active.signal.aborted ? t('下载已暂停') : t('下载未完成');
          if (!active.signal.aborted) dialog.setError(error.message);
        }
      }).finally(() => {
        controller = null;
        dialog.setActions([{text: ready ? t('保存安装包') : t('继续下载'), primary: true, run: () => { start(); return false; }}]);
      });
    };
    const dialog = sheet({title: t('自用版'), compact: true, body,
      actions: release.state === 'ready' ? [{text: window.koneOwnerUpdates ? t('检查更新') : t('下载自用版'), primary: true, run: async () => {
        if (window.koneOwnerUpdates) window.koneOwnerUpdates.postMessage(JSON.stringify({action: 'check'}));
        else { start(); return false; }
      }}] : []});
    const close = dialog.close;
    dialog.close = force => { controller?.abort(); close(force); };
  } catch (e) { toast(e.message, 'error'); }
}

function buildFrame() {
  titleEl = h('h1', {class: 'top-title'});
  actionsEl = h('div', {class: 'top-actions'});
  offlineEl = h('span', {class: 'offline', role: 'status', hidden: true}, t('后台暂时连不上'));
  view = h('div', {class: 'view'});
  const phoneMenu = moreMenu(() => [...themeItems(t('外观 · ')), {text: t('自用版'), run: ownerDownload}, {text: t('退出登录'), run: logout}]);
  phoneMenu.classList.add('phone-only');
  frame = h('div', {class: 'app'},
    h('nav', {class: 'rail', 'aria-label': t('导航')},
      h('a', {class: 'rail-mark', href: '#/overview', 'aria-label': 'KOne'}, mark()),
      h('div', {class: 'rail-pages'}, navItems('rail-item')),
      h('div', {class: 'rail-foot'},
        themeMenu('rail-theme'),
        h('button', {type: 'button', class: 'rail-item', onClick: ownerDownload}, icon('download'), h('span', {class: 'rail-text'}, t('自用版'))),
        h('button', {type: 'button', class: 'rail-item', onClick: logout}, icon('logout'), h('span', {class: 'rail-text'}, t('退出'))))),
    h('main', {class: 'main'},
      h('header', {class: 'top'}, titleEl, offlineEl, actionsEl, languageControl(), phoneMenu),
      view));
  fill(root, frame);
  applyTheme();
}

function parseHash() {
  const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(part => { try { return decodeURIComponent(part); } catch { return ''; } });
  const page = PAGES.find(p => p.id === parts[0]) || overview;
  return {page, params: parts.slice(1)};
}

export function navigate(path) {
  if (location.hash !== '#' + path) location.hash = path;
  else route();
}

const ctx = {
  navigate,
  setActions(nodes) { fill(actionsEl, nodes || []); },
  toast,
};

function route() {
  if (!frame) return;
  const {page, params} = parseHash();
  frame.querySelectorAll('[data-page]').forEach(el => {
    if (el.dataset.page === page.id) el.setAttribute('aria-current', 'page'); else el.removeAttribute('aria-current');
  });
  frame.dataset.page = page.id;
  if (window.matchMedia('(max-width: 760px)').matches) frame.querySelector('.rail-pages [aria-current="page"]')?.scrollIntoView({block: 'nearest', inline: 'nearest'});
  titleEl.textContent = page.title;
  document.title = page.title + ' · KOne';
  if (current !== page) {
    current?.unmount?.();
    current = page;
    ctx.setActions([]);
    view.replaceChildren();
    view.scrollTop = 0;
    window.scrollTo(0, 0);
    page.mount(view, params, ctx);
  } else {
    page.update(params);
  }
}

function onData() {
  if (!current) return;
  offlineEl.hidden = !store.data.stale;
  current.update(parseHash().params);
}

let unsubscribe = null, authEpoch = 0;

async function showApp() {
  const epoch = ++authEpoch;
  buildFrame();
  current = null;
  unsubscribe?.();
  unsubscribe = store.subscribe(onData);
  route();
  await store.refreshAll();
  if (epoch === authEpoch && frame) store.startPolling();
}

window.addEventListener('hashchange', route);

api.on('unauthorized', () => {
  showLogin(true);
});

async function boot() {
  try {
    const status = await api.status();
    if (status.authenticated) showApp();
    else showLogin(status.configured);
  } catch {
    fill(root, h('main', {class: 'boot-error'}, mark('login-mark'), h('p', null, t('暂时连不上管理中心')), h('button', {type: 'button', class: 'button', onClick: boot}, t('重试')), languageControl()));
  }
}

boot();
