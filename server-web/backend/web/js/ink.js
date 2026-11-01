import {t} from './i18n.js';
// Ink drawings: the paintings, each server as a building in a skyline,
// each route as a suspension bridge between those buildings, and name seals for people.

import {h, s, hash, random, clamp} from './core.js';
import {WX} from './weather.js';

const calm = () => window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

// ---- The paintings ---------------------------------------------------------------------------

const PAINTINGS = ['liberty', 'goldengate', 'halfdome', 'rainier'];

/**
 * A stable daily painting, with both theme variants. No background rotation.
 */
export function scenery(className = '') {
  const name = PAINTINGS[Math.floor(Date.now() / 86400000) % PAINTINGS.length];
  const image = mode => h('img', {class: 'scene-' + mode + ' is-ready', alt: '', decoding: 'async', draggable: 'false', src: '/art/' + name + '-' + mode + '.png'});
  return h('div', {class: 'scenery ' + className, 'data-painting': name, 'aria-hidden': 'true'},
    h('div', {class: 'scene is-shown'}, image('light'), image('dark')));
}

// ---- Buildings -----------------------------------------------------------------------------

// Each style is a stack of tiers (inset from each side, top as a share of the height) and a roof.
const STYLES = [
  {name: 'empire', tiers: [[0, 0.42], [0.12, 0.66], [0.26, 0.82], [0.37, 0.9]], roof: 'mast', tall: [0.82, 1]},
  {name: 'chrysler', tiers: [[0, 0.5], [0.15, 0.76]], roof: 'crown', tall: [0.8, 1]},
  {name: 'slab', tiers: [[0.04, 0.95]], roof: 'plant', tall: [0.62, 0.86]},
  {name: 'pyramid', tiers: null, roof: 'pyramid', tall: [0.85, 1]},
  {name: 'stepped', tiers: [[0, 0.32], [0.1, 0.54], [0.2, 0.72], [0.3, 0.86]], roof: 'antenna', tall: [0.6, 0.84]},
  {name: 'gothic', tiers: [[0, 0.5], [0.22, 0.82]], roof: 'point', tall: [0.7, 0.92]},
  {name: 'dome', tiers: [[0, 0.4]], roof: 'dome', tall: [0.48, 0.6]},
];

const f = n => n.toFixed(1);

function roofPoints(roof, x0, x1, top, W, H) {
  const cx = (x0 + x1) / 2, w = x1 - x0;
  const thin = Math.max(0.7, W * 0.022);
  switch (roof) {
    case 'mast': return [[cx - w * 0.18, top], [cx - w * 0.18, top - H * 0.035], [cx - thin, top - H * 0.035], [cx - thin, top - H * 0.1], [cx + thin, top - H * 0.1], [cx + thin, top - H * 0.035], [cx + w * 0.18, top - H * 0.035], [cx + w * 0.18, top]];
    case 'antenna': return [[cx - thin * 0.6, top], [cx - thin * 0.6, top - H * 0.12], [cx + thin * 0.6, top - H * 0.12], [cx + thin * 0.6, top]];
    case 'plant': return [[x0 + w * 0.28, top], [x0 + w * 0.28, top - H * 0.035], [x1 - w * 0.28, top - H * 0.035], [x1 - w * 0.28, top]];
    case 'point': return [[cx, top - H * 0.15]];
    case 'crown': {
      // Stacked arches narrowing to a needle, the Chrysler's sunburst crown in a few strokes.
      const left = [];
      const steps = 4;
      for (let i = 0; i < steps; i++) {
        const a = x0 + (w / 2) * (i / steps) * 0.9;
        const b = x0 + (w / 2) * ((i + 0.55) / steps) * 0.9;
        const y = top - H * 0.035 * i;
        left.push([a, y], [b, y - H * 0.045]);
      }
      left.push([cx - thin, top - H * 0.15], [cx, top - H * 0.24]);
      const right = left.slice(0, -1).reverse().map(([x, y]) => [2 * cx - x, y]);
      return [...left, ...right];
    }
    case 'dome': {
      const drumL = x0 + w * 0.24, drumR = x1 - w * 0.24, drumTop = top - H * 0.08;
      const radius = (drumR - drumL) / 2;
      const arc = [];
      for (let i = 0; i <= 10; i++) {
        const angle = Math.PI - (Math.PI * i) / 10;
        arc.push([cx + Math.cos(angle) * radius, drumTop - Math.sin(angle) * radius * 0.9]);
      }
      const crownY = drumTop - radius * 0.9;
      return [[drumL, top], [drumL, drumTop], ...arc.slice(0, 5), [cx - thin * 1.4, crownY], [cx - thin * 1.4, crownY - H * 0.07], [cx + thin * 1.4, crownY - H * 0.07], [cx + thin * 1.4, crownY], ...arc.slice(6), [drumR, drumTop], [drumR, top]];
    }
    default: return [];
  }
}

/** The outline and window bays of one building, base at y = H. */
function plan(seed, W, maxH) {
  const rand = random(seed);
  const style = STYLES[seed % STYLES.length];
  const H = maxH * (style.tall[0] + (style.tall[1] - style.tall[0]) * rand());
  const y = share => H - share * H;
  const bays = [];
  let points;
  if (style.roof === 'pyramid') {
    // Transamerica: a tall thin pyramid with two shoulders near the top.
    const base = W * 0.08;
    points = [[base, H], [W * 0.42, y(0.78)], [W * 0.3, y(0.74)], [W * 0.28, y(0.68)], [W * 0.44, y(0.7)], [W / 2, 0],
      [W * 0.56, y(0.7)], [W * 0.72, y(0.68)], [W * 0.7, y(0.74)], [W * 0.58, y(0.78)], [W - base, H]];
    for (let r = 0; r < 9; r++) {
      const lo = r / 10, hi = (r + 0.8) / 10;
      const inset = base + (W / 2 - base) * hi * 0.92;
      bays.push({x0: inset, x1: W - inset, y0: y(hi * 0.7), y1: y(lo * 0.7)});
    }
    return {d: 'M' + points.map(p => f(p[0]) + ' ' + f(p[1])).join('L') + 'Z', bays, H};
  }
  const tiers = style.tiers.map(([inset, top]) => ({x0: inset * W, x1: W - inset * W, top: y(top)}));
  points = [[tiers[0].x0, H]];
  tiers.forEach((tier, i) => {
    if (i) points.push([tier.x0, tiers[i - 1].top]);
    points.push([tier.x0, tier.top]);
  });
  const last = tiers[tiers.length - 1];
  points.push(...roofPoints(style.roof, last.x0, last.x1, last.top, W, H));
  for (let i = tiers.length - 1; i >= 0; i--) {
    points.push([tiers[i].x1, tiers[i].top]);
    if (i) points.push([tiers[i].x1, tiers[i - 1].top]);
  }
  points.push([tiers[0].x1, H]);
  tiers.forEach((tier, i) => bays.push({x0: tier.x0, x1: tier.x1, y0: tier.top, y1: i ? tiers[i - 1].top : H}));
  return {d: 'M' + points.map(p => f(p[0]) + ' ' + f(p[1])).join('L') + 'Z', bays, H};
}

function windows(bays, W, seed) {
  const size = clamp(W * 0.062, 1.5, 3.4);
  const tall = size * 1.45, gapX = size * 0.95, gapY = tall * 0.8;
  const list = [];
  for (const bay of bays) {
    const width = bay.x1 - bay.x0 - 2 * Math.max(2, size);
    const height = bay.y1 - bay.y0 - tall * 0.9;
    const cols = Math.floor((width + gapX) / (size + gapX));
    const rows = Math.floor((height + gapY) / (tall + gapY));
    if (cols < 1 || rows < 1) continue;
    const startX = bay.x0 + (bay.x1 - bay.x0 - (cols * size + (cols - 1) * gapX)) / 2;
    const startY = bay.y0 + tall * 0.7;
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) list.push([startX + c * (size + gapX), startY + r * (tall + gapY)]);
    }
  }
  const order = random(seed ^ 0x9e3779b9);
  return {size, tall, list: list.map(p => ({x: p[0], y: p[1], rank: order()}))};
}

/** How busy a server looks: CPU and traffic, as the share of windows lit. */
export function activity(server) {
  const m = server.metrics || {};
  const cpu = Number.isFinite(m.cpuPercent) ? m.cpuPercent / 100 : 0;
  const traffic = (m.downloadRate || 0) + (m.uploadRate || 0);
  const net = clamp(Math.log10(1 + traffic / 1024) / 4, 0, 1);
  return clamp(0.12 + 0.55 * cpu + 0.45 * net, 0, 1);
}

/**
 * One building as an SVG group, base at (x, base). `look` is 'lit' (online), 'dark' (down),
 * 'site' (being built: not yet enrolled) or 'ghost' (revoked); `fog` veils it (雾).
 */
export function building(id, {x = 0, base = 0, width = 40, height = 120, look = 'lit', lit = 0.4, fog = false}) {
  const seed = hash(String(id));
  const {d, bays, H} = plan(seed, width, height);
  const group = s('g', {class: 'bldg bldg-' + look, transform: 'translate(' + f(x) + ' ' + f(base - H) + ')'});
  group.append(s('path', {class: 'bldg-body', d}));
  if (look === 'site') {
    // Scaffolding floors and a crane: a building still going up.
    for (let yy = H - 7; yy > H * 0.22; yy -= 7) group.append(s('path', {class: 'bldg-scaffold', d: 'M' + f(width * 0.08) + ' ' + f(yy) + 'H' + f(width * 0.92)}));
    const mastX = width * 0.82, top = -H * 0.18;
    group.append(s('path', {class: 'bldg-crane', d: 'M' + f(mastX) + ' ' + f(H * 0.3) + 'V' + f(top) + 'M' + f(mastX + width * 0.18) + ' ' + f(top + 3) + 'H' + f(mastX - width * 0.8) + 'M' + f(mastX - width * 0.62) + ' ' + f(top + 3) + 'v' + f(H * 0.18)}));
  }
  if (look === 'lit' || look === 'dark') {
    const panes = windows(bays, width, seed);
    const on = look === 'lit' ? Math.round(panes.list.length * lit) : 0;
    const ranked = [...panes.list].sort((a, b) => a.rank - b.rank);
    const layer = s('g', {class: 'bldg-windows'});
    ranked.forEach((pane, i) => layer.append(s('rect', {class: i < on ? 'pane on' : 'pane', x: f(pane.x), y: f(pane.y), width: f(panes.size), height: f(panes.tall)})));
    group.append(layer);
  }
  if (fog) group.append(s('rect', {class: 'bldg-fog', x: -6, y: f(H * 0.3), width: f(width + 12), height: f(H * 0.42), rx: 12}));
  group.dataset.height = String(H);
  return group;
}

const SEAL_FILL = {qing: 'var(--ok)', wu: 'var(--slow)', duan: 'var(--down)', xie: 'var(--rest)'};
const SEAL_TEXT = {qing: 'var(--on-seal)', wu: 'var(--on-slow)', duan: 'var(--on-seal)', xie: 'var(--on-seal)'};

/** A small weather seal drawn inside an SVG. */
export function svgSeal(key, x, y, size) {
  return s('g', {class: 'svg-seal', transform: 'translate(' + f(x) + ' ' + f(y) + ')'},
    s('rect', {width: f(size), height: f(size), rx: f(size * 0.2), style: 'fill:' + SEAL_FILL[key]}),
    s('text', {x: f(size / 2), y: f(size * 0.53), 'font-size': f(size * 0.68), style: 'fill:' + SEAL_TEXT[key], 'dominant-baseline': 'central', 'text-anchor': 'middle'}, WX[key].char));
}

function labelSize(text, room, largest) {
  let units = 0;
  for (const ch of String(text)) units += /[⺀-￿]/.test(ch) ? 1 : 0.58;
  return clamp(room / Math.max(units, 1), 8, largest);
}

/** The look of a server's building for its weather. */
function lookOf(server, weatherKey) {
  if (server.revoked) return 'ghost';
  if (!server.registered) return 'site';
  return weatherKey === 'duan' ? 'dark' : 'lit';
}

/**
 * The servers as a skyline standing in mist. items: [{server, weather}], weather = {key, text}.
 * Buildings twinkle while their server is busy; clicking one calls onSelect(server.id).
 */
export function skyline(items, {height = 150, onSelect, selected, slot = 112, labels = true} = {}) {
  const count = Math.max(items.length, 1);
  const width = count * slot;
  const label = labels ? 30 : 0;
  const svg = s('svg', {class: 'skyline-art', viewBox: '0 0 ' + width + ' ' + (height + label + 8), preserveAspectRatio: 'xMidYMax meet', role: 'group', 'aria-label': t('服务器')});
  const base = height + 4;
  const mistId = 'mist' + Math.random().toString(36).slice(2, 8);
  svg.append(s('defs', null, s('linearGradient', {id: mistId, x1: '0', y1: '0', x2: '0', y2: '1'},
    s('stop', {offset: '0', style: 'stop-color: rgb(var(--mist)); stop-opacity: 0'}),
    s('stop', {offset: '1', style: 'stop-color: rgb(var(--mist)); stop-opacity: .78'}))));
  const towers = s('g', {class: 'skyline-towers'});
  const plaques = s('g', {class: 'skyline-plaques'});
  const twinkle = [];
  items.forEach(({server, weather}, index) => {
    const seed = hash(server.id);
    const bw = slot * (0.42 + 0.2 * random(seed ^ 0x51ed)());
    const x = index * slot + (slot - bw) / 2;
    const look = lookOf(server, weather.key);
    const interactive = Boolean(onSelect);
    const wrap = s('g', {class: 'skyline-item wx-' + weather.key + (server.id === selected ? ' is-selected' : ''), tabindex: interactive ? '0' : null, role: interactive ? 'button' : null,
      'aria-label': server.name + ' · ' + weather.text});
    wrap.append(s('title', null, server.name + ' · ' + weather.text));
    // A wide invisible target so narrow towers stay easy to hit.
    wrap.append(s('rect', {class: 'skyline-hit', x: f(index * slot + 2), y: 0, width: f(slot - 4), height: f(base)}));
    // Roofs, masts and cranes rise above the body, so the body keeps a fifth of the height free.
    const art = building(server.id, {x, base, width: bw, height: height * 0.78, look, lit: activity(server), fog: weather.key === 'wu'});
    wrap.append(art);
    towers.append(wrap);
    if (look === 'lit') twinkle.push(art);
    let plaque = null;
    if (labels) {
      const name = String(server.name);
      const size = labelSize(name, slot - 26, 13);
      const units = [...name].reduce((n, ch) => n + (/[⺀-￿]/.test(ch) ? 1 : 0.58), 0);
      const total = 16 + Math.min(slot - 26, size * units);
      const left = index * slot + (slot - total) / 2;
      plaque = s('g', {class: 'skyline-plaque wx-' + weather.key, 'aria-hidden': 'true'},
        svgSeal(weather.key, left, base + 9, 12.5),
        s('text', {class: 'skyline-name', x: f(left + 16), y: f(base + 15.5), 'font-size': f(size), 'dominant-baseline': 'central'}, name));
      plaques.append(plaque);
    }
    if (interactive) {
      const choose = () => onSelect(server.id);
      wrap.addEventListener('click', choose);
      plaque?.addEventListener('click', choose);
      wrap.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); choose(); } });
      wrap.addEventListener('mouseenter', () => plaque?.classList.add('is-hover'));
      wrap.addEventListener('mouseleave', () => plaque?.classList.remove('is-hover'));
    }
  });
  svg.append(s('path', {class: 'skyline-ground', d: 'M0 ' + f(base) + 'H' + width}), towers,
    s('rect', {class: 'skyline-mist', x: '-40', y: f(base - height * 0.34), width: f(width + 80), height: f(height * 0.34 + 2), fill: 'url(#' + mistId + ')'}),
    plaques);
  startTwinkle(svg, twinkle);
  return svg;
}

/** Now and then a light goes out in one window and comes on in another. */
function startTwinkle(svg, buildings) {
  if (!buildings.length || calm()) return;
  const timer = setInterval(() => {
    if (!svg.isConnected) { clearInterval(timer); return; }
    if (document.hidden || calm()) return;
    const art = buildings[Math.floor(Math.random() * buildings.length)];
    const on = art.querySelectorAll('.pane.on'), off = art.querySelectorAll('.pane:not(.on)');
    if (!on.length || !off.length) return;
    on[Math.floor(Math.random() * on.length)].classList.remove('on');
    off[Math.floor(Math.random() * off.length)].classList.add('on');
  }, 1400);
}

// ---- Routes as bridges ---------------------------------------------------------------------

/**
 * A route drawn as a suspension bridge: each server on the path is a tower (its own building),
 * cables sag from tower to tower, the last span runs out to the exit.
 * stations: [{id, name, sub}], exit: {name, sub, external}, weather: {key}.
 */
export function bridge(stations, exit, weatherKey, {onStation} = {}) {
  const span = 172, towerW = 32, towerH = 80, deck = 106, left = 30;
  const count = stations.length;
  const width = left + count * span + 70;
  const svg = s('svg', {class: 'bridge wx-' + weatherKey, viewBox: '0 0 ' + width + ' 160', width, height: 160, preserveAspectRatio: 'xMinYMid meet', role: 'img',
    'aria-label': stations.map(st => st.name).join(' → ') + ' → ' + exit.name});
  const xs = stations.map((_, i) => left + i * span + span / 2);
  const exitX = left + count * span + 26;
  svg.append(s('path', {class: 'bridge-deck', d: 'M' + (left - 18) + ' ' + deck + 'H' + (exitX + 4)}));
  const towers = stations.map((station, i) => building(station.id || station.name,
    {x: xs[i] - towerW / 2, base: deck, width: towerW, height: towerH, look: station.look || 'lit', lit: 0.35}));
  // Cables: from each tower top to the next, sagging toward the deck; the last one to the exit.
  const tops = xs.map((x, i) => [x, deck - Number(towers[i].dataset.height) * 0.9]);
  const anchors = [...tops, [exitX, deck - 4]];
  const cable = [], hangers = [];
  for (let i = 0; i < anchors.length - 1; i++) {
    const [x1, y1] = anchors[i], [x2, y2] = anchors[i + 1];
    const sag = Math.min(y1, y2) + (deck - Math.min(y1, y2)) * 0.62;
    const mx = (x1 + x2) / 2;
    cable.push('M' + f(x1) + ' ' + f(y1) + 'Q' + f(mx) + ' ' + f(2 * sag - (y1 + y2) / 2) + ' ' + f(x2) + ' ' + f(y2));
    for (let t = 0.12; t < 0.9; t += 0.11) {
      const px = (1 - t) * (1 - t) * x1 + 2 * (1 - t) * t * mx + t * t * x2;
      const py = (1 - t) * (1 - t) * y1 + 2 * (1 - t) * t * (2 * sag - (y1 + y2) / 2) + t * t * y2;
      if (deck - py > 3) hangers.push('M' + f(px) + ' ' + f(py) + 'V' + deck);
    }
  }
  // The approach on the left, rising from the shore to the first tower.
  if (tops.length) cable.unshift('M' + (left - 18) + ' ' + (deck - 3) + 'Q' + f(left - 2) + ' ' + f(deck - 10) + ' ' + f(tops[0][0]) + ' ' + f(tops[0][1]));
  svg.append(s('path', {class: 'bridge-hangers', d: hangers.join('')}));
  svg.append(s('path', {class: 'bridge-cable', d: cable.join('')}));
  stations.forEach((station, i) => {
    const group = s('g', {class: 'bridge-station' + (onStation && station.id ? ' is-link' : ''), tabindex: onStation && station.id ? '0' : null, role: onStation && station.id ? 'link' : null, 'aria-label': station.name});
    group.append(towers[i]);
    group.append(s('text', {class: 'bridge-name', x: f(xs[i]), y: deck + 20, 'text-anchor': 'middle', 'font-size': f(labelSize(station.name, span - 14, 13))}, station.name));
    if (station.sub) group.append(s('text', {class: 'bridge-sub', x: f(xs[i]), y: deck + 37, 'text-anchor': 'middle'}, station.sub));
    if (onStation && station.id) {
      group.addEventListener('click', () => onStation(station.id));
      group.addEventListener('keydown', event => { if (event.key === 'Enter') onStation(station.id); });
    }
    svg.append(group);
  });
  // The exit: the open world for a direct exit, a small gate for an outside proxy.
  const end = s('g', {class: 'bridge-exit', transform: 'translate(' + f(exitX) + ' ' + (deck - 4) + ')'});
  if (exit.external) end.append(s('path', {d: 'M-7 4V-12h14V4M-7 -12l7-6 7 6M-3 4v-7h6v7'}));
  else end.append(s('circle', {cx: 0, cy: -6, r: 8}), s('path', {d: 'M-8 -6h16M0 -14c3 2.5 4 5 4 8s-1 5.5-4 8c-3-2.5-4-5-4-8s1-5.5 4-8'}));
  svg.append(end);
  svg.append(s('text', {class: 'bridge-name', x: f(exitX), y: deck + 20, 'text-anchor': 'middle', 'font-size': f(labelSize(exit.name, 64, 13))}, exit.name));
  if (exit.sub) svg.append(s('text', {class: 'bridge-sub', x: f(exitX), y: deck + 37, 'text-anchor': 'middle'}, exit.sub));
  return svg;
}

// ---- People --------------------------------------------------------------------------------

/** A name seal (白文印): the first character of a name cut white into a square of ink. */
export function nameSeal(name, size = 'm') {
  const char = Array.from(String(name || '').trim())[0] || t('客');
  return h('span', {class: 'name-seal name-seal-' + size, 'aria-hidden': 'true'}, char.toUpperCase());
}

/** A tiny building, as an icon for a server in lists. */
export function towerIcon(server, weatherKey, size = 30) {
  const look = lookOf(server, weatherKey);
  const svg = s('svg', {class: 'tower-icon', viewBox: '-4 -14 32 54', width: size, height: f(size * 54 / 32), 'aria-hidden': 'true'});
  svg.append(building(server.id, {x: 0, base: 38, width: 24, height: 38, look, lit: activity(server), fog: false}));
  return svg;
}
