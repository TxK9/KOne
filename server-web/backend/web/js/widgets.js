import {t, number, quotation} from './i18n.js';
// Composite pieces shared by several pages.

import {h, s, fill, matches, clamp, copyText} from './core.js';
import {icon} from './icons.js';
import {nameSeal} from './ink.js';
import {toast} from './layer.js';

/** An empty state: a drawing, one line, maybe one action. */
export function empty(iconName, text, action) {
  return h('div', {class: 'empty'}, h('span', {class: 'empty-art'}, icon(iconName)), h('p', null, text), action || null);
}

export function button(text, {primary, danger, quiet, iconName, onClick, disabled, title} = {}) {
  return h('button', {type: 'button', class: 'button' + (primary ? ' button-primary' : '') + (danger ? ' button-danger' : '') + (quiet ? ' button-quiet' : ''), onClick, disabled, title},
    iconName ? icon(iconName) : null, text);
}

export function iconButton(name, label, onClick, props = {}) {
  return h('button', {type: 'button', class: 'icon-button', 'aria-label': label, title: label, onClick, ...props}, icon(name));
}

/** Text tabs, like KOne's category tabs: [{id, text, count}]. */
export function tabs(items, current, onPick, label) {
  return h('div', {class: 'tabs', role: 'tablist', 'aria-label': label},
    items.map(item => h('button', {type: 'button', role: 'tab', 'aria-selected': String(item.id === current), class: 'tab', onClick: () => onPick(item.id)},
      item.text, item.count != null ? h('span', {class: 'tab-count'}, number(item.count)) : null)));
}

/** A ring gauge drawn like a brush circle: the share filled in ink, the number in the middle. */
export function gauge(label, value, detail) {
  const known = Number.isFinite(value);
  const share = known ? clamp(value, 0, 100) / 100 : 0;
  const radius = 26, length = 2 * Math.PI * radius;
  const level = !known ? 'unknown' : value > 95 ? 'duan' : value > 85 ? 'wu' : 'ok';
  const svg = s('svg', {viewBox: '0 0 64 64', class: 'gauge-ring', 'aria-hidden': 'true'},
    s('circle', {class: 'gauge-track', cx: 32, cy: 32, r: radius}),
    known ? s('circle', {class: 'gauge-arc', cx: 32, cy: 32, r: radius, 'stroke-dasharray': (length * share).toFixed(1) + ' ' + length.toFixed(1), transform: 'rotate(-90 32 32)'}) : null);
  return h('div', {class: 'gauge gauge-' + level},
    h('div', {class: 'gauge-dial'}, svg, h('strong', null, known ? String(Math.round(value)) : '—', known ? h('small', null, '%') : null)),
    h('span', {class: 'gauge-label'}, label),
    detail ? h('small', {class: 'gauge-detail'}, detail) : null);
}

/** A list of facts: [[term, value], ...]; values may be nodes. Empty values are skipped. */
export function facts(pairs, className = '') {
  return h('dl', {class: 'facts ' + className}, pairs.filter(p => p && p[1] != null && p[1] !== '').map(([term, value]) =>
    h('div', null, h('dt', null, term), h('dd', null, value))));
}

/** A thin ink bar for used / total. */
export function meter(used, total) {
  if (!Number.isFinite(used) || !total) return h('span', {class: 'meter is-unknown'});
  const share = clamp(used / total, 0, 1);
  return h('span', {class: 'meter' + (share > 0.95 ? ' is-full' : share > 0.85 ? ' is-high' : '')}, h('b', {style: 'width:' + (share * 100).toFixed(1) + '%'}));
}

/**
 * Pick people from a list, with a search box. users: [{id, name, note, disabled}].
 * Returns {node, chosen: Set}.
 */
export function userPicker(users, {chosen = new Set(), empty: emptyText = t('还没有客户')} = {}) {
  const picked = new Set(chosen);
  const list = h('div', {class: 'picker-list'});
  const count = h('span', {class: 'picker-count'});
  const search = h('input', {class: 'input picker-search', type: 'search', placeholder: t('搜索'), 'aria-label': t('搜索客户'), autocomplete: 'off'});
  const draw = () => {
    const shown = users.filter(u => matches(search.value, u.name, u.note));
    fill(list, shown.length ? shown.map(u => {
      const box = h('input', {type: 'checkbox', checked: picked.has(u.id), disabled: u.disabled});
      box.addEventListener('change', () => { box.checked ? picked.add(u.id) : picked.delete(u.id); tally(); });
      return h('label', {class: 'picker-item' + (u.disabled ? ' is-disabled' : '')}, box, nameSeal(u.name, 's'),
        h('span', {class: 'picker-name'}, u.name), u.note ? h('small', null, u.note) : null);
    }) : h('p', {class: 'picker-empty'}, users.length ? t('没有匹配的客户') : emptyText));
  };
  const tally = () => { count.textContent = picked.size ? t('已选 {n} 位', {n: number(picked.size)}) : ''; };
  search.addEventListener('input', draw);
  draw();
  tally();
  const node = h('div', {class: 'picker'}, users.length > 6 ? h('div', {class: 'picker-head'}, h('span', {class: 'picker-search-wrap'}, icon('search'), search), count) : h('div', {class: 'picker-head'}, count), list);
  return {node, chosen: picked};
}

/** A secret shown once: masked until asked, with a copy button. */
export function secret(value, {label = t('复制'), masked = true} = {}) {
  const text = h('code', {class: 'secret-text' + (masked ? ' is-masked' : '')}, value);
  const reveal = masked ? h('button', {type: 'button', class: 'icon-button', 'aria-label': t('显示'), title: t('显示'), onClick: () => { text.classList.remove('is-masked'); reveal.remove(); }}, icon('eye')) : null;
  const copy = h('button', {type: 'button', class: 'button button-quiet', onClick: async () => {
    const done = await copyText(value);
    toast(done ? t('已复制') : t('请手动选择复制'), done ? '' : 'error');
  }}, icon('copy'), label);
  return h('div', {class: 'secret'}, text, h('div', {class: 'secret-actions'}, reveal, copy));
}

/** "14:59" counting down to a deadline (epoch ms); calls done() when it runs out. */
export function countdown(until, done) {
  const el = h('span', {class: 'countdown'});
  const tick = () => {
    const left = Math.max(0, Math.round((until - Date.now()) / 1000));
    el.textContent = String(Math.floor(left / 60)).padStart(2, '0') + ':' + String(left % 60).padStart(2, '0');
    if (!left) { clearInterval(timer); el.classList.add('is-over'); done && done(); }
    if (!el.isConnected && left < Infinity && el.dataset.started) clearInterval(timer);
    el.dataset.started = '1';
  };
  const timer = setInterval(tick, 1000);
  tick();
  return el;
}

/** A QR code, encoded here in the browser (the code never leaves it), dark on white for any camera. */
export function qr(text, label = t('二维码')) {
  const lib = window.qrcodegen;
  if (!lib || !text) return null;
  let code;
  try { code = lib.QrCode.encodeText(text, lib.QrCode.Ecc.MEDIUM); } catch { return null; }
  const quiet = 4, size = code.size + quiet * 2;
  let d = '';
  for (let y = 0; y < code.size; y++) {
    for (let x = 0; x < code.size; x++) if (code.getModule(x, y)) d += 'M' + (x + quiet) + ' ' + (y + quiet) + 'h1v1h-1z';
  }
  return s('svg', {class: 'qr', viewBox: '0 0 ' + size + ' ' + size, role: 'img', 'aria-label': label, 'shape-rendering': 'crispEdges'},
    s('rect', {width: size, height: size, fill: '#FFFFFF'}), s('path', {d, fill: '#1C2622'}));
}

/** A fixed, source-linked cultural detail; it never rotates with polling or time. */
export function cultureNote(className = '') {
  return h('figure', {class: 'culture-note ' + className},
    h('blockquote', null, quotation.text),
    h('figcaption', null, h('a', {href: quotation.source, target: '_blank', rel: 'noopener noreferrer', 'aria-label': t('引用出处') + ' · ' + quotation.author}, quotation.author)));
}
