import {t, number, count, listText, groupName} from '../i18n.js';
// Routes: bridges between towers and their exits.

import {h, fill, when} from '../core.js';
import {post} from '../api.js';
import {data, refreshAll, server as findServer, node as findNode, exit as findExit, customer as findCustomer, routeOf} from '../store.js';
import {bridge} from '../ink.js';
import {seal, weatherLine, routeWeather, serverWeather} from '../weather.js';
import {icon} from '../icons.js';
import {sheet, toast, field, input, select} from '../layer.js';
import {button, empty, userPicker, facts} from '../widgets.js';

const KIND = {vless: 'VLESS · TLS', hysteria2: 'Hysteria 2', socks: 'SOCKS5', http: 'HTTP CONNECT'};
const PROBE = {pending: t('检测中'), failed: t('检测失败'), stale: t('结果已过期'), unverified: t('未检测'), verified: t('已检测')};

let els = null, ctx = null, focus = null, lastKey = '';

function stations(route) {
  return route.nodeIds.map(id => {
    const n = findNode(id);
    const srv = n && findServer(n.serverId);
    const weather = srv ? serverWeather(srv, data.stale) : {key: 'duan'};
    return {
      id: srv?.id || null,
      name: srv?.name || n?.name || t('节点不可用'),
      sub: n ? KIND[n.kind] : '',
      look: !srv ? 'ghost' : srv.revoked ? 'ghost' : weather.key === 'duan' ? 'dark' : 'lit',
    };
  });
}

function exitOf(route) {
  if (!route.exitId) return {name: t('直接出口'), sub: t('最后一台出网'), external: false};
  const e = findExit(route.exitId);
  return {name: e?.name || t('外部出口'), sub: e ? KIND[e.kind] + (e.tls ? ' · TLS' : '') : t('不可用'), external: true};
}

function assign(route) {
  if (!data.state.users.length) { toast(t('先在客户页添加客户')); ctx.navigate('/customers'); return; }
  const people = data.state.users.map(u => {
    const summary = findCustomer(u.id);
    const on = routeOf(u.id);
    return {
      id: u.id, name: u.name,
      note: summary?.revoked ? t('服务已收回') : on ? (on.id === route.id ? t('已在这条线路') : t('在 ') + on.name) : groupName(summary?.groupName, summary?.groupId),
      disabled: Boolean(summary?.revoked) || on?.id === route.id,
    };
  });
  const picker = userPicker(people);
  sheet({
    title: t('分配客户'), kicker: route.name,
    body: [picker.node, h('p', {class: 'note'}, t('每位客户会得到自己的节点凭据；线路上的服务器都应用后，配置才会生成。'))],
    actions: [{text: t('取消')}, {text: t('分配'), primary: true, run: async () => {
      if (!picker.chosen.size) throw new Error(t('请选择客户'));
      await post('/api/fleet/assignments', {routeId: route.id, userIds: [...picker.chosen]});
      await refreshAll();
      toast(t('已分配，等服务器应用'));
    }}],
  });
}

async function probe(route, trigger) {
  trigger.disabled = true;
  try {
    await post('/api/fleet/routes/' + route.id + '/probe', {});
    await refreshAll();
    toast(t('出口检测已排队'));
  } catch (e) {
    toast(e.message, 'error');
    trigger.disabled = false;
  }
}

export function addRoute() {
  const usable = data.fleet.nodes.filter(n => n.enabled && !findServer(n.serverId)?.revoked);
  if (!usable.length) { toast(t('先接入服务器并部署协议'), 'error'); ctx?.navigate('/servers'); return; }
  const label = n => (findServer(n.serverId)?.name || n.name) + ' · ' + KIND[n.kind];
  const nodeOptions = usable.map(n => ({value: n.id, text: label(n)}));
  const name = input({maxlength: '80', placeholder: t('例如 美国 · 直连')});
  const entry = select(nodeOptions, usable[0].id);
  const middle = select([{value: '', text: t('不经过中转')}, ...nodeOptions], '');
  const end = select([
    {value: '', text: t('由最后一台直接出网')},
    {group: t('再经过一台服务器'), options: usable.map(n => ({value: 'node:' + n.id, text: label(n)}))},
    ...(data.fleet.exits.length ? [{group: t('外部代理'), options: data.fleet.exits.map(e => ({value: 'exit:' + e.id, text: e.name + ' · ' + KIND[e.kind]}))}] : []),
  ], '');
  sheet({
    title: t('新增线路'),
    body: [field(t('名称'), name), field(t('入口'), entry), field(t('中转'), middle), field(t('出口'), end)],
    actions: [{text: t('取消')}, {text: t('保存线路'), primary: true, run: async () => {
      const ids = [entry.value];
      if (middle.value) ids.push(middle.value);
      if (end.value.startsWith('node:')) ids.push(end.value.slice(5));
      await post('/api/fleet/routes', {name: name.value.trim(), nodeIds: ids, exitId: end.value.startsWith('exit:') ? end.value.slice(5) : null});
      await refreshAll();
      toast(t('线路已保存'));
    }}],
  });
}

export function addExit() {
  const name = input({maxlength: '80'});
  const kind = select([{value: 'socks', text: 'SOCKS5'}, {value: 'http', text: 'HTTP CONNECT'}, {value: 'https', text: 'HTTPS CONNECT'}], 'socks');
  const address = input({class: 'input mono', placeholder: t('IP 或域名'), maxlength: '253'});
  const port = input({type: 'number', class: 'input mono', value: '1080', min: '1', max: '65535'});
  const username = input({placeholder: t('可不填'), maxlength: '1024'});
  const password = input({type: 'password', placeholder: t('可不填'), maxlength: '1024', autocomplete: 'new-password'});
  sheet({
    title: t('添加外部出口'),
    body: [field(t('名称'), name), field(t('类型'), kind), h('div', {class: 'field-pair'}, field(t('地址'), address), field(t('端口'), port)), h('div', {class: 'field-pair'}, field(t('用户名'), username), field(t('密码'), password))],
    actions: [{text: t('取消')}, {text: t('保存'), primary: true, run: async () => {
      await post('/api/fleet/exits', {
        name: name.value.trim(), kind: kind.value === 'https' ? 'http' : kind.value, tls: kind.value === 'https',
        address: address.value.trim(), port: Number(port.value), username: username.value, password: password.value,
      });
      await refreshAll();
      toast(t('出口已保存'));
    }}],
  });
}

function card(route) {
  const weather = routeWeather(route, data.stale);
  const ready = route.assignments.filter(a => a.status === 'ready').length;
  const probeState = route.probe || {status: 'unverified'};
  const people = route.assignments.map(a => findCustomer(a.userId)?.name).filter(Boolean);
  const probeButton = h('button', {type: 'button', class: 'text-button', disabled: route.status !== 'running' || probeState.status === 'pending'}, icon('globe'), probeState.status === 'pending' ? t('检测中') : t('检测'));
  probeButton.addEventListener('click', () => probe(route, probeButton));
  const exitLine = h('span', {class: 'route-probe'},
    probeState.ip ? [h('span', {class: 'mono'}, probeState.ip), h('small', {class: 'quiet'}, (probeState.status === 'verified' ? '' : (PROBE[probeState.status] || t('未知')) + ' · ') + when(probeState.checked))]
      : h('span', {class: 'quiet'}, PROBE[probeState.status] || t('未知')),
    probeButton);
  return h('article', {class: 'route-card' + (focus === route.id ? ' is-focused' : ''), id: 'route-' + route.id},
    h('header', {class: 'route-head'},
      seal(weather.key),
      h('div', {class: 'route-title'}, h('h2', null, route.name), weatherLine(weather)),
      h('div', {class: 'route-actions'}, button(t('分配客户'), {quiet: true, iconName: 'customers', disabled: route.status === 'blocked', onClick: () => assign(route)}))),
    h('div', {class: 'route-body'},
      h('div', {class: 'route-art'}, bridge(stations(route), exitOf(route), weather.key, {onStation: id => ctx.navigate('/servers/' + id)})),
      facts([
        [t('客户'), route.assignments.length ? h('span', null, count(route.assignments.length, 'customer'), ' · ', h('span', {class: 'quiet'}, listText(people.slice(0, 4)) + (people.length > 4 ? t(' 等') : ''))) : h('span', {class: 'quiet'}, t('还没有'))],
        route.assignments.length ? [t('配置'), t('{ready} / {total} 份已生成', {ready: number(ready), total: number(route.assignments.length)})] : null,
        [t('出口'), exitLine],
        route.tcpOnly ? [t('协议'), t('仅 TCP')] : null,
      ], 'route-facts')));
}

function exitRow(e) {
  const used = data.fleet.routes.filter(r => r.exitId === e.id).length;
  return h('li', {class: 'exit-row'},
    h('span', {class: 'proto-icon'}, icon('globe')),
    h('span', {class: 'proto-main'}, h('strong', null, e.name), h('small', null, KIND[e.kind] + (e.tls ? ' · TLS' : ''), ' · ', h('span', {class: 'mono'}, e.address + ':' + e.port))),
    h('small', {class: 'quiet'}, used ? count(used, 'route') : t('未使用')));
}

function render() {
  if (!els) return;
  const key = JSON.stringify([data.fleet.routes, data.fleet.exits, data.fleet.nodes, data.fleet.servers.map(srv => [srv.id, srv.name, serverWeather(srv, data.stale).key]), data.customers.users.map(u => [u.id, u.name]), focus, data.stale, data.loaded.fleet]);
  if (key === lastKey) return;
  lastKey = key;
  const routes = data.fleet.routes;
  fill(els.routes, routes.length ? routes.map(card)
    : data.loaded.fleet ? empty('routes', data.fleet.nodes.length ? t('还没有线路') : t('先接入服务器、部署协议，再连成线路'),
      data.fleet.nodes.length ? button(t('新增线路'), {primary: true, iconName: 'plus', onClick: addRoute}) : button(t('去接入服务器'), {quiet: true, onClick: () => ctx.navigate('/servers')})) : null);
  fill(els.exits, data.fleet.exits.length || routes.length ? [
    h('h3', {class: 'section-title'}, t('外部出口'), button(t('添加'), {quiet: true, iconName: 'plus', onClick: addExit})),
    data.fleet.exits.length ? h('ul', {class: 'exit-list'}, data.fleet.exits.map(exitRow)) : h('p', {class: 'quiet'}, t('线路可以经由别处的 SOCKS5 或 HTTP 代理出网。')),
  ] : null);
  if (focus) {
    const target = document.getElementById('route-' + focus);
    if (target) requestAnimationFrame(() => target.scrollIntoView({block: 'nearest', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'}));
  }
}

export default {
  id: 'routes',
  title: t('线路'),
  icon: 'routes',
  mount(view, params, context) {
    ctx = context;
    focus = params[0] || null;
    lastKey = '';
    els = {routes: h('div', {class: 'route-list'}), exits: h('section', {class: 'panel exits'})};
    view.append(els.routes, els.exits);
    ctx.setActions([
      button(t('添加出口'), {quiet: true, iconName: 'globe', onClick: addExit}),
      button(t('新增线路'), {primary: true, iconName: 'plus', onClick: addRoute}),
    ]);
    render();
  },
  update(params) {
    focus = params[0] || null;
    render();
  },
  unmount() { els = null; },
};
