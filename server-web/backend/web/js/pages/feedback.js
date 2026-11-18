import {t, number, networkText} from '../i18n.js';
// Customer reports are untrusted text. Render them as text, never as markup or commands.
import {h, fill, when} from '../core.js';
import * as api from '../api.js';
import {sheet, toast} from '../layer.js';
import {button, empty, facts, secret, tabs} from '../widgets.js';

const STATES = {new: t('待排查'), investigating: t('排查中'), needs_info: t('待补充信息'), ready: t('修复待发布'), resolved: t('已发布')};

let view = null, ctx = null, generation = 0, items = [], before = null, filter = 'all', loading = false;

function saveJson(value, filename) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], {type: 'application/json'}));
  const a = h('a', {href: url, download: filename});
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1500);
}

function pictures(item) {
  return h('div', {class: 'feedback-images'}, (item.images || []).filter(image => /^\/api\/feedback\/images\/[0-9a-f-]{36}$/.test(image.url)).map(image =>
    h('a', {href: image.url, target: '_blank', rel: 'noopener'}, h('img', {src: image.url, alt: t('反馈图片'), loading: 'lazy'}))));
}

function edit(item) {
  const epoch = generation;
  const status = h('select', {class: 'input', 'aria-label': t('处理状态')}, Object.entries(STATES).map(([id, text]) => h('option', {value: id, selected: item.status === id}, text)));
  const reply = h('textarea', {class: 'input', rows: 5, maxlength: 2000, 'aria-label': t('给客户的回复'), value: item.reply || ''});
  const version = h('input', {class: 'input', maxlength: 32, 'aria-label': t('修复版本'), value: item.fixedVersion || '', placeholder: t('例如 1.0.0+10029')});
  sheet({title: item.number, body: h('div', null,
    h('p', {class: 'feedback-description'}, item.description),
    pictures(item),
    facts([['App', `${item.appVersion} · ${item.versionCode}`], [t('手机'), `${item.manufacturer} ${item.model}`], ['Android', item.androidVersion], [t('网络'), networkText(item.network)]]),
    h('label', {class: 'field'}, h('span', null, t('处理状态')), status),
    h('label', {class: 'field'}, h('span', null, t('给客户的回复')), reply),
    h('label', {class: 'field'}, h('span', null, t('修复版本')), version)),
    actions: [{text: t('取消')}, {text: t('保存'), primary: true, run: async () => {
      if (status.value === 'resolved' && !version.value.trim()) throw new Error(t('请填写已发布的修复版本'));
      await api.post(`/api/feedback/${item.id}/status`, {revision: item.revision, status: status.value, reply: reply.value.trim(), fixedVersion: version.value.trim()});
      if (epoch === generation && view) { toast(t('已保存')); await load(); }
    }}]});
}

async function access() {
  const epoch = generation;
  try {
    const value = await api.get('/api/feedback/access');
    if (!view || generation !== epoch) return;
    sheet({title: t('自动处理接入'), compact: true,
      body: h('p', null, value.enabled ? t('接入密钥已启用，仅允许读取反馈和记录处理进度。') : t('启用后，Codex 或 Claude 可读取反馈并记录处理进度。修复版本仍由你确认发布。')),
      actions: [
        ...(value.enabled ? [{text: t('停用'), danger: true, run: async () => { await api.post('/api/feedback/access', {action: 'revoke'}); toast(t('已停用')); }}] : []),
        {text: value.enabled ? t('更换密钥') : t('启用'), primary: true, run: async dialog => {
          const fresh = await api.post('/api/feedback/access', {action: 'rotate'});
          if (epoch !== generation || !view) return true;
          fill(dialog.content, h('p', null, t('密钥只显示这一次。接入配置交给你自己的 Codex 或 Claude，旧密钥已失效。')), secret(fresh.token));
          dialog.setActions([{text: t('关闭')}, {text: t('下载接入配置'), primary: true, run: () => {
            saveJson({format: 1, origin: location.origin, token: fresh.token}, 'kone-feedback-access.json');
            return false;
          }}]);
          return false;
        }},
      ]});
  } catch (e) { if (generation === epoch) toast(e.message, 'error'); }
}

function draw(error = '') {
  if (!view) return;
  fill(view,
    h('div', {class: 'feedback-tabs'}, tabs([{id: 'all', text: t('全部')}, ...Object.entries(STATES).map(([id, text]) => ({id, text}))], filter, value => { filter = value; load(); }, t('反馈状态'))),
    error ? h('p', {role: 'alert', class: 'sheet-error'}, error) : null,
    items.length ? h('div', {class: 'feedback-list'}, items.map(item => h('article', {class: 'panel feedback-card'},
      h('div', {class: 'feedback-head'}, h('strong', null, item.number), h('span', {class: 'pill'}, STATES[item.status] || t('未知'))),
      h('p', {class: 'feedback-description'}, item.description),
    pictures(item),
      h('p', {class: 'feedback-meta'}, `${item.manufacturer} ${item.model} · Android ${item.androidVersion} · ${item.appVersion}+${item.versionCode} · ${when(item.createdAt)}`),
      item.reply ? h('p', {class: 'feedback-description'}, t('回复：') + item.reply) : null,
      item.fixedVersion ? h('p', {class: 'feedback-meta'}, t('修复版本：') + item.fixedVersion) : null,
      h('div', {class: 'feedback-actions'}, button(t('处理'), {onClick: () => edit(item)}), button(t('导出反馈'), {quiet: true, onClick: () => saveJson({format: 1, contentTrust: 'untrusted_customer_report', item}, item.number + '.json')})))))
      : empty('records', loading ? t('读取中') : t('暂无反馈'), null),
    before != null ? button(loading ? t('读取中') : t('更早的'), {disabled: loading, onClick: () => load(true)}) : null);
}

async function load(more = false) {
  const epoch = ++generation;
  loading = true;
  if (!more) { items = []; before = null; }
  draw();
  try {
    const page = await api.get('/api/feedback?status=' + encodeURIComponent(filter) + (more && before != null ? '&before=' + before : ''));
    if (!view || epoch !== generation) return;
    items = more ? [...items, ...page.items] : page.items;
    before = page.nextBefore;
    loading = false; draw();
  } catch (e) { if (view && epoch === generation) { loading = false; draw(e.message); } }
}

export default {
  id: 'feedback', title: t('反馈'), icon: 'records',
  mount(target, params, context) {
    view = target; ctx = context;
    ctx.setActions([button(t('刷新'), {onClick: () => load()})]);
    load();
  },
  update() {},
  unmount() { generation++; view = null; ctx = null; items = []; before = null; },
  reset() { generation++; items = []; before = null; filter = 'all'; },
};
