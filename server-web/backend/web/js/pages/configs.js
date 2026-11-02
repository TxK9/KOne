import {t, count, systemText, listText, groupName} from '../i18n.js';
// Configs: imported sing-box configurations and route-generated configurations.

import {h, fill, when, copyText} from '../core.js';
import {post, download} from '../api.js';
import {data, refreshAll, customer as findCustomer} from '../store.js';
import {nameSeal} from '../ink.js';
import {glyph} from '../weather.js';
import {icon} from '../icons.js';
import {sheet, toast, field, input} from '../layer.js';
import {button, iconButton, empty, userPicker, tabs} from '../widgets.js';

const MAX_FILE = 512 * 1024;
let els = null, ctx = null, lastKey = '', local = {source: 'imported', renaming: null};

const ownersOf = id => data.state.users.filter(u => u.profile === id);

function people(chosenIds = []) {
  return data.state.users.map(u => {
    const summary = findCustomer(u.id);
    return {id: u.id, name: u.name, note: groupName(summary?.groupName, summary?.groupId), disabled: chosenIds.includes(u.id)};
  });
}

export function importConfig(target = null) {
  if (typeof target?.id !== 'string') target = null;
  const name = input({maxlength: '80', value: target?.name || '', placeholder: t('例如 美国节点')});
  const text = h('textarea', {class: 'input code-area', spellcheck: 'false', placeholder: t('Paste node links, PassWall UCI, Clash YAML or JSON')});
  const fileInput = h('input', {type: 'file', accept: '.json,.txt,.yaml,.yml,.conf,.uci,application/json,text/plain,application/yaml,text/yaml', hidden: true});
  const fileName = h('small', {class: 'quiet'});
  const preview = h('div', {class: 'import-notes', role: 'status', 'aria-live': 'polite'});
  let source = 'paste', checkedText = '';
  const invalidate = () => { checkedText = ''; fill(preview); };
  const readFile = async file => {
    if (!file) return;
    if (file.size > MAX_FILE) { toast(t('文件需小于 512 KB'), 'error'); return; }
    try { text.value = await file.text(); }
    catch { toast(t('The file could not be read.'), 'error'); return; }
    invalidate();
    source = 'file';
    fileName.textContent = file.name;
    if (!name.value.trim()) name.value = file.name.replace(/\.(json|txt|yaml|yml|conf|uci)$/i, '').slice(0, 80);
  };
  fileInput.addEventListener('change', () => readFile(fileInput.files?.[0]));
  text.addEventListener('input', () => { source = 'paste'; fileName.textContent = ''; invalidate(); });
  text.addEventListener('dragover', event => { event.preventDefault(); text.classList.add('is-dropping'); });
  text.addEventListener('dragleave', () => text.classList.remove('is-dropping'));
  text.addEventListener('drop', event => { event.preventDefault(); text.classList.remove('is-dropping'); readFile(event.dataTransfer.files?.[0]); });
  const picker = !target && data.state.users.length ? userPicker(people()) : null;
  sheet({
    title: t(target ? 'Add nodes' : '导入配置'), kicker: target?.name, wide: true,
    body: [
      target ? null : field(t('名称'), name),
      h('div', {class: 'field'},
        h('div', {class: 'field-label-row'}, h('label', {for: 'import-text'}, t('内容')), fileName, h('button', {type: 'button', class: 'text-button', onClick: () => fileInput.click()}, icon('upload'), t('选择文件'))),
        Object.assign(text, {id: 'import-text'}), fileInput),
      preview,
      picker ? h('div', {class: 'field'}, h('span', {class: 'field-label'}, t('同时分配给')), picker.node) : null,
    ],
    actions: [{text: t('取消')}, {text: t('Check import'), run: async () => {
      if (!text.value.trim()) throw new Error(t('请粘贴或选择配置'));
      const submitted = text.value;
      const result = await post('/api/profiles/preview', {configText: submitted, ...(target ? {profileId: target.id, expectedRevision: target.revision} : {})});
      if (text.value !== submitted) return false;
      checkedText = submitted;
      fill(preview, h('p', null, count(result.nodes, 'node') + (target ? ' · ' + t('{n} new nodes', {n: result.addedNodes}) : '') + ' · ' + result.protocols.filter(k => !['direct', 'selector', 'urltest', 'block', 'dns'].includes(k)).map(k => String(k).toUpperCase()).join(', ')),
        result.warnings?.length ? h('ul', null, result.warnings.map(w => h('li', null, systemText(w, '系统消息不可用')))) : null);
      return false;
    }}, {text: t(target ? 'Add nodes' : '导入'), primary: true, run: async api => {
      if (!name.value.trim()) throw new Error(t('请填写名称'));
      if (!text.value.trim()) throw new Error(t('请粘贴或选择配置'));
      if (text.value !== checkedText) throw new Error(t('Check the current content before importing.'));
      const userIds = picker ? [...picker.chosen] : [];
      const result = await post(target ? '/api/profiles/' + target.id + '/update' : '/api/profiles', {name: name.value.trim(), configText: text.value, source, userIds, ...(target ? {expectedRevision: target.revision, mode: 'merge'} : {})});
      await refreshAll();
      local.source = 'imported';
      if (result.warnings?.length) {
        // Imported, but the converter has something to say; keep it on screen until read.
        fill(api.content, h('div', {class: 'import-notes'}, h('p', null, glyph('wu'), t('已导入。转换时留意：')), h('ul', null, result.warnings.map(w => h('li', null, systemText(w, '系统消息不可用'))))));
        api.setActions([{text: t('知道了'), primary: true}]);
        return false;
      }
      toast(target ? t('Shared configuration updated') : userIds.length ? t('已导入并分配') : t('已导入'));
    }}],
  });
}

function subscription(p) {
  if (p.subscriptionEnabled) {
    sheet({title: t('Revoke subscription'), kicker: p.name,
      body: h('p', null, t('Connected apps keep their account access. This only disables the shared subscription link.')),
      actions: [{text: t('取消')}, {text: t('Revoke subscription'), danger: true, run: async () => {
        await post('/api/profiles/' + p.id + '/subscription/revoke');
        await refreshAll();
        toast(t('Subscription revoked'));
      }}]});
    return;
  }
  sheet({title: t('Create subscription'), kicker: p.name,
    body: h('p', null, t('Keep subscription links private.')),
    actions: [{text: t('取消')}, {text: t('Create subscription'), primary: true, run: async api => {
      const result = await post('/api/profiles/' + p.id + '/subscription');
      const link = (label, url) => {
        const value = input({value: url, readonly: true, 'aria-label': label});
        return field(label, h('div', {class: 'field-label-row'}, value, button(t('复制'), {quiet: true, onClick: async () => {
          const copied = await copyText(url);
          if (!copied) value.select();
          toast(t(copied ? '已复制' : '请手动选择复制'));
        }})));
      };
      fill(api.content, h('p', {class: 'quiet'}, t('Shown once. The link stays current when this configuration changes.')),
        link(t('sing-box subscription'), result.singBoxUrl),
        result.shareLinksUrl ? link(t('Share-link subscription'), result.shareLinksUrl) : null,
        result.linksError ? h('p', {class: 'quiet'}, systemText(result.linksError, '系统消息不可用')) : null);
      api.setActions([{text: t('关闭'), primary: true}]);
      await refreshAll();
      return false;
    }}]});
}

function assign(p) {
  if (!data.state.users.length) { toast(t('先在客户页添加客户')); ctx.navigate('/customers'); return; }
  const owners = ownersOf(p.id).map(u => u.id);
  const picker = userPicker(people(owners), {chosen: new Set(owners)});
  sheet({
    title: t('分配配置'), kicker: p.name,
    body: [picker.node],
    actions: [{text: t('取消')}, {text: t('分配'), primary: true, run: async () => {
      const ids = [...picker.chosen].filter(id => !owners.includes(id));
      if (!ids.length) throw new Error(t('请选择客户'));
      await post('/api/assignments', {profileId: p.id, userIds: ids});
      await refreshAll();
      toast(t('已分配'));
    }}],
  });
}

function renameInline(p, title) {
  const field_ = h('input', {class: 'input rename-input', value: p.name, maxlength: '80', 'aria-label': t('新名称')});
  const done = async keep => {
    const value = field_.value.trim();
    local.renaming = null;
    if (keep && value && value !== p.name) {
      try {
        await post('/api/profiles/' + p.id + '/rename', {name: value});
        await refreshAll();
        toast(t('已改名'));
        return;
      } catch (e) { toast(e.message, 'error'); }
    }
    lastKey = '';
    render();
  };
  field_.addEventListener('keydown', event => {
    if (event.key === 'Enter') { event.preventDefault(); done(true); }
    if (event.key === 'Escape') { event.preventDefault(); done(false); }
  });
  field_.addEventListener('blur', () => { if (local.renaming === p.id) done(true); });
  title.replaceWith(h('div', {class: 'rename'}, field_));
  local.renaming = p.id;
  field_.focus();
  field_.select();
}

function card(p) {
  const owners = ownersOf(p.id);
  const generated = p.source === '线路生成';
  const title = h('h2', {class: 'config-name'}, p.name);
  const kinds = (p.protocols || []).filter(k => !['direct', 'block', 'dns', 'selector', 'urltest'].includes(String(k).toLowerCase()));
  return h('article', {class: 'config-card'},
    h('div', {class: 'config-scroll', 'aria-hidden': 'true'}, h('span', null, p.version)),
    h('div', {class: 'config-main'},
      h('header', {class: 'config-head'}, title,
        h('div', {class: 'config-actions'},
          iconButton('pen', t('改名'), event => renameInline(p, event.currentTarget.closest('.config-card').querySelector('.config-name'))),
          iconButton('download', t('下载'), () => download('/api/profiles/' + p.id + '/config', p.name.replace(/[\/\\\u0000-\u001f]/g, '_') + '.json').catch(e => toast(e.message, 'error'))))),
      h('div', {class: 'config-facts'},
        h('span', null, count(p.nodes, 'node')),
        p.ruleCount != null && !generated ? h('span', null, count(p.ruleCount, 'rule')) : null,
        p.dnsCount && !generated ? h('span', null, count(p.dnsCount, 'dns')) : null,
        kinds.length ? h('span', {class: 'config-kinds'}, kinds.slice(0, 4).map(k => h('span', {class: 'pill'}, String(k).toUpperCase()))) : null),
      h('footer', {class: 'config-foot'},
        !generated ? button(t('Add nodes'), {quiet: true, iconName: 'plus', onClick: () => importConfig(p)}) : null,
        !generated ? button(t(p.subscriptionEnabled ? 'Revoke subscription' : 'Create subscription'), {quiet: true, iconName: 'key', onClick: () => subscription(p)}) : null,
        !generated ? button(t('Export nodes'), {quiet: true, iconName: 'download', onClick: () => download('/api/profiles/' + p.id + '/portable', p.name.replace(/[\/\\\u0000-\u001f]/g, '_') + '-nodes.json').catch(e => toast(e.message, 'error'))}) : null,
        owners.length ? h('span', {class: 'seal-stack', title: listText(owners.map(u => u.name))}, owners.slice(0, 5).map(u => nameSeal(u.name, 's')), owners.length > 5 ? h('small', null, '+' + (owners.length - 5)) : null)
          : h('small', {class: 'quiet'}, t('还没人用')),
        h('small', {class: 'quiet'}, (generated ? t('线路生成 · ') : p.source === 'file' ? t('文件导入 · ') : '') + when(p.updated)),
        !generated ? button(t('分配'), {quiet: true, iconName: 'customers', onClick: () => assign(p)}) : null)));
}

function render() {
  if (!els) return;
  const all = data.state.profiles;
  const imported = all.filter(p => p.source !== '线路生成');
  const generated = all.filter(p => p.source === '线路生成');
  const key = JSON.stringify([all, data.state.users, local.source, data.loaded.state]);
  if (key === lastKey || local.renaming) return;
  lastKey = key;
  if (!generated.length) local.source = 'imported';
  const list = local.source === 'imported' ? imported : generated;
  fill(els.tabs, generated.length ? tabs([{id: 'imported', text: t('导入的'), count: imported.length}, {id: 'generated', text: t('线路生成'), count: generated.length}], local.source, id => { local.source = id; render(); }, t('配置来源')) : null);
  fill(els.list, list.length ? list.map(card)
    : data.loaded.state ? empty('configs', t('还没有配置'), button(t('导入配置'), {primary: true, iconName: 'plus', onClick: importConfig})) : null);
}

export default {
  id: 'configs',
  hasUnsavedChanges() { return Boolean(local.renaming); },
  title: t('配置'),
  icon: 'configs',
  mount(view, params, context) {
    ctx = context;
    lastKey = '';
    local.renaming = null;
    els = {tabs: h('div', {class: 'page-tabs'}), list: h('div', {class: 'config-grid'})};
    view.append(els.tabs, els.list);
    ctx.setActions([button(t('导入配置'), {primary: true, iconName: 'plus', onClick: importConfig})]);
    render();
  },
  update() { render(); },
  unmount() { els = null; },
};
