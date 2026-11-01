import {t, number, count} from '../i18n.js';
// 服务器: every server as a building. Pick one to see its load, protocols and routes.

import {h, s, fill, matches, ago, when, bytes, rate, duration, percent} from '../core.js';
import {post} from '../api.js';
import {data, refreshAll, refreshFleet, server as findServer, node as findNode} from '../store.js';
import {building, activity, towerIcon} from '../ink.js';
import {weatherTag, weatherLine, serverWeather, routeWeather, glyph} from '../weather.js';
import {icon} from '../icons.js';
import {sheet, toast, twoStep, moreMenu, field, input, select} from '../layer.js';
import {button, empty, gauge, facts, tabs, secret, countdown} from '../widgets.js';

const KIND = {vless: 'VLESS · TLS', hysteria2: 'Hysteria 2', socks: 'SOCKS5', http: 'HTTP CONNECT'};
const MODE = {systemd: t('系统服务'), process: t('后台进程'), stage: t('仅暂存'), unknown: '—'};
const FILTERS = [['all', t('全部')], ['online', t('在线')], ['offline', t('离线')], ['pending', t('待接入')], ['revoked', t('已撤销')]];

let els = null, ctx = null, local = {query: '', filter: 'all', selected: null}, lastList = '', lastDetail = '';

const kindOf = srv => (srv.revoked ? 'revoked' : !srv.registered ? 'pending' : srv.online ? 'online' : 'offline');

function shown() {
  return data.fleet.servers.filter(srv => (local.filter === 'all' || kindOf(srv) === local.filter)
    && matches(local.query, srv.name, srv.address, srv.os, srv.metrics?.osName, srv.metrics?.hostname));
}

function filterChanged() {
  const list = shown();
  if (!list.some(srv => srv.id === local.selected)) {
    local.selected = window.innerWidth > 980 ? list[0]?.id || null : null;
    ctx.navigate('/servers' + (local.selected ? '/' + local.selected : ''));
  }
  render();
}

// ---- Enrolling ---------------------------------------------------------------------------------

/** The install ticket: a one-time credential and the command to run on the VPS. */
function ticket(result, address) {
  const origin = data.fleet.installOrigin || (location.protocol === 'https:' ? location.origin : null);
  const quote = v => "'" + String(v).replace(/'/g, "'\\''") + "'";
  const command = origin ? 'curl --fail --proto "=https" ' + quote(origin + '/downloads/install-node.sh') + ' -o /tmp/kone-install.sh && sudo bash /tmp/kone-install.sh ' + quote(origin) + ' ' + quote(address) : null;
  return h('div', {class: 'ticket'},
    h('div', {class: 'ticket-row'}, h('span', {class: 'ticket-label'}, t('一次性凭据')), countdown(Date.now() + (result.expiresIn || 900) * 1000)),
    secret(result.token),
    command ? [h('span', {class: 'ticket-label'}, t('在 VPS 上运行')), secret(command, {masked: false, label: t('复制命令')})]
      : h('p', {class: 'note warn'}, t('还没有可供 VPS 访问的 HTTPS 入口，请先配置节点入口。')),
    h('a', {class: 'text-link', href: '/downloads/install-node.sh', download: 'install-node.sh'}, icon('download'), t('下载安装脚本')));
}

export function enrollServer(context) {
  const name = input({maxlength: '80', placeholder: t('例如 洛杉矶 1')});
  const address = input({maxlength: '253', placeholder: t('IP 或域名'), class: 'input mono'});
  sheet({
    title: t('接入服务器'),
    body: [field(t('名称'), name), field(t('连接地址'), address)],
    actions: [{text: t('取消')}, {text: t('生成凭据'), primary: true, run: async api => {
      const result = await post('/api/fleet/enrollments', {name: name.value.trim(), address: address.value.trim()});
      local.selected = result.id;
      await refreshAll();
      fill(api.content, ticket(result, address.value.trim()));
      api.setActions([{text: t('完成'), primary: true}]);
      context?.navigate('/servers/' + result.id);
      return false;
    }}],
  });
}

function renew(srv) {
  post('/api/fleet/servers/' + srv.id + '/renew', {}).then(result => {
    refreshFleet().catch(() => {});
    sheet({title: t('接入凭据'), kicker: srv.name, body: ticket(result, srv.address), actions: [{text: t('完成'), primary: true}]});
  }).catch(e => toast(e.message, 'error'));
}

function reinstall(srv) {
  const address = input({value: srv.address, class: 'input mono', maxlength: '253'});
  sheet({
    title: t('重装后重新接入'), kicker: srv.name,
    body: [h('p', {class: 'note'}, t('旧的管理凭据会失效；协议、线路和客户都保留，证书重建后重新下发，客户需要更新配置。')), field(t('连接地址'), address)],
    actions: [{text: t('取消')}, {text: t('生成凭据'), primary: true, run: async api => {
      const result = await post('/api/fleet/servers/' + srv.id + '/reinstall', {address: address.value.trim()});
      await refreshAll();
      fill(api.content, ticket(result, address.value.trim()));
      api.setActions([{text: t('完成'), primary: true}]);
      return false;
    }}],
  });
}

function revoke(srv) {
  sheet({
    title: t('撤销接入'), kicker: srv.name,
    body: h('p', {class: 'note'}, t('这台服务器不再接受管理。已在运行的代理服务不会因此停止。')),
    actions: [{text: t('取消')}, {text: t('撤销接入'), danger: true, run: async () => {
      await post('/api/fleet/servers/' + srv.id + '/revoke', {});
      await refreshAll();
      toast(t('已撤销接入'));
    }}],
  });
}

function deploy(srv) {
  const name = input({value: srv.name + t(' · 节点'), maxlength: '80'});
  const kind = select([{value: 'vless', text: 'VLESS · TLS'}, {value: 'hysteria2', text: 'Hysteria 2'}], 'vless');
  const port = input({type: 'number', value: '443', min: '1', max: '65535', class: 'input mono'});
  sheet({
    title: t('部署协议'), kicker: srv.name,
    body: [field(t('名称'), name), h('div', {class: 'field-pair'}, field(t('协议'), kind), field(t('端口'), port))],
    actions: [{text: t('取消')}, {text: t('部署'), primary: true, run: async () => {
      await post('/api/fleet/nodes', {name: name.value.trim(), serverId: srv.id, kind: kind.value, port: Number(port.value)});
      await refreshAll();
      toast(t('已排队，等服务器执行'));
    }}],
  });
}

// ---- List --------------------------------------------------------------------------------------

function row(srv) {
  const weather = serverWeather(srv, data.stale);
  const m = srv.metrics || {};
  const load = srv.registered && !srv.revoked && srv.metricsAt
    ? h('small', {class: 'row-load'}, 'CPU ' + percent(m.cpuPercent), h('i'), t('内存 ') + percent(m.memoryPercent))
    : null;
  return h('a', {class: 'row server-row' + (srv.id === local.selected ? ' is-selected' : ''), href: '#/servers/' + srv.id, 'aria-current': srv.id === local.selected ? 'true' : null},
    h('span', {class: 'row-art'}, towerIcon(srv, weather.key, 26)),
    h('span', {class: 'row-main'}, h('strong', null, srv.name), h('small', {class: 'mono'}, srv.address)),
    h('span', {class: 'row-side'}, weatherLine(weather), load));
}

function renderList() {
  const list = shown();
  const counts = Object.fromEntries(FILTERS.map(([id]) => [id, id === 'all' ? data.fleet.servers.length : data.fleet.servers.filter(srv => kindOf(srv) === id).length]));
  const key = JSON.stringify([local, list.map(srv => [srv.id, srv.name, srv.address, serverWeather(srv, data.stale), Math.round(srv.metrics?.cpuPercent || 0), Math.round(srv.metrics?.memoryPercent || 0)]), counts, data.loaded.fleet]);
  if (key === lastList) return;
  lastList = key;
  fill(els.filters, tabs(FILTERS.filter(([id]) => id === 'all' || counts[id]).map(([id, text]) => ({id, text, count: counts[id]})), local.filter, id => {
    local.filter = id;
    filterChanged();
  }, t('筛选服务器')));
  els.tools.hidden = data.fleet.servers.length < 2;
  fill(els.list, list.length ? list.map(row)
    : data.fleet.servers.length ? empty('search', t('没有匹配的服务器'), button(t('清除筛选'), {quiet: true, onClick: () => { local.query = ''; local.filter = 'all'; els.search.value = ''; render(); }}))
      : data.loaded.fleet ? empty('servers', t('还没有服务器'), button(t('接入服务器'), {primary: true, iconName: 'plus', onClick: () => enrollServer(ctx)})) : null);
}

// ---- Detail ------------------------------------------------------------------------------------

function nodeRow(n, srv) {
  const live = srv.status === 'running' && srv.online && n.enabled && !data.stale;
  const state = !n.enabled ? {key: 'xie', text: t('已停用')} : live ? {key: 'qing', text: t('运行中')} : serverWeather(srv, data.stale);
  const toggle = h('button', {type: 'button', class: 'button button-quiet', disabled: srv.revoked}, icon(n.enabled ? 'pause' : 'play'), n.enabled ? t('停用') : t('启用'));
  twoStep(toggle, {ask: n.enabled ? t('确认停用') : t('确认启用'), run: async () => {
    try {
      await post('/api/fleet/nodes/' + n.id + '/toggle', {enabled: !n.enabled});
      await refreshAll();
      toast(t('已排队，等服务器执行'));
    } catch (e) { toast(e.message, 'error'); }
  }});
  return h('li', {class: 'proto'},
    h('span', {class: 'proto-icon'}, icon(n.kind === 'hysteria2' ? 'bolt' : 'shield')),
    h('span', {class: 'proto-main'}, h('strong', null, n.name), h('small', null, KIND[n.kind] || n.kind, ' · ', n.kind === 'vless' ? 'TCP' : 'UDP', ' ', h('span', {class: 'mono'}, String(n.port)))),
    weatherLine(state), toggle);
}

function routeChips(srv) {
  const linked = data.fleet.routes.filter(r => r.nodeIds.some(id => findNode(id)?.serverId === srv.id));
  if (!linked.length) return null;
  return h('section', {class: 'detail-section'},
    h('h3', {class: 'section-title'}, t('线路')),
    h('div', {class: 'chips'}, linked.map(r => h('a', {class: 'chip', href: '#/routes/' + r.id}, glyph(routeWeather(r, data.stale).key), r.name))));
}

// The detail is drawn in parts, each redrawn only when its own facts change, so a menu left open
// or a button half-way through "确认停用" survives the five-second refresh.
const parts = {id: null, keys: {}};

const SLOTS = ['head', 'resource', 'nodes', 'routes'];

function section(name, key, draw) {
  const slot = els.detail.querySelector('[data-slot="' + name + '"]');
  if (!slot || parts.keys[name] === key) return;
  parts.keys[name] = key;
  fill(slot, draw());
}

function headPart(srv, weather) {
  const m = srv.metrics || {};
  const look = srv.revoked ? 'ghost' : !srv.registered ? 'site' : weather.key === 'duan' ? 'dark' : 'lit';
  const art = s('svg', {class: 'detail-art', viewBox: '-10 -30 80 150', 'aria-hidden': 'true'},
    building(srv.id, {x: 0, base: 118, width: 60, height: 104, look, lit: activity(srv), fog: weather.key === 'wu'}));
  const menu = moreMenu([
    srv.registered ? {text: t('重新下发配置'), disabled: srv.revoked, run: () => post('/api/fleet/servers/' + srv.id + '/retry', {}).then(() => { refreshAll(); toast(t('已重新下发')); }).catch(e => toast(e.message, 'error'))} : null,
    srv.registered ? {text: t('重装后重新接入'), disabled: srv.revoked, run: () => reinstall(srv)} : {text: t('重新生成凭据'), disabled: srv.revoked, run: () => renew(srv)},
    {text: t('撤销接入'), danger: true, disabled: srv.revoked, run: () => revoke(srv)},
  ], t('服务器操作'));
  return h('header', {class: 'detail-head'},
    h('a', {class: 'back phone-only', href: '#/servers'}, icon('left'), t('服务器')),
    art,
    h('div', {class: 'detail-title'},
      h('h2', null, srv.name),
      h('p', {class: 'mono'}, srv.address),
      h('p', {class: 'detail-sub'}, [m.osName || srv.os, srv.arch, srv.registered ? MODE[srv.mode] || t('未知') : null].filter(Boolean).join(' · ') || t('等待接入')),
      h('div', {class: 'detail-state'}, weatherTag(weather))),
    h('div', {class: 'detail-actions'},
      srv.registered && !srv.revoked ? button(t('部署协议'), {primary: true, iconName: 'plus', onClick: () => deploy(srv)}) : null,
      !srv.registered && !srv.revoked ? button(t('接入凭据'), {primary: true, iconName: 'key', onClick: () => renew(srv)}) : null,
      menu));
}

function resourcePart(srv) {
  if (!srv.registered || srv.revoked) {
    if (srv.revoked) return null;
    return h('section', {class: 'detail-section'},
      h('div', {class: 'inline-empty'}, h('span', null, t('在 VPS 上运行安装命令后，这里会出现它的资源和协议。')), button(t('接入凭据'), {quiet: true, iconName: 'key', onClick: () => renew(srv)})));
  }
  const m = srv.metrics || {};
  const fresh = srv.metricsFresh && !data.stale;
  return h('section', {class: 'detail-section'},
    h('h3', {class: 'section-title'}, t('资源'), h('span', {class: 'quiet'}, !srv.metricsAt ? t('等待上报') : (fresh ? '' : t('上次 ')) + ago(srv.metricsAt))),
    h('div', {class: 'gauges' + (fresh ? '' : ' is-stale')},
      gauge('CPU', m.cpuPercent, m.cpuCores ? count(m.cpuCores, 'core') : null),
      gauge(t('内存'), m.memoryPercent, Number.isFinite(m.memoryTotal) ? bytes(m.memoryUsed) + ' / ' + bytes(m.memoryTotal) : null),
      gauge(t('系统盘'), m.diskPercent, Number.isFinite(m.diskTotal) ? bytes(m.diskUsed) + ' / ' + bytes(m.diskTotal) : null)),
    facts([
      [t('下载'), rate(m.downloadRate)],
      [t('上传'), rate(m.uploadRate)],
      [t('运行时间'), Number.isFinite(m.uptime) ? duration(m.uptime) : null],
      [t('负载'), Array.isArray(m.load) ? m.load.map(v => v.toFixed(2)).join('  ') : null],
      [t('主机名'), m.hostname ? h('span', {class: 'mono'}, m.hostname) : null],
      [t('处理器'), m.cpuModel],
      [t('网卡累计'), Number.isFinite(m.networkReceived) ? '↓ ' + bytes(m.networkReceived) + '  ↑ ' + bytes(m.networkSent) : null],
      ['Agent', m.agentVersion ? h('span', {class: 'mono'}, m.agentVersion) : null],
    ], 'facts-grid'));
}

function nodesPart(srv, nodes) {
  if (!srv.registered || srv.revoked && !nodes.length) return null;
  return h('section', {class: 'detail-section'},
    h('h3', {class: 'section-title'}, t('协议')),
    nodes.length ? h('ul', {class: 'proto-list'}, nodes.map(n => nodeRow(n, srv)))
      : h('div', {class: 'inline-empty'}, h('span', null, t('还没有部署协议')), button(t('部署协议'), {quiet: true, iconName: 'plus', onClick: () => deploy(srv)})));
}

function renderDetail() {
  const srv = findServer(local.selected);
  if (!srv) {
    parts.id = null;
    parts.keys = {};
    const blank = data.fleet.servers.length ? h('div', {class: 'detail-blank'}, icon('servers'), h('p', null, t('选一台服务器'))) : null;
    const key = 'blank:' + data.fleet.servers.length;
    if (lastDetail !== key) { lastDetail = key; fill(els.detail, blank); }
    return;
  }
  const weather = serverWeather(srv, data.stale);
  if (parts.id !== srv.id || lastDetail !== srv.id) {
    parts.id = srv.id;
    parts.keys = {};
    lastDetail = srv.id;
    fill(els.detail, h('article', {class: 'detail'}, SLOTS.map(name => h('div', {class: 'slot', 'data-slot': name}))));
  }
  els.detail.firstChild.className = 'detail wx-' + weather.key;
  const m = srv.metrics || {};
  const nodes = data.fleet.nodes.filter(n => n.serverId === srv.id);
  section('head', JSON.stringify([srv.name, srv.address, srv.os, srv.arch, srv.mode, srv.registered, srv.revoked, m.osName, weather, Math.round((m.cpuPercent || 0) / 20)]), () => headPart(srv, weather));
  section('resource', JSON.stringify([srv.registered, srv.revoked, m, srv.metricsAt, srv.metricsFresh, data.stale, Math.floor(Date.now() / 60000)]), () => resourcePart(srv));
  section('nodes', JSON.stringify([nodes, srv.status, srv.online, srv.revoked, srv.registered, data.stale]), () => nodesPart(srv, nodes));
  section('routes', JSON.stringify(data.fleet.routes.map(r => [r.id, r.name, r.status, r.nodeIds]).concat([data.stale])), () => routeChips(srv));
}

function render() {
  if (!els) return;
  renderList();
  renderDetail();
  els.page.classList.toggle('has-selection', Boolean(findServer(local.selected)));
}

export default {
  id: 'servers',
  title: t('服务器'),
  icon: 'servers',
  mount(view, params, context) {
    ctx = context;
    lastList = lastDetail = '';
    local.selected = params[0] || local.selected;
    const search = h('input', {class: 'input search', type: 'search', placeholder: t('名称、地址、系统'), 'aria-label': t('搜索服务器'), value: local.query, autocomplete: 'off', spellcheck: 'false'});
    search.addEventListener('input', () => { local.query = search.value; filterChanged(); });
    els = {
      search,
      filters: h('div', {class: 'filters'}),
      list: h('div', {class: 'list', role: 'list'}),
      detail: h('div', {class: 'detail-wrap'}),
    };
    els.tools = h('div', {class: 'toolbar'}, h('label', {class: 'search-wrap'}, icon('search'), search), els.filters);
    els.page = h('div', {class: 'split'}, h('div', {class: 'split-list'}, els.tools, els.list), els.detail);
    view.append(els.page);
    ctx.setActions([button(t('接入服务器'), {primary: true, iconName: 'plus', onClick: () => enrollServer(ctx)})]);
    if (!local.selected && window.innerWidth > 980) local.selected = shown()[0]?.id || null;
    render();
  },
  update(params) {
    if (params[0] && params[0] !== local.selected) local.selected = params[0];
    if (!params[0] && window.innerWidth <= 980) local.selected = null;
    if (!local.selected && window.innerWidth > 980) local.selected = shown()[0]?.id || null;
    render();
  },
  unmount() { els = null; },
};
