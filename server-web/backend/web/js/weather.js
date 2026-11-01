import {t} from './i18n.js';
// 天气印: health told as weather, the same seals as KOne on the phone.
// 晴 works, 雾 is on its way or slow, 断 is down, 歇 is resting (not connected, not started).

import {h} from './core.js';

export const WX = {
  qing: {char: t('晴'), word: t('正常')},
  wu: {char: t('雾'), word: t('偏慢')},
  duan: {char: t('断'), word: t('不通')},
  xie: {char: t('歇'), word: t('未连接')},
};

const GLYPHS = {
  qing: '<circle cx="12" cy="12" r="4.2"/><path d="M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M5.5 18.5l1.7-1.7M16.8 7.2l1.7-1.7"/>',
  wu: '<path d="M3.5 8.5c2-1.3 4-1.3 6 0s4 1.3 6 0 3.5-1.1 5-.5"/><path d="M3 12.5c2-1.3 4-1.3 6 0s4 1.3 6 0 4-1.1 6-.4"/><path d="M5.5 16.5c1.7-1.1 3.4-1.1 5.1 0s3.4 1.1 5.1 0 2.6-.8 3.8-.3"/>',
  duan: '<path d="M7.3 15.2h-.6a3.6 3.6 0 0 1-.3-7.2 5.2 5.2 0 0 1 10-1 3.8 3.8 0 0 1 1.3 7.4"/><path d="M12.8 11.2l-2.5 4.1h3.4l-2.3 4.2"/>',
  xie: '<path d="M18.8 14.6A7.4 7.4 0 0 1 9.4 5.2a7.4 7.4 0 1 0 9.4 9.4z"/><path d="M17.8 3.6v2.6M16.5 4.9h2.6"/>',
};

const RANK = {qing: 0, xie: 1, wu: 2, duan: 3};

/** The worse of two weathers: 断 over 雾 over 歇 over 晴. */
export const worse = (a, b) => (RANK[b] > RANK[a] ? b : a);

/** The seal: the character cut into a square of the pigment. */
export function seal(key, size = '') {
  return h('span', {class: 'seal wx-' + key + (size ? ' seal-' + size : ''), role: 'img', 'aria-label': WX[key].char + ' · ' + WX[key].word}, WX[key].char);
}

/** The weather drawn in ink. */
export function glyph(key) {
  const span = h('span', {class: 'glyph wx-' + key, 'aria-hidden': 'true'});
  span.innerHTML = '<svg viewBox="0 0 24 24">' + GLYPHS[key] + '</svg>';
  return span;
}

/** Seal, glyph and a few words: the usual way to show a state. */
export function weatherTag(state, options = {}) {
  return h('span', {class: 'wx wx-' + state.key + (options.quiet ? ' wx-quiet' : '')},
    options.noSeal ? null : seal(state.key, options.size),
    glyph(state.key),
    state.text ? h('span', {class: 'wx-text'}, state.text) : null);
}

/** Glyph and words, no seal: for lists, where a column of seals would be too loud. */
export const weatherLine = state => weatherTag(state, {noSeal: true});

// ---- What each thing's weather is ----------------------------------------------------------

const busy = new Set(['pending', 'queued', 'leased']);

export function serverWeather(server, stale = false) {
  if (server.revoked) return {key: 'xie', text: t('已撤销')};
  if (!server.registered) return {key: 'xie', text: t('待接入')};
  if (stale) return {key: 'wu', text: t('待确认')};
  if (!server.online) return {key: 'duan', text: t('离线')};
  if (server.status === 'failed') return {key: 'duan', text: t('部署失败')};
  if (server.status === 'stopped') return {key: 'duan', text: t('服务未运行')};
  if (busy.has(server.status) || server.desired !== server.applied) return {key: 'wu', text: t('正在部署')};
  if (server.status === 'staged') return {key: 'wu', text: t('仅暂存')};
  if (!server.metricsFresh) return {key: 'wu', text: t('等待上报')};
  const m = server.metrics || {};
  if (m.cpuPercent > 90 || m.memoryPercent > 92 || m.diskPercent > 92) return {key: 'wu', text: t('负载高')};
  return {key: 'qing', text: server.status === 'running' ? t('运行中') : t('在线')};
}

export function routeWeather(route, stale = false) {
  if (stale) return {key: 'wu', text: t('待确认')};
  switch (route.status) {
    case 'running': return {key: 'qing', text: t('运行中')};
    case 'pending': return {key: 'wu', text: t('等待部署')};
    case 'offline': return {key: 'duan', text: t('服务器离线')};
    case 'blocked': return {key: 'duan', text: t('服务器已撤销')};
    case 'paused': return {key: 'xie', text: t('已停用')};
    default: return {key: 'xie', text: t('未知')};
  }
}

export function customerWeather(user) {
  if (user.revoked) {
    const control = user.nodeControl || {};
    if (control.status === 'pending') return {key: 'duan', text: t('已收回 · 等节点确认')};
    if (control.status === 'failed') return {key: 'duan', text: t('已收回 · 节点未执行')};
    return {key: 'duan', text: t('已收回')};
  }
  switch (user.accessState) {
    case 'paused': return {key: 'xie', text: t('已暂停')};
    case 'expired': return {key: 'duan', text: t('已到期')};
    case 'quota_reached': return {key: 'duan', text: t('额度用完')};
    case 'nodes_pending': return {key: 'wu', text: t('等待节点')};
    default: break;
  }
  if (user.appOnly) return user.deviceCount ? {key: 'qing', text: t('App 已开通')} : {key: 'xie', text: t('待领取')};
  if (!user.profileId) return {key: 'xie', text: t('待分配')};
  if (!user.deviceCount) return {key: 'xie', text: t('待领取')};
  if (user.appliedCount) return {key: 'qing', text: t('已应用')};
  if (user.receivedCount) return {key: 'wu', text: t('已收到 · 待应用')};
  return {key: 'wu', text: t('等待回执')};
}

export function jobWeather(job) {
  switch (job.status) {
    case 'running': return {key: 'qing', text: t('已启动')};
    case 'probed': return {key: 'qing', text: t('检测完成')};
    case 'failed': return {key: 'duan', text: t('失败')};
    case 'queued': return {key: 'wu', text: t('排队中')};
    case 'leased': return {key: 'wu', text: t('执行中')};
    case 'staged': return {key: 'xie', text: t('仅暂存')};
    case 'superseded': return {key: 'xie', text: t('已被替代')};
    default: return {key: 'xie', text: t('未知')};
  }
}

export function deviceWeather(device, revision) {
  if (device.revoked) return {key: 'xie', text: t('已撤销')};
  if (device.ack_revision === revision) {
    if (device.ack_status === 'applied') return {key: 'qing', text: t('已应用')};
    if (device.ack_status === 'failed') return {key: 'duan', text: t('应用失败')};
    if (device.ack_status === 'received') return {key: 'wu', text: t('已收到')};
  }
  if (device.ack_status) return {key: 'wu', text: t('旧版本')};
  return {key: 'wu', text: t('已绑定')};
}
