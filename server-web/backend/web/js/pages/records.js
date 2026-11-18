import {t, number, count, systemText, recordTitle, recordDetail, timeText} from '../i18n.js';
// Records: daily actions and server results.

import {h, fill, when, day, toDate} from '../core.js';
import {data, server as findServer} from '../store.js';
import {weatherLine, jobWeather} from '../weather.js';
import {empty, tabs} from '../widgets.js';

const PAGE = 60;
let els = null, lastKey = '', local = {tab: 'actions', shown: PAGE};

const two = n => String(n).padStart(2, '0');
const clock = value => { const d = toDate(value); return d ? timeText(d) : '—'; };

function byDay(items, timeOf, draw) {
  const blocks = [];
  let current = null;
  for (const item of items) {
    const label = day(timeOf(item));
    if (!current || current.label !== label) { current = {label, items: []}; blocks.push(current); }
    current.items.push(item);
  }
  return blocks.map(block => h('section', {class: 'day'}, h('h3', {class: 'day-title'}, block.label), h('ul', {class: 'record-list'}, block.items.map(draw))));
}

function actionRow(r) {
  const tone = /失败|未通过/.test(r.result) ? 'bad' : /等待|未分配|未同步|仅暂存/.test(r.result) ? 'wait' : '';
  return h('li', {class: 'record'},
    h('time', null, clock(r.time)),
    h('div', null, h('strong', null, recordTitle(r.title)), r.detail ? h('p', null, recordDetail(r)) : null),
    r.result && r.result !== '完成' ? h('span', {class: 'pill' + (tone ? ' pill-' + tone : '')}, systemText(r.result)) : null);
}

function jobRow(j) {
  const srv = findServer(j.serverId);
  return h('li', {class: 'record'},
    h('time', null, clock(j.created)),
    h('div', null, h('strong', null, (srv?.name || t('服务器')) + ' · ' + t('第 {n} 版', {n: number(j.revision)})), h('p', null, (j.message ? systemText(j.message, '系统消息不可用') : '') || (j.finished ? t('完成于 ') + when(j.finished) : ''))),
    weatherLine(jobWeather(j)));
}

function render() {
  if (!els) return;
  const records = data.state.records, jobs = data.fleet.jobs;
  const key = JSON.stringify([local, records.length && records[0].id, records.length, jobs.map(j => [j.id, j.status]), data.loaded.state]);
  if (key === lastKey) return;
  lastKey = key;
  fill(els.tabs, tabs([{id: 'actions', text: t('操作'), count: records.length}, {id: 'jobs', text: t('部署'), count: jobs.length}], local.tab, id => { local.tab = id; local.shown = PAGE; render(); }, t('记录类型')));
  const items = local.tab === 'actions' ? records : jobs;
  const list = items.slice(0, local.shown);
  fill(els.list, list.length
    ? [byDay(list, item => (local.tab === 'actions' ? item.time : item.created), local.tab === 'actions' ? actionRow : jobRow),
      items.length > local.shown ? h('button', {type: 'button', class: 'button button-quiet more-button', onClick: () => { local.shown += PAGE; render(); }}, t('更早的')) : null]
    : data.loaded.state ? empty('records', local.tab === 'actions' ? t('还没有记录') : t('还没有部署任务'), null) : null);
}

export default {
  id: 'records',
  title: t('记录'),
  icon: 'records',
  mount(view) {
    lastKey = '';
    local.shown = PAGE;
    els = {tabs: h('div', {class: 'page-tabs'}), list: h('div', {class: 'records'})};
    view.append(els.tabs, els.list);
    render();
  },
  update() { render(); },
  unmount() { els = null; },
};
