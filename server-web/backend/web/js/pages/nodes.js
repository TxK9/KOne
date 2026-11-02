import {t, count, number, systemText, listText} from '../i18n.js';
import {h, fill, matches, when} from '../core.js';
import {get, post} from '../api.js';
import {data, refreshController} from '../store.js';
import {icon} from '../icons.js';
import {sheet, toast, field, input, select} from '../layer.js';
import {button, empty, tabs, facts} from '../widgets.js';
import {unique, dependencyClosure, rootsWithout, authorizationKey, executionKey, canManage, nodeAvailable, combinedImpact} from '../controller-model.js';

const BASE = '/api/node-controller';
const MAX_FILE = 512 * 1024;
const PROTOCOLS = {vless: 'VLESS', vmess: 'VMess', shadowsocks: 'Shadowsocks', hysteria2: 'Hysteria 2', trojan: 'Trojan', socks: 'SOCKS5', http: 'HTTP', tuic: 'TUIC', anytls: 'AnyTLS'};
let els = null, ctx = null, lastKey = '', timer = null;
let local = {tab: 'nodes', query: '', selected: new Set()};
const nodes = () => data.controller?.nodes || [];
const devices = () => data.controller?.devices || [];
const findNode = id => nodes().find(node => node.id === id);
const selectedNodes = () => nodes().filter(node => local.selected.has(node.id) && node.deleted === (local.tab === 'deleted'));
const nodeName = id => findNode(id)?.name || t('Unavailable node');
const kind = node => PROTOCOLS[node.protocol] || t('Unknown protocol');
const platform = device => ({windows: 'Windows', android: 'Android', router: t('Router')})[device.platform] || t('Unreported platform');
const deviceNodeCount = device => (device.expandedNodeIds || device.grantedNodeIds || []).length;
const serverNow = () => Number.isFinite(data.controller?.serverTime)
  ? data.controller.serverTime + Math.max(0, performance.now() - data.controllerReceivedAt) / 1000 : Date.now() / 1000;

function indicator(text, tone = '') {
  return h('span', {class: 'node-status' + (tone ? ' node-status-' + tone : '')}, h('i', {'aria-hidden': 'true'}), text);
}

function deviceStatus(device) {
  return h('div', {class: 'controller-device-status'},
    indicator(t(authorizationKey(device)), device.controllerMode === 'revoked' ? 'revoked' : device.controllerEnabled ? 'ok' : ''),
    h('small', null, t(executionKey(device, serverNow()))));
}

function picker(items, {chosen = [], label, none, searchText = t('Search devices or owners'), disabled = () => false} = {}) {
  const selected = new Set(chosen);
  const search = input({type: 'search', placeholder: searchText, 'aria-label': label});
  const list = h('div', {class: 'picker-list'});
  const total = h('small', {class: 'picker-count', role: 'status'});
  const tally = () => { total.textContent = selected.size ? t('{n} selected', {n: number(selected.size)}) : ''; };
  const draw = () => {
    const shown = items.filter(item => matches(search.value, item.name, item.userName, item.note));
    fill(list, shown.length ? shown.map(item => {
      const unavailable = disabled(item);
      const box = h('input', {type: 'checkbox', checked: selected.has(item.id), disabled: unavailable});
      box.addEventListener('change', () => { box.checked ? selected.add(item.id) : selected.delete(item.id); tally(); });
      return h('label', {class: 'picker-item controller-picker-item' + (unavailable ? ' is-disabled' : '')}, box,
        h('span', {class: 'picker-name'}, h('strong', null, item.name), item.userName ? h('small', null, item.userName) : null),
        h('small', null, item.note || (unavailable ? t('Client update required') : platform(item))));
    }) : h('p', {class: 'picker-empty'}, items.length ? t('No matching devices') : none || t('No devices yet')));
  };
  search.addEventListener('input', draw);
  draw(); tally();
  return {chosen: selected, node: h('div', {class: 'picker'}, h('div', {class: 'picker-head'}, search, total), list)};
}

function nodeSummary(list) {
  return h('ul', {class: 'controller-summary'}, list.map(node => h('li', null, h('strong', null, node.name), h('small', null, kind(node)))));
}

async function impacts(list) {
  return combinedImpact(await Promise.all(list.map(node => get(BASE + '/nodes/' + node.id + '/impact'))));
}

function impactBody(list, impact) {
  const selected = new Set(list.map(node => node.id));
  const dependent = impact.dependentNodeIds.filter(id => !selected.has(id));
  return h('div', {class: 'controller-impact'}, nodeSummary(list),
    facts([[t('Affected devices'), count(impact.devices.length, 'device')], [t('Dependent nodes'), count(dependent.length, 'node')]], 'facts-row'),
    dependent.length ? h('p', {class: 'quiet'}, listText(dependent.map(nodeName))) : null,
    impact.devices.length ? h('ul', {class: 'controller-summary'}, impact.devices.map(device => h('li', null,
      h('strong', null, device.name), h('small', null, device.userName || '')))) : null);
}

async function deleteNodes(list) {
  try {
    const impact = await impacts(list);
    sheet({title: t('Delete nodes'), body: [impactBody(list, impact), h('p', {class: 'controller-action-note'}, t('Device access is withdrawn. Offline devices remain pending until confirmed.'))],
      actions: [{text: t('Cancel')}, {text: t('Delete nodes'), danger: true, run: async () => {
        const results = [];
        for (const node of list) {
          try { await post(BASE + '/nodes/' + node.id + '/delete', {expectedRevision: node.revision, confirmImpact: true}); results.push({ok: true}); }
          catch (error) { results.push({ok: false, error}); }
        }
        await refreshController(); local.selected.clear(); lastKey = ''; render();
        const failed = results.filter(result => !result.ok);
        if (failed.length) throw failed[0].error;
        toast(t('Nodes deleted'));
      }}]});
  } catch (error) { toast(error.message, 'error'); }
}

function restoreNodes(list) {
  sheet({title: t('Restore nodes'), body: [nodeSummary(list), h('p', {class: 'controller-action-note'}, t('Previous device permissions remain withdrawn.'))],
    actions: [{text: t('Cancel')}, {text: t('Restore nodes'), primary: true, run: async () => {
      let failure = null;
      for (const node of list) try { await post(BASE + '/nodes/' + node.id + '/restore', {expectedRevision: node.revision}); }
      catch (error) { failure ||= error; }
      await refreshController(); local.selected.clear(); lastKey = ''; render();
      if (failure) throw failure;
      toast(t('Nodes restored'));
    }}]});
}

async function changeAssignments(list, withdraw = false, onlyDevice = null) {
  try {
    const impact = withdraw ? await impacts(list) : null;
    const affected = new Map((impact?.devices || []).map(device => [device.id, device]));
    const available = devices().filter(device => !onlyDevice || device.id === onlyDevice.id).filter(device => !withdraw || affected.has(device.id));
    const choices = picker(available, {label: t('Choose devices'), chosen: onlyDevice ? [onlyDevice.id] : [], disabled: device => !canManage(device),
      none: withdraw ? t('No device has these nodes') : t('No devices yet')});
    sheet({title: t(withdraw ? 'Withdraw nodes' : 'Assign nodes'), body: [nodeSummary(list),
      withdraw && impact.dependentNodeIds.length ? h('p', {class: 'controller-action-note'}, t('Dependent exits are withdrawn with their relay.')) : null,
      choices.node], actions: [{text: t('Cancel')}, {text: t(withdraw ? 'Withdraw nodes' : 'Assign nodes'), primary: !withdraw, danger: withdraw, run: async api => {
        const chosen = available.filter(device => choices.chosen.has(device.id));
        if (!chosen.length) throw new Error(t('Choose at least one device.'));
        const selectedIds = list.map(node => node.id);
        const results = await Promise.all(chosen.map(async device => {
          try {
            let current = device;
            if (!withdraw && !current.controllerEnabled) {
              await post(BASE + '/devices/' + device.id + '/configure', {enabled: true, expectedGeneration: device.authGeneration});
              const fresh = await get(BASE);
              current = fresh.devices.find(item => item.id === device.id);
              if (!current) throw new Error(t('The device changed. Refresh and try again.'));
            }
            const roots = withdraw ? rootsWithout(current.grantedNodeIds || [], selectedIds, nodes()) : unique([...(current.grantedNodeIds || []), ...selectedIds]);
            await post(BASE + '/devices/' + current.id + '/grants', {nodeIds: roots, expectedGeneration: current.authGeneration});
            return {device, ok: true};
          } catch (error) { return {device, ok: false, error}; }
        }));
        await refreshController();
        const failed = results.filter(result => !result.ok);
        if (failed.length) {
          fill(api.content, h('ul', {class: 'controller-summary'}, results.map(result => h('li', null, h('strong', null, result.device.name),
            h('small', {class: result.ok ? '' : 'warn-text'}, result.ok ? t('Awaiting device confirmation') : result.error.message)))));
          api.setActions([{text: t('Close'), primary: true}]);
          return false;
        }
        toast(t(withdraw ? 'Authorization withdrawn' : 'Device access updated'));
      }}]});
  } catch (error) { toast(error.message, 'error'); }
}

function renameNode(node) {
  const name = input({value: node.name, maxlength: '80'});
  sheet({title: t('Rename node'), body: field(t('Name'), name), actions: [{text: t('Cancel')}, {text: t('Save'), primary: true, run: async () => {
    if (!name.value.trim()) throw new Error(t('Enter a node name.'));
    await post(BASE + '/nodes/' + node.id + '/update', {expectedRevision: node.revision, name: name.value.trim()});
    await refreshController(); toast(t('Node updated'));
  }}]});
}

async function editConnection(node) {
  try {
    const detail = await get(BASE + '/nodes/' + node.id + '/config');
    const text = h('textarea', {class: 'input code-area controller-connection', spellcheck: 'false', 'aria-label': t('Connection configuration')});
    text.value = JSON.stringify(detail.config, null, 2);
    const previousRelay = detail.dependencyIds?.[0] || '';
    const relay = select([{value: '', text: t('Direct connection')}, ...nodes().filter(candidate => candidate.id === previousRelay ||
      !candidate.deleted && candidate.id !== node.id && !dependencyClosure([candidate.id], nodes()).has(node.id)).map(candidate => ({
        value: candidate.id, text: candidate.name + (candidate.deleted ? ' · ' + t('Deleted') : ''), disabled: candidate.deleted,
      }))], previousRelay);
    sheet({title: t('Edit connection'), kicker: node.name, wide: true,
      body: [field(t('Connection configuration'), text), field(t('Connection via'), relay)],
      actions: [{text: t('Cancel')}, {text: t('Save'), primary: true, run: async () => {
        await post(BASE + '/nodes/' + node.id + '/update', {expectedRevision: detail.revision, configText: text.value,
          ...(relay.value !== previousRelay ? {dependencyIds: relay.value ? [relay.value] : []} : {})});
        text.value = '';
        await refreshController(); toast(t('Node updated'));
      }}]});
  } catch (error) { toast(error.message, 'error'); }
}

function showNode(node) {
  const grantDevices = devices().filter(device => (device.expandedNodeIds || [...dependencyClosure(device.grantedNodeIds || [], nodes())]).includes(node.id));
  const body = h('div', null,
    facts([[t('Protocol'), kind(node)], [t('Source'), node.sourceName || t('Server library')], [t('Revision'), number(node.revision)], [t('Updated'), when(node.updated)]], 'facts-row'),
    node.dependencyIds?.length ? h('section', {class: 'detail-section'}, h('h3', {class: 'section-title'}, t('Required nodes')), nodeSummary(node.dependencyIds.map(findNode).filter(Boolean))) : null,
    h('section', {class: 'detail-section'}, h('h3', {class: 'section-title'}, t('Devices')), grantDevices.length ? h('ul', {class: 'controller-summary'}, grantDevices.map(device => h('li', null,
      h('span', null, h('strong', null, device.name), h('small', null, device.userName || platform(device))), deviceStatus(device)))) : empty('phone', t('No devices assigned'))));
  sheet({title: node.name, body, actions: node.deleted ? [{text: t('Close')}, {text: t('Restore'), primary: true, run: () => { restoreNodes([node]); return false; }}]
    : [{text: t('Rename'), quiet: true, run: () => { renameNode(node); return false; }},
      {text: t('Edit connection'), quiet: true, run: () => { editConnection(node); return false; }},
      {text: t('Withdraw'), quiet: true, run: () => { changeAssignments([node], true); return false; }},
      {text: t('Delete'), danger: true, run: () => { deleteNodes([node]); return false; }},
      ...(nodeAvailable(node, nodes()) ? [{text: t('Assign'), primary: true, run: () => { changeAssignments([node]); return false; }}] : [])]});
}

function revokeDevice(device) {
  const legacy = !canManage(device);
  sheet({title: t(legacy ? 'Revoke legacy access' : 'Revoke device access'), kicker: device.name,
    body: [facts([[t('Owner'), device.userName], [t('Nodes'), count(deviceNodeCount(device), 'node')]], 'facts-row'),
      h('p', {class: 'controller-action-note'}, t(legacy ? 'Legacy client access can be revoked. Stopping cached connections is not confirmed.' : 'Authorization is revoked now. Device confirmation may arrive later.'))],
    actions: [{text: t('Cancel')}, {text: t('Revoke access'), danger: true, run: async () => {
      await post(BASE + '/devices/' + device.id + '/revoke', {expectedGeneration: device.authGeneration});
      await refreshController(); toast(t('Authorization withdrawn'));
    }}]});
}

function showDevice(device) {
  const list = nodes().filter(node => !node.deleted);
  const choices = picker(list.map(node => ({...node, note: kind(node)})), {label: t('Choose nodes'), searchText: t('Search nodes'), chosen: device.grantedNodeIds || [],
    none: t('No nodes yet'), disabled: node => !nodeAvailable(node, nodes())});
  const body = h('div', null, deviceStatus(device),
    facts([[t('Owner'), device.userName], [t('Platform'), platform(device)], [t('Last seen'), when(device.lastSeen)],
      [t('Authorization version'), number(device.authGeneration)], [t('Confirmed version'), device.ackGeneration == null ? '—' : number(device.ackGeneration)]], 'facts-row'),
    canManage(device) ? choices.node : h('p', {class: 'controller-action-note'}, t('Update and connect this device before assigning nodes.')));
  sheet({title: device.name, body, actions: [{text: t('Close')},
    ...(authorizationKey(device) !== 'Authorization revoked' ? [{text: t(canManage(device) ? 'Revoke access' : 'Revoke legacy access'), danger: true, run: () => { revokeDevice(device); return false; }}] : []),
    ...(canManage(device) ? [{text: t('Save access'), primary: true, run: async () => {
      let current = device;
      if (!current.controllerEnabled) {
        await post(BASE + '/devices/' + current.id + '/configure', {enabled: true, expectedGeneration: current.authGeneration});
        const fresh = await get(BASE);
        current = fresh.devices.find(item => item.id === device.id);
        if (!current) throw new Error(t('The device changed. Refresh and try again.'));
      }
      await post(BASE + '/devices/' + current.id + '/grants', {nodeIds: [...choices.chosen], expectedGeneration: current.authGeneration});
      await refreshController(); toast(t('Device access updated'));
    }}] : [])]});
}

function importNodes() {
  const text = h('textarea', {class: 'input code-area', spellcheck: 'false', 'aria-label': t('Node content'), placeholder: t('Paste node links, PassWall UCI, Clash YAML or JSON')});
  const fileInput = h('input', {type: 'file', hidden: true, accept: '.json,.txt,.yaml,.yml,.conf,.uci,application/json,text/plain'});
  const preview = h('div', {class: 'import-notes', role: 'status', 'aria-live': 'polite'});
  let checked = '';
  const invalidate = () => { checked = ''; fill(preview); };
  fileInput.addEventListener('change', async () => {
    const file = fileInput.files?.[0]; if (!file) return;
    if (file.size > MAX_FILE) { toast(t('文件需小于 512 KB'), 'error'); return; }
    try { text.value = await file.text(); invalidate(); } catch { toast(t('The file could not be read.'), 'error'); }
  });
  text.addEventListener('input', invalidate);
  const profiles = data.state.profiles.filter(profile => profile.source !== '线路生成');
  const saved = profiles.length ? select(profiles.map(profile => ({value: profile.id, text: profile.name})), profiles[0].id, {'aria-label': t('Saved configuration')}) : null;
  sheet({title: t('Import nodes'), wide: true, body: [
    saved ? field(t('Saved configuration'), h('div', {class: 'controller-load-profile'}, saved, button(t('Load'), {quiet: true, onClick: async event => {
      const control = event.currentTarget;
      control.disabled = true;
      try { const config = await get('/api/profiles/' + encodeURIComponent(saved.value) + '/config'); text.value = JSON.stringify(config, null, 2); invalidate(); }
      catch (error) { toast(error.message, 'error'); }
      finally { control.disabled = false; }
    }}))) : null,
    h('div', {class: 'field'}, h('div', {class: 'field-label-row'}, h('label', {for: 'node-import-text'}, t('Node content')), button(t('Choose file'), {quiet: true, iconName: 'upload', onClick: () => fileInput.click()})), Object.assign(text, {id: 'node-import-text'}), fileInput), preview],
    actions: [{text: t('Cancel')}, {text: t('Check import'), run: async () => {
      if (!text.value.trim()) throw new Error(t('请粘贴或选择配置'));
      const submitted = text.value;
      const result = await post(BASE + '/nodes/preview', {configText: submitted});
      if (text.value !== submitted) return false;
      checked = submitted;
      const list = Array.isArray(result.nodes) ? result.nodes : [];
      fill(preview, h('p', null, count(list.length || result.nodeCount || result.nodes || 0, 'node')),
        list.length ? nodeSummary(list) : null,
        result.warnings?.length ? h('ul', null, result.warnings.map(message => h('li', null, systemText(message, '系统消息不可用')))) : null);
      return false;
    }}, {text: t('Import'), primary: true, run: async () => {
      if (checked !== text.value || !checked.trim()) throw new Error(t('Check the current content before importing.'));
      await post(BASE + '/nodes/import', {configText: checked});
      await refreshController(); toast(t('Node library updated'));
    }}]});
}

function renderActions() {
  if (!els) return;
  const list = selectedNodes();
  const deleted = local.tab === 'deleted';
  els.selection.hidden = local.tab === 'devices' || !list.length;
  fill(els.selection, h('span', {role: 'status'}, t('{n} selected', {n: number(list.length)})),
    deleted ? button(t('Restore'), {quiet: true, iconName: 'refresh', onClick: () => restoreNodes(list)}) : [
      button(t('Assign'), {quiet: true, iconName: 'plus', disabled: list.some(node => !nodeAvailable(node, nodes())), onClick: () => changeAssignments(list)}),
      button(t('Withdraw'), {quiet: true, iconName: 'ban', onClick: () => changeAssignments(list, true)}),
      button(t('Delete'), {quiet: true, danger: true, iconName: 'trash', onClick: () => deleteNodes(list)})],
    button(t('Clear selection'), {quiet: true, onClick: () => { local.selected.clear(); lastKey = ''; render(); }}));
}

function nodeRow(node) {
  const box = h('input', {type: 'checkbox', checked: local.selected.has(node.id), 'aria-label': t('Select {name}', {name: node.name})});
  box.addEventListener('change', () => { box.checked ? local.selected.add(node.id) : local.selected.delete(node.id); renderActions(); });
  const available = nodeAvailable(node, nodes());
  return h('article', {class: 'controller-node-row' + (node.deleted ? ' is-deleted' : ''), 'data-node-id': node.id}, box,
    h('div', {class: 'controller-node-title'}, h('button', {type: 'button', class: 'controller-name', onClick: () => showNode(node)}, node.name),
      h('small', null, kind(node), ' · ', node.sourceName || t('Server library'))),
    h('div', {class: 'controller-node-facts'},
      h('small', null, count(node.grantCount || 0, 'device')),
      node.dependencyIds?.length ? h('small', null, t('Requires {nodes}', {nodes: count(node.dependencyIds.length, 'node')})) : null),
    indicator(t(node.deleted ? 'Deleted' : available ? 'Assignable' : 'Missing dependency'), node.deleted ? '' : available ? 'ok' : 'revoked'),
    button(t(node.deleted ? 'Restore' : 'Manage'), {quiet: true, onClick: () => node.deleted ? restoreNodes([node]) : showNode(node)}));
}

function deviceRow(device) {
  return h('article', {class: 'controller-device-row', 'data-device-id': device.id},
    h('span', {class: 'controller-device-icon'}, icon(device.platform === 'router' ? 'routes' : device.platform === 'windows' ? 'terminal' : 'phone')),
    h('div', {class: 'controller-node-title'}, h('button', {type: 'button', class: 'controller-name', onClick: () => showDevice(device)}, device.name),
      h('small', null, [device.userName, platform(device)].filter(Boolean).join(' · '))),
    h('small', {class: 'controller-device-count'}, count(deviceNodeCount(device), 'node')),
    deviceStatus(device), button(t('Manage'), {quiet: true, onClick: () => showDevice(device)}));
}

function render() {
  if (!els) return;
  const key = JSON.stringify([data.controller, data.loaded.controller, local.tab, local.query, devices().map(device => executionKey(device, serverNow()))]);
  if (key === lastKey) return;
  lastKey = key;
  const active = nodes().filter(node => !node.deleted), deleted = nodes().filter(node => node.deleted);
  const known = new Set(nodes().map(node => node.id));
  for (const id of local.selected) if (!known.has(id)) local.selected.delete(id);
  fill(els.tabs, tabs([{id: 'nodes', text: t('Nodes'), count: active.length}, {id: 'devices', text: t('Devices'), count: devices().length},
    {id: 'deleted', text: t('Deleted'), count: deleted.length}], local.tab, id => {
    local.tab = id; local.selected.clear(); local.query = ''; els.search.value = ''; lastKey = ''; render();
  }, t('Node center views')));
  const deviceView = local.tab === 'devices';
  els.search.placeholder = t(deviceView ? 'Search devices or owners' : 'Search nodes');
  els.search.setAttribute('aria-label', els.search.placeholder);
  const all = deviceView ? devices() : local.tab === 'deleted' ? deleted : active;
  const shown = all.filter(item => matches(local.query, item.name, item.userName, item.protocol, item.sourceName));
  fill(els.list, shown.length ? shown.map(deviceView ? deviceRow : nodeRow) : data.loaded.controller
    ? empty(deviceView ? 'phone' : 'nodes', t(local.query ? 'No matching results' : deviceView ? 'No devices yet' : local.tab === 'deleted' ? 'No deleted nodes' : 'No nodes yet'),
      !deviceView && local.tab === 'nodes' && !local.query ? button(t('Import nodes'), {primary: true, iconName: 'plus', onClick: importNodes}) : null) : h('p', {class: 'quiet'}, t('Loading nodes…')));
  renderActions();
}

export default {
  id: 'nodes', title: t('Node center'), icon: 'nodes',
  mount(view, params, context) {
    ctx = context; lastKey = '';
    const search = input({class: 'input search', type: 'search', placeholder: t('Search nodes'), 'aria-label': t('Search nodes')});
    search.addEventListener('input', () => { local.query = search.value; render(); });
    els = {tabs: h('div', {class: 'page-tabs'}), search, selection: h('div', {class: 'controller-selection', hidden: true}), list: h('div', {class: 'controller-list'})};
    view.append(els.tabs, h('div', {class: 'toolbar'}, h('span', {class: 'search-wrap'}, icon('search'), search)), els.selection, els.list);
    ctx.setActions([button(t('Refresh'), {quiet: true, iconName: 'refresh', onClick: () => refreshController().catch(error => toast(error.message, 'error'))}),
      button(t('Import nodes'), {primary: true, iconName: 'plus', onClick: importNodes})]);
    render();
    timer = setInterval(render, 1000);
  },
  update() { render(); },
  unmount() { clearInterval(timer); timer = null; els = null; ctx = null; },
  reset() { local = {tab: 'nodes', query: '', selected: new Set()}; lastKey = ''; },
};
