// Small DOM, time and number helpers shared by every page.

import {t, locale, number, dateText, timeText} from './i18n.js';

const SVG_NS = 'http://www.w3.org/2000/svg';

function setProps(el, props) {
  if (!props) return;
  for (const [key, value] of Object.entries(props)) {
    if (value == null || value === false) continue;
    if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'dataset') Object.assign(el.dataset, value);
    else if (key === 'html') el.innerHTML = value; // trusted, static markup only (icons)
    else if (key === 'value' && 'value' in el) el.value = value;
    else if (key === 'checked' || key === 'disabled' || key === 'selected') { el[key] = true; if (key !== 'checked') el.setAttribute(key, ''); }
    else el.setAttribute(key, value === true ? '' : String(value));
  }
}

function addChildren(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false || child === '') continue;
    el.append(child instanceof Node ? child : String(child));
  }
}

/** An HTML element: h('button', {class: 'x', onClick}, 'text', child). Text is never parsed as HTML. */
export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  setProps(el, props);
  addChildren(el, children);
  return el;
}

/** An SVG element, same shape as h(). */
export function s(tag, props, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  setProps(el, props);
  addChildren(el, children);
  return el;
}

export const $ = (selector, scope = document) => scope.querySelector(selector);

/** Replaces an element's children without losing the element itself. */
export function fill(el, ...children) {
  el.replaceChildren();
  addChildren(el, children);
  return el;
}

/** A stable 32-bit hash of a string (FNV-1a), for drawings that must look the same every time. */
export function hash(text) {
  let value = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    value ^= text.charCodeAt(i);
    value = Math.imul(value, 0x01000193);
  }
  return value >>> 0;
}

/** A small seeded random generator (mulberry32). */
export function random(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export const clamp = (value, low, high) => Math.min(high, Math.max(low, value));

// Times arrive either as epoch seconds (numbers) or ISO strings.
export function toDate(value) {
  if (value == null || value === '') return null;
  const date = typeof value === 'number' ? new Date(value * 1000) : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

const two = n => String(n).padStart(2, '0');

function dayLabel(date, today = new Date()) {
  const start = d => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const days = Math.round((start(today) - start(date)) / 86400000);
  if (days === 0) return t('今天');
  if (days === 1) return t('昨天');
  return dateText(date, {month: 'short', day: 'numeric', ...(date.getFullYear() !== today.getFullYear() ? {year: 'numeric'} : {})});
}

export function when(value) {
  const date = toDate(value);
  if (!date) return '—';
  const label = dayLabel(date);
  return label === t('今天') ? timeText(date) : label + ' ' + timeText(date);
}

export function day(value) {
  const date = toDate(value);
  return date ? dayLabel(date) : '—';
}

export function ago(value) {
  const date = toDate(value);
  if (!date) return '—';
  const seconds = (date.getTime() - Date.now()) / 1000;
  if (Math.abs(seconds) < 45) return t('刚刚');
  const relative = new Intl.RelativeTimeFormat(locale, {numeric: 'always'});
  if (Math.abs(seconds) < 3600) return relative.format(Math.round(seconds / 60), 'minute');
  if (Math.abs(seconds) < 6 * 3600) return relative.format(Math.round(seconds / 3600), 'hour');
  return when(value);
}

export function duration(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return '—';
  const n = Math.floor(seconds / 86400), h = Math.floor((seconds % 86400) / 3600), m = Math.floor((seconds % 3600) / 60);
  if (n) return t('{n} 天 {h} 小时', {n: number(n), h: number(h)});
  if (h) return t('{h} 小时 {m} 分', {h: number(h), m: number(m)});
  return t('{m} 分', {m: number(m)});
}

export function bytes(value) {
  if (!Number.isFinite(value) || value < 0) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let n = value, i = 0;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return number(n, {minimumFractionDigits: i && n < 10 ? 1 : 0, maximumFractionDigits: i && n < 10 ? 1 : 0}) + ' ' + units[i];
}

export const rate = value => (Number.isFinite(value) && value >= 0 ? bytes(value) + '/s' : '—');
export const percent = value => (Number.isFinite(value) ? number(value / 100, {style: 'percent', maximumFractionDigits: 0}) : '—');

/** The first character of a name, for seals and avatars. */
export function initial(name) {
  const first = Array.from(String(name || '').trim())[0] || t('客');
  return first.toUpperCase();
}

/** Copies text, falling back to a hidden field for pages not served over HTTPS. */
export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const field = h('textarea', {style: 'position:fixed;opacity:0;pointer-events:none', 'aria-hidden': 'true'});
    field.value = text;
    document.body.append(field);
    field.select();
    let done = false;
    try { done = document.execCommand('copy'); } catch { done = false; }
    field.remove();
    return done;
  }
}

/** Remembers a small per-browser preference; survives being unavailable. */
export const prefs = {
  get(key, fallback = null) {
    try { return localStorage.getItem('kone.' + key) ?? fallback; } catch { return fallback; }
  },
  set(key, value) {
    try { value == null ? localStorage.removeItem('kone.' + key) : localStorage.setItem('kone.' + key, value); } catch { /* private mode */ }
  },
};

/** Matches every whitespace-separated term in any of the texts, ignoring case. */
export function matches(query, ...texts) {
  const terms = String(query || '').trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  if (!terms.length) return true;
  const haystack = texts.filter(Boolean).join(' ').toLocaleLowerCase();
  return terms.every(term => haystack.includes(term));
}
