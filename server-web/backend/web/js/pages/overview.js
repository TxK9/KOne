import {t, number, systemText, recordTitle, recordDetail} from '../i18n.js';
// Overview: paintings, the server skyline and items needing attention.

import {h, fill, ago} from '../core.js';
import {data} from '../store.js';
import {scenery, skyline} from '../ink.js';
import {seal, glyph, worse, serverWeather, routeWeather, customerWeather} from '../weather.js';
import {button, cultureNote} from '../widgets.js';
import {enrollServer} from './servers.js';

let els = null, ctx = null, lastKey = '';

/** The day's weather over everything: the worst of servers and routes, with what to say. */
function sky() {
  if (data.stale) return {key: 'wu', title: t('后台暂时连不上'), lines: []};
  const servers = data.fleet.servers.filter(s => !s.revoked);
  const live = servers.filter(s => s.registered);
  if (!live.length) {
    return {key: 'xie', title: servers.length ? t('等待服务器接入') : t('还没有服务器'), lines: []};
  }
  let key = 'qing';
  const lines = [];
  for (const server of live) {
    const weather = serverWeather(server, data.stale);
    key = worse(key, weather.key);
    if (weather.key !== 'qing') lines.push({key: weather.key, text: server.name + ' ' + weather.text});
  }
  for (const route of data.fleet.routes) {
    const weather = routeWeather(route, data.stale);
    if (weather.key === 'xie') continue;
    key = worse(key, weather.key);
    if (weather.key !== 'qing') lines.push({key: weather.key, text: route.name + ' ' + weather.text});
  }
  const order = {duan: 0, wu: 1, xie: 2, qing: 3};
  lines.sort((a, b) => order[a.key] - order[b.key]);
  const title = key === 'qing' ? t('一切正常') : lines[0].text;
  return {key, title, lines: lines.slice(1, 3)};
}

/** Things worth a look, worst first, each with where to go. */
function attention() {
  const items = [];
  for (const server of data.fleet.servers) {
    if (server.revoked) continue;
    const weather = serverWeather(server, data.stale);
    if (weather.key === 'qing') continue;
    items.push({key: weather.key, title: server.name, text: weather.text, go: '/servers/' + server.id});
  }
  for (const route of data.fleet.routes) {
    const weather = routeWeather(route, data.stale);
    if (weather.key === 'qing') continue;
    items.push({key: weather.key, title: route.name, text: weather.text, go: '/routes/' + route.id});
  }
  for (const user of data.customers.users) {
    const weather = customerWeather(user);
    const worth = user.revoked ? weather.text !== t('已收回') : ['expired', 'quota_reached', 'nodes_pending'].includes(user.accessState);
    if (worth) items.push({key: weather.key, title: user.name, text: weather.text, go: '/customers/' + user.id});
  }
  const order = {duan: 0, wu: 1, xie: 2, qing: 3};
  return items.sort((a, b) => order[a.key] - order[b.key]);
}

function tally() {
  const servers = data.fleet.servers.filter(s => s.registered && !s.revoked);
  const online = servers.filter(s => s.online).length;
  const routes = data.fleet.routes;
  const running = routes.filter(r => r.status === 'running').length;
  const people = data.customers.users;
  const devices = people.reduce((n, u) => n + (u.deviceCount || 0), 0);
  const cell = (go, value, of, label) => h('a', {class: 'tally-cell', href: '#' + go},
    h('strong', null, number(value), of != null ? h('small', null, ' / ' + number(of)) : null), h('span', null, label));
  return [
    cell('/servers', online, servers.length || null, t('服务器在线')),
    cell('/routes', running, routes.length || null, t('线路运行中')),
    cell('/customers', people.length, null, t('位客户')),
    cell('/customers', devices, null, t('台设备')),
  ];
}

function render() {
  const weather = sky();
  const items = attention();
  const records = data.state.records.slice(0, 6);
  const servers = data.fleet.servers.filter(s => !s.revoked);
  // Redraw only when something visible changed; the skyline keeps twinkling in between.
  const key = JSON.stringify([weather, items, records.map(r => r.id), servers.map(s => [s.id, s.name, serverWeather(s, data.stale).key, Math.round((s.metrics?.cpuPercent || 0) / 10)]),
    data.fleet.routes.length, data.customers.users.length, data.customers.users.reduce((n, u) => n + u.deviceCount, 0), data.loaded.fleet]);
  if (key === lastKey) return;
  lastKey = key;

  els.hero.dataset.wx = weather.key;
  fill(els.head,
    seal(weather.key, 'xl'),
    h('div', {class: 'hero-words'},
      h('h2', {class: 'hero-title'}, data.loaded.fleet ? weather.title : ''),
      weather.lines.length ? h('p', {class: 'hero-sub'}, weather.lines.map(line => h('span', null, glyph(line.key), line.text))) : null,
      data.loaded.fleet && !data.fleet.servers.length ? button(t('接入服务器'), {primary: true, iconName: 'plus', onClick: () => enrollServer(ctx)}) : null));
  fill(els.city, servers.length ? skyline(servers.map(server => ({server, weather: serverWeather(server, data.stale)})), {
    height: 170, slot: 104, onSelect: id => ctx.navigate('/servers/' + id),
  }) : null);
  fill(els.tally, tally());
  fill(els.attention, items.length ? [
    h('h3', {class: 'panel-title'}, t('留意')),
    h('ul', {class: 'notice-list'}, items.slice(0, 8).map(item => h('li', null, h('a', {class: 'notice wx-' + item.key, href: '#' + item.go},
      seal(item.key, 's'), h('strong', null, item.title), h('span', null, item.text))))),
  ] : null);
  els.attention.hidden = !items.length;
  fill(els.recent, records.length ? [
    h('h3', {class: 'panel-title'}, t('最近'), h('a', {class: 'panel-link', href: '#/records'}, t('全部记录'))),
    h('ul', {class: 'record-list compact'}, records.map(r => h('li', {class: 'record'},
      h('time', null, ago(r.time)), h('div', null, h('strong', null, recordTitle(r.title)), h('p', null, recordDetail(r)))))),
  ] : null);
  els.recent.hidden = !records.length;
}

export default {
  id: 'overview',
  title: t('总览'),
  icon: 'overview',
  mount(view, params, context) {
    ctx = context;
    lastKey = '';
    els = {
      hero: h('section', {class: 'hero'}),
      head: h('div', {class: 'hero-head'}),
      city: h('div', {class: 'hero-city'}),
      tally: h('nav', {class: 'tally', 'aria-label': t('概况')}),
      attention: h('section', {class: 'panel notices', hidden: true}),
      recent: h('section', {class: 'panel recent', hidden: true}),
    };
    els.hero.append(scenery('hero-scenery'), h('div', {class: 'hero-drift', 'aria-hidden': 'true'}), h('div', {class: 'hero-veil', 'aria-hidden': 'true'}),
      h('div', {class: 'hero-mist', 'aria-hidden': 'true'}), els.city, els.head);
    view.append(els.hero, cultureNote('overview-quote'), els.tally, h('div', {class: 'overview-grid'}, els.attention, els.recent));
    render();
  },
  update() { render(); },
  unmount() { els = null; },
};
