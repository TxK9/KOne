import {t, number, count, eventText, ackText, locale, groupName} from '../i18n.js';
// Customers: groups, configurations, keys, devices and usage.

import {h, fill, matches, when, ago, bytes} from '../core.js';
import {get, post, download} from '../api.js';
import {data, refreshAll, refreshCustomers, profile as findProfile, group as findGroup, customer as findCustomer, routeOf} from '../store.js';
import {nameSeal} from '../ink.js';
import {seal, weatherTag, weatherLine, customerWeather, deviceWeather} from '../weather.js';
import {icon} from '../icons.js';
import {sheet, toast, twoStep, field, input, select} from '../layer.js';
import {button, iconButton, empty, tabs, facts, meter, secret, countdown, qr} from '../widgets.js';

const DEFAULT_GROUP = '00000000-0000-4000-8000-000000000001';
const GIB = 1073741824;
const NETWORK = {wifi: 'Wi-Fi', cellular: t('移动网络'), ethernet: t('有线'), offline: t('离线'), unknown: t('未知')};
const TABS = [['config', t('配置')], ['devices', t('设备')], ['usage', t('流量')], ['events', t('诊断')]];

let els = null, ctx = null, lastList = '';
let detailGeneration = 0;
const local = {group: 'all', query: '', selected: null, tab: 'config', detail: null, detailFor: null, loading: false, error: '', key: null, dirty: false};
const slotKeys = {};

const quota = value => (value ? bytes(value) : t('不限'));
// What, in the list's summary, means the open detail is out of date.
const stampOf = u => (u ? JSON.stringify([u.revision, u.deviceCount, u.accessState, u.revoked, u.paused, u.profileId, u.groupId, u.usage.updated, u.nodeControl, u.receivedCount, u.appliedCount, u.blockClaude]) : '');

// ---- Sheets ------------------------------------------------------------------------------------

function groupOptions(selected, withNone = true) {
  const groups = data.customers.groups.length ? data.customers.groups : [{id: DEFAULT_GROUP, name: t('默认分组')}];
  return [...groups.map(g => ({value: g.id, text: groupName(g.name, g.id), note: g.profileId ? findProfile(g.profileId)?.name : t('未设默认配置')})), ...(withNone ? [{value: '', text: t('不分组'), note: t('单独分配配置')}] : [])];
}

/** Configs a group may use as its template: imported ones, not a person's own route config. */
const templateProfiles = () => data.state.profiles.filter(p => p.source !== '线路生成');

export function addCustomer() {
  const name = input({maxlength: '80', placeholder: t('输入名字或备注'), class: 'input customer-name-input'});
  const groupSelect = select(groupOptions(), local.group !== 'all' ? local.group : DEFAULT_GROUP);
  const avatar = h('span', {class: 'customer-preview-seal'}, nameSeal(t('客'), 'l'));
  const previewName = h('strong', null, t('新客户'));
  const previewNote = h('span', null);
  const serviceChoices = h('div', {class: 'service-choices', role: 'radiogroup', 'aria-label': t('服务方式')});
  let service = 'nodes';
  const sync = () => {
    fill(avatar, nameSeal(name.value.trim() || t('客'), 'l'));
    previewName.textContent = name.value.trim() || t('新客户');
    const g = findGroup(groupSelect.value), p = g?.profileId && findProfile(g.profileId);
    previewNote.textContent = service === 'app' ? t('仅开通 App') : p ? t('跟随 ') + groupName(g.name, g.id) + ' · ' + p.name : t('创建后分配配置');
    serviceChoices.querySelectorAll('[role="radio"]').forEach(el => { el.setAttribute('aria-checked', String(el.dataset.value === service)); });
  };
  [['nodes', t('跟随分组'), t('使用分组的配置与额度'), 'configs'], ['app', t('仅开通 App'), t('不下发节点'), 'phone']].forEach(([id, title, note, art]) => {
    serviceChoices.append(h('button', {type: 'button', class: 'service-choice', role: 'radio', 'aria-checked': String(id === service), 'data-value': id,
      onClick: () => { service = id; sync(); }}, icon(art), h('span', null, h('strong', null, title), h('small', null, note)), icon('check')));
  });
  serviceChoices.addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
    event.preventDefault(); service = service === 'nodes' ? 'app' : 'nodes'; sync();
    serviceChoices.querySelector('[data-value="' + service + '"]').focus();
  });
  name.addEventListener('input', sync); groupSelect.addEventListener('change', sync); sync();
  const form = h('form', {class: 'customer-create', novalidate: true},
    h('div', {class: 'customer-preview'}, avatar, h('div', null, previewName, previewNote)),
    field(t('名字'), name), field(t('分组'), groupSelect),
    h('div', {class: 'field'}, h('span', {class: 'field-label'}, t('服务方式')), serviceChoices));
  const dialog = sheet({
    title: t('新增客户'), compact: true,
    body: form,
    actions: [{text: t('取消')}, {text: t('创建客户'), primary: true, run: async () => {
      if (!name.value.trim()) { name.setAttribute('aria-invalid', 'true'); throw new Error(t('请填写客户名字')); }
      name.removeAttribute('aria-invalid');
      const result = await post('/api/users', {name: name.value.trim(), groupId: groupSelect.value || null, appOnly: service === 'app'});
      await refreshAll();
      toast(t('已添加'));
      ctx?.navigate('/customers/' + result.id);
    }}],
  });
  form.addEventListener('submit', event => { event.preventDefault(); dialog.panel.querySelector('.button-primary')?.click(); });
}

function deleteCustomer(d) {
  sheet({title: t('删除客户'), compact: true,
    body: [h('div', {class: 'delete-person'}, nameSeal(d.name, 'm'), h('strong', null, d.name)),
      h('p', {class: 'delete-copy'}, t('客户、设备和领取码将被删除，无法恢复。')),
      h('p', {class: 'note'}, d.nodeControl?.nodes?.length || routeOf(d.id)
        ? t('托管节点会排队移除权限，执行结果可在部署记录查看。')
        : d.profileNodes ? t('外部共享节点的权限，请在原节点面板撤销。') : t('已有设备将无法继续领取配置。'))],
    actions: [{text: t('保留客户')}, {text: t('删除客户'), danger: true, run: async () => {
      const result = await post('/api/customer-users/' + d.id + '/delete', {});
      detailGeneration++;
      Object.assign(local, {selected: null, detail: null, detailFor: null, key: null, loading: false, dirty: false});
      ctx.navigate('/customers');
      await refreshAll();
      toast(result.nodeControl?.status === 'pending' ? t('客户已删除，节点权限移除已排队') : t('客户已删除'));
    }}]});
}

function editGroup(id) {
  const g = id ? findGroup(id) : null;
  const name = input({maxlength: '80', value: g?.name || ''});
  const config = select([{value: '', text: t('不设默认配置')}, ...templateProfiles().map(p => ({value: p.id, text: p.name + ' · ' + p.version}))], g?.profileId || '');
  const gib = input({type: 'number', min: '0', step: '1', class: 'input mono', placeholder: t('不限'), value: g?.quotaBytes ? String(Math.round(g.quotaBytes / GIB * 100) / 100) : ''});
  const limit = input({type: 'number', min: '1', max: '20', class: 'input mono', value: String(g?.deviceLimit || 1)});
  const days = input({type: 'number', min: '0', max: '36500', class: 'input mono', placeholder: t('长期'), value: g?.validDays ? String(g.validDays) : ''});
  sheet({
    title: g ? t('编辑分组') : t('新增分组'),
    kicker: g ? groupName(g.name, g.id) : '',
    body: [field(t('名称'), name), field(t('默认配置'), config),
      h('div', {class: 'field-trio'}, field(t('每月流量 GiB'), gib), field(t('设备上限'), limit), field(t('有效天数'), days))],
    actions: [{text: t('取消')}, {text: t('保存'), primary: true, run: async () => {
      const quotaGiB = gib.value === '' ? 0 : Number(gib.value);
      const deviceLimit = Number(limit.value), validDays = days.value === '' ? 0 : Number(days.value);
      if (!Number.isFinite(quotaGiB) || quotaGiB < 0 || quotaGiB > 1000000) throw new Error(t('流量额度不对'));
      if (!Number.isInteger(deviceLimit) || deviceLimit < 1 || deviceLimit > 20) throw new Error(t('设备上限是 1 到 20'));
      if (!Number.isInteger(validDays) || validDays < 0 || validDays > 36500) throw new Error(t('有效天数不对'));
      const result = await post('/api/customer-groups' + (g ? '/' + g.id + '/update' : ''), {
        name: name.value.trim(), profileId: config.value || null, quotaBytes: Math.round(quotaGiB * GIB), deviceLimit, validDays,
      });
      if (!g) local.group = result.id;
      await refreshAll();
      toast(t('分组已保存'));
    }}],
  });
}

// ---- Detail ------------------------------------------------------------------------------------

async function loadDetail(quiet = false) {
  const id = local.selected;
  if (!id) return;
  const epoch = ++detailGeneration;
  if (local.detailFor !== id) { local.detail = null; local.detailFor = id; local.dirty = false; Object.keys(slotKeys).forEach(k => delete slotKeys[k]); }
  local.loading = true;
  if (!quiet) renderInspector();
  try {
    const detail = await get('/api/customer-users/' + encodeURIComponent(id) + '/detail');
    if (local.selected !== id || epoch !== detailGeneration) return;
    local.detail = detail;
    if (local.key?.userId === id && (!detail.invitePending || detail.accessState !== 'active')) local.key = null;
    local.stamp = stampOf(findCustomer(id));
    local.error = '';
  } catch (e) {
    if (local.selected !== id || epoch !== detailGeneration) return;
    local.error = e.message;
  } finally {
    if (local.selected === id && epoch === detailGeneration) { local.loading = false; renderInspector(); }
  }
}

async function act(path, body, message) {
  try {
    await post(path, body);
    await refreshAll();
    await loadDetail(true);
    if (message) toast(message);
    return true;
  } catch (e) {
    toast(e.message, 'error');
    return false;
  }
}

function configTab(d) {
  const ownRoute = routeOf(d.id);
  const profiles = data.state.profiles.filter(p => p.source !== '线路生成' || p.id === d.profileId);
  const groupSelect = select(groupOptions(), d.groupId || '');
  const current = d.appOnly ? 'app_only' : d.profileSource === 'individual' ? d.profileId || '' : '';
  const profileSelect = select([{value: '', text: t('跟随分组')}, {value: 'app_only', text: t('仅开通 App · 不下发节点')},
    ...profiles.map(p => ({value: p.id, text: p.name + ' · ' + p.version}))], current);
  const save = button(t('保存'), {primary: true});
  save.hidden = true;
  const sync = () => {
    local.dirty = groupSelect.value !== (d.groupId || '') || profileSelect.value !== current;
    save.hidden = !local.dirty;
  };
  groupSelect.addEventListener('change', sync);
  profileSelect.addEventListener('change', sync);
  save.addEventListener('click', async () => {
    save.disabled = true;
    const appOnly = profileSelect.value === 'app_only';
    const saved = await act('/api/customer-users/' + d.id + '/configure', {groupId: groupSelect.value || null, appOnly, profileId: appOnly ? null : profileSelect.value || null}, t('已保存'));
    if (saved) { local.dirty = false; slotKeys.body = null; renderInspector(); }
    else save.disabled = false;
  });

  const p = findProfile(d.profileId);
  const blocked = d.accessState !== 'active';
  const servable = Boolean(d.profileId || d.appOnly);
  let keyBox;
  if (local.key && local.key.userId === d.id && local.key.until > Date.now()) {
    const code = qr(local.key.link, t('KOne 开通二维码'));
    keyBox = h('div', {class: 'key-slip' + (code ? ' has-qr' : '')},
      h('div', {class: 'key-slip-head'}, seal('qing', 's'), h('strong', null, t('开通密钥')), countdown(local.key.until, () => { local.key = null; slotKeys.body = null; renderInspector(); })),
      code ? h('div', {class: 'key-qr'}, code) : null,
      h('div', {class: 'key-words'},
        secret(local.key.link || local.key.code, {masked: false}),
        h('small', {class: 'quiet'}, (code ? t('用 KOne 扫码，或把这串发给对方粘贴；') : t('在 KOne 的密钥页输入；')) + t('只显示这一次，用过即失效。'))));
  } else {
    const make = button(d.invitePending ? t('重新生成密钥') : t('生成密钥'), {primary: !d.invitePending, quiet: d.invitePending, iconName: 'key', disabled: !servable || blocked});
    make.addEventListener('click', async () => {
      make.disabled = true;
      try {
        const result = await post('/api/customer-users/' + d.id + '/invite', {});
        local.key = {userId: d.id, code: result.inviteCode, link: result.claimLink || null,
          until: result.expiresAt ? result.expiresAt * 1000 : Date.now() + (result.expiresIn || 900) * 1000};
        slotKeys.body = null;
        await refreshCustomers().catch(() => {});
        await loadDetail(true);
      } catch (e) {
        toast(e.message, 'error');
        make.disabled = false;
      }
    });
    keyBox = h('div', {class: 'key-box'},
      make,
      h('small', {class: 'quiet'}, !servable ? t('先分配配置') : blocked ? t('服务恢复后才能生成') : d.invitePending ? t('上一串密钥还没用') : d.deviceCount ? t('换手机或加设备时再生成') : ''));
  }
  const pause = h('button', {type: 'button', class: 'text-button'}, d.paused ? t('恢复下发') : t('暂停下发'));
  pause.addEventListener('click', () => act('/api/customer-users/' + d.id + '/pause', {paused: !d.paused}, d.paused ? t('已恢复下发') : t('已暂停下发')));
  // Claude: the console's own servers refuse it for this person; nodes from an imported
  // config can only be held back by the app, which does not do it yet.
  const claudeButton = h('button', {type: 'button', class: 'toggle', role: 'switch', 'aria-label': t('拦截 Claude'), 'aria-checked': String(Boolean(d.blockClaude))}, h('span', null));
  claudeButton.addEventListener('click', async () => {
    claudeButton.disabled = true;
    try { await act('/api/customer-users/' + d.id + '/claude', {blocked: !d.blockClaude}, d.blockClaude ? t('已关闭 Claude 拦截') : t('已开启 Claude 拦截')); }
    finally { if (claudeButton.isConnected) claudeButton.disabled = false; }
  });
  const claudeNote = !d.blockClaude ? t('仅对这位客户生效') : d.appOnly || !p ? t('分配节点后生效')
    : p.source === '线路生成' ? (ownRoute?.status === 'running' ? t('托管节点已应用') : t('等待托管节点应用')) : t('导入节点需使用支持拦截规则的 App');
  return h('div', {class: 'tab-body'},
    h('div', {class: 'inline-form'}, field(t('分组'), groupSelect), field(t('配置'), profileSelect), save),
    h('div', {class: 'current-config'}, icon(d.appOnly ? 'phone' : 'configs'),
      d.appOnly ? h('div', null, h('strong', null, t('仅开通 App')), h('small', null, t('不下发节点')))
        : h('div', null, h('strong', null, d.profileName || t('还没有配置')),
          h('small', null, p ? p.version + ' · ' + (d.profileSource === 'group' ? t('跟随分组') : ownRoute ? t('线路 ') + ownRoute.name : t('单独分配')) : t('分组或个人都还没有配置')))),
    // A console older than 0.3.2 does not report the setting; then there is no switch to show.
    typeof d.blockClaude === 'boolean' ? h('div', {class: 'current-config claude-row' + (d.blockClaude ? ' is-blocked' : '')}, icon(d.blockClaude ? 'ban' : 'check'),
      h('div', null, h('strong', null, t('拦截 Claude')), h('small', null, claudeNote)),
      claudeButton) : null,
    facts([
      [t('每月流量'), quota(d.policy.quotaBytes)],
      [t('设备'), number(d.deviceCount) + ' / ' + count(d.policy.deviceLimit, 'device')],
      [t('到期'), d.policy.expiresAt ? when(d.policy.expiresAt) : t('长期')],
    ], 'facts-row'),
    keyBox,
    !d.revoked ? pause : null);
}

function devicesTab(d) {
  if (!d.devices.length) return h('div', {class: 'tab-body'}, empty('phone', t('还没有设备'), null));
  return h('div', {class: 'tab-body'}, h('ul', {class: 'device-list'}, d.devices.map(v => {
    const weather = deviceWeather(v, d.revision);
    const revoke = h('button', {type: 'button', class: 'text-button is-danger', disabled: v.revoked}, t('撤销'));
    if (!v.revoked) twoStep(revoke, {ask: t('确认撤销'), run: () => act('/api/customer-users/' + d.id + '/devices/' + v.id + '/revoke', {}, t('设备已撤销'))});
    return h('li', {class: 'device' + (v.revoked ? ' is-revoked' : '')},
      h('span', {class: 'device-icon'}, icon('phone')),
      h('div', {class: 'device-main'},
        h('strong', null, v.name),
        h('small', null, [v.app_version ? 'KOne ' + v.app_version : null, v.network ? NETWORK[v.network] || t('未知') : null, v.last_seen ? t('同步 ') + ago(v.last_seen) : t('未同步')].filter(Boolean).join(' · ')),
        v.ack_code && v.ack_code !== 'ok' ? h('small', {class: 'warn-text'}, ackText(v.ack_code)) : null),
      weatherLine(weather),
      revoke);
  })));
}

function usageTab(d) {
  const used = d.usage.totalBytes, max = d.policy.quotaBytes;
  const samples = d.devices.filter(v => v.report_at);
  return h('div', {class: 'tab-body'},
    h('div', {class: 'usage-hero'},
      h('small', null, /^\d{4}-\d{2}$/.test(d.policy.period || '') ? new Intl.DateTimeFormat(locale, {year: 'numeric', month: 'long', timeZone: 'UTC'}).format(new Date(d.policy.period + '-01T00:00:00Z')) : '—'),
      h('strong', null, used == null ? t('待采集') : bytes(used)),
      h('span', null, max ? '/ ' + bytes(max) : t('不限流量'))),
    meter(used, max),
    facts([
      [t('下载'), bytes(d.usage.downloadBytes)],
      [t('上传'), bytes(d.usage.uploadBytes)],
      [t('计量'), used == null ? t('服务器还没报来') : (d.usage.fresh ? t('入口服务器') : t('入口服务器 · 上次')) + ' · ' + ago(d.usage.updated)],
    ], 'facts-row'),
    samples.length ? [h('h3', {class: 'section-title'}, t('客户端自报')),
      h('ul', {class: 'sample-list'}, samples.map(v => h('li', null, h('strong', null, v.name), h('span', {class: 'mono'}, '↓ ' + bytes(v.report_rx) + '  ↑ ' + bytes(v.report_tx)), h('small', null, ago(v.report_at)))))] : null);
}

function eventsTab(d) {
  const exportButton = button(t('导出'), {quiet: true, iconName: 'download', onClick: () => download('/api/customer-users/' + d.id + '/diagnostics', 'kone-' + d.name + '-diagnostics.json').catch(e => toast(e.message, 'error'))});
  return h('div', {class: 'tab-body'},
    h('div', {class: 'tab-tools'}, h('small', {class: 'quiet'}, t('近 7 天')), exportButton),
    d.events.length ? h('ul', {class: 'event-list'}, d.events.map(e => h('li', {class: 'event' + (/fail|invalid/.test(e.code) ? ' is-bad' : '')},
      h('i', {class: 'event-dot'}),
      h('div', null, h('strong', null, eventText(e.code)), h('small', null, when(e.created) + ' · ' + (e.source === 'client' ? t('客户端') : t('管理中心')))))))
      : empty('records', t('这几天没有记录'), null));
}

function servicePart(d) {
  const control = d.nodeControl || {status: 'not_requested'};
  const note = d.revoked || (control.action === 'restore' && control.status !== 'applied')
    ? h('p', {class: 'quiet'}, {applied: t('托管节点已确认'), pending: t('等节点执行'), failed: t('节点执行失败，看部署任务'), not_managed: d.profileNodes ? (d.revoked ? t('外部节点需在原面板撤销') : t('外部节点需在原面板恢复')) : t('没有托管节点')}[control.status] || t('等节点确认'))
    : null;
  const toggle = h('button', {type: 'button', class: 'text-button' + (d.revoked ? '' : ' is-danger')}, d.revoked ? t('恢复服务') : t('收回服务'));
  twoStep(toggle, {ask: d.revoked ? t('确认恢复') : t('确认收回'), run: () => act('/api/customer-users/' + d.id + '/' + (d.revoked ? 'restore' : 'revoke'), {}, d.revoked ? t('已恢复，旧密钥和设备仍然失效') : t('已收回'))});
  return h('footer', {class: 'inspector-foot'}, h('span', null, t('服务')), note, toggle);
}

function inspectorSlot(name, key, draw) {
  const slot = els.inspector.querySelector('[data-slot="' + name + '"]');
  if (!slot || slotKeys[name] === key) return;
  slotKeys[name] = key;
  fill(slot, draw());
}

function renderInspector() {
  if (!els) return;
  const summary = findCustomer(local.selected);
  if (!local.selected || !summary) {
    if (els.inspector.dataset.for !== '') {
      els.inspector.dataset.for = '';
      fill(els.inspector, data.customers.users.length ? h('div', {class: 'detail-blank'}, icon('customers'), h('p', null, t('选一位客户'))) : null);
    }
    return;
  }
  if (els.inspector.dataset.for !== summary.id) {
    els.inspector.dataset.for = summary.id;
    Object.keys(slotKeys).forEach(k => delete slotKeys[k]);
    fill(els.inspector, h('article', {class: 'detail inspector'}, ['head', 'tabs', 'body', 'foot'].map(name => h('div', {class: 'slot', 'data-slot': name}))));
  }
  const d = local.detail && local.detailFor === summary.id ? local.detail : null;
  const weather = customerWeather(d || summary);
  inspectorSlot('head', JSON.stringify([summary.name, summary.groupName, weather]), () => h('header', {class: 'detail-head person-head'},
    h('a', {class: 'back phone-only', href: '#/customers'}, icon('left'), t('客户')),
      nameSeal(summary.name, 'l'),
    h('div', {class: 'detail-title'}, h('h2', null, summary.name), h('p', {class: 'detail-sub'}, groupName(summary.groupName, summary.groupId)), h('div', {class: 'detail-state'}, weatherTag(weather))),
    button(t('删除客户'), {quiet: true, iconName: 'trash', onClick: () => deleteCustomer(local.detailFor === summary.id && local.detail || summary)})));
  inspectorSlot('tabs', local.tab, () => tabs(TABS.map(([id, text]) => ({id, text})), local.tab, id => { local.tab = id; local.dirty = false; renderInspector(); }, t('客户详情')));
  if (!d) {
    inspectorSlot('body', 'loading:' + local.error, () => h('div', {class: 'tab-body'}, local.error ? h('p', {class: 'warn-text'}, local.error) : h('p', {class: 'quiet loading'}, t('正在读取'))));
    inspectorSlot('foot', 'none', () => null);
    return;
  }
  // While a choice is being made in the selects, leave the form alone.
  const bodyKey = JSON.stringify([local.tab, d, local.key && local.key.userId === d.id ? local.key.code : null, data.state.profiles.map(p => [p.id, p.name]), data.customers.groups.map(g => [g.id, g.name]), Math.floor(Date.now() / 60000)]);
  if (!(local.tab === 'config' && (local.dirty || els.inspector.querySelector('.choice.is-open')))) {
    inspectorSlot('body', bodyKey, () => ({config: configTab, devices: devicesTab, usage: usageTab, events: eventsTab}[local.tab])(d));
  }
  inspectorSlot('foot', JSON.stringify([d.revoked, d.nodeControl, d.profileNodes]), () => servicePart(d));
}

// ---- List --------------------------------------------------------------------------------------

function row(u) {
  const weather = customerWeather(u);
  const used = u.usage.totalBytes, max = u.policy.quotaBytes;
  const usage = used == null ? (max ? t('额度 ') + bytes(max) : '') : bytes(used) + (max ? ' / ' + bytes(max) : '');
  return h('a', {class: 'row customer-row' + (u.id === local.selected ? ' is-selected' : ''), href: '#/customers/' + u.id, 'aria-current': u.id === local.selected ? 'true' : null},
    nameSeal(u.name, 'm'),
    h('span', {class: 'row-main'}, h('strong', null, u.name, u.blockClaude ? h('span', {class: 'claude-mark', role: 'img', 'aria-label': t('Claude 拦截已开启'), title: t('Claude 拦截已开启')}, icon('ban')) : null),
      h('small', null, groupName(u.groupName, u.groupId) + ' · ' + number(u.deviceCount) + '/' + count(u.policy.deviceLimit, 'device'))),
    h('span', {class: 'row-side'}, weatherLine(weather),
      usage ? h('small', {class: 'row-usage'}, usage) : null,
      used != null && max ? meter(used, max) : null));
}

function shown() {
  return data.customers.users.filter(u => (local.group === 'all' || u.groupId === local.group) && matches(local.query, u.name, u.profileName, u.groupName));
}

function filterChanged() {
  const users = shown();
  if (!users.some(u => u.id === local.selected)) {
    const id = window.innerWidth > 980 ? users[0]?.id || null : null;
    if (id) choose(id);
    else { local.selected = null; detailGeneration++; local.loading = false; }
    ctx.navigate('/customers' + (id ? '/' + id : ''));
  }
  render();
}

function renderList() {
  const users = shown();
  const groups = data.customers.groups;
  const key = JSON.stringify([local.group, local.query, local.selected, users.map(u => [u.id, u.name, u.groupName, u.deviceCount, u.policy.deviceLimit, u.profileName, u.usage.totalBytes, u.policy.quotaBytes, customerWeather(u), u.blockClaude]), groups.map(g => [g.id, g.name, g.memberCount]), data.loaded.customers]);
  if (key === lastList) return;
  lastList = key;
  if (local.group !== 'all' && !groups.some(g => g.id === local.group)) local.group = 'all';
  const groupTabs = tabs([{id: 'all', text: t('全部'), count: data.customers.users.length}, ...groups.map(g => ({id: g.id, text: groupName(g.name, g.id), count: g.memberCount}))], local.group, id => {
    local.group = id;
    filterChanged();
  }, t('分组'));
  fill(els.groups, groupTabs,
    local.group !== 'all' ? iconButton('pen', t('编辑分组'), () => editGroup(local.group)) : null,
    iconButton('plus', t('新增分组'), () => editGroup(null)));
  fill(els.list, users.length ? users.map(row)
    : data.customers.users.length ? empty('search', t('没有匹配的客户'), null)
      : data.loaded.customers ? empty('customers', t('还没有客户'), button(t('新增客户'), {primary: true, iconName: 'plus', onClick: addCustomer})) : null);
}

function render() {
  if (!els) return;
  renderList();
  renderInspector();
  els.page.classList.toggle('has-selection', Boolean(findCustomer(local.selected)));
}

function choose(id) {
  if (local.selected === id) return;
  local.selected = id;
  local.tab = 'config';
  local.dirty = false;
  loadDetail();
}

export default {
  id: 'customers',
  hasUnsavedChanges() { return local.dirty; },
  title: t('客户'),
  icon: 'customers',
  mount(view, params, context) {
    ctx = context;
    lastList = '';
    const search = h('input', {class: 'input search', type: 'search', placeholder: t('名字、配置、分组'), 'aria-label': t('搜索客户'), value: local.query, autocomplete: 'off', spellcheck: 'false'});
    search.addEventListener('input', () => { local.query = search.value; filterChanged(); });
    els = {
      groups: h('div', {class: 'group-tabs'}),
      list: h('div', {class: 'list', role: 'list'}),
      inspector: h('div', {class: 'detail-wrap'}),
    };
    els.inspector.dataset.for = '-';
    els.page = h('div', {class: 'split'},
      h('div', {class: 'split-list'}, els.groups, h('div', {class: 'toolbar'}, h('label', {class: 'search-wrap'}, icon('search'), search)), els.list),
      els.inspector);
    view.append(els.page);
    ctx.setActions([button(t('新增客户'), {primary: true, iconName: 'plus', onClick: addCustomer})]);
    const wanted = params[0] || local.selected || (window.innerWidth > 980 ? shown()[0]?.id : null);
    local.selected = null;
    if (wanted) choose(wanted);
    render();
  },
  update(params) {
    if (params[0]) choose(params[0]);
    else if (window.innerWidth <= 980) local.selected = null;
    else if (!local.selected && shown().length) choose(shown()[0].id);
    // Keep the open person's detail as fresh as the list.
    const summary = findCustomer(local.selected);
    if (summary && local.detail && local.detailFor === summary.id && !local.loading && stampOf(summary) !== local.stamp) loadDetail(true);
    render();
  },
  unmount() { detailGeneration++; local.loading = false; els = null; },
  reset() {
    detailGeneration++;
    Object.assign(local, {group: 'all', query: '', selected: null, tab: 'config', detail: null, detailFor: null, loading: false, error: '', key: null, dirty: false});
    Object.keys(slotKeys).forEach(k => delete slotKeys[k]);
  },
};
