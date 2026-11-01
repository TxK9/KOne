/* Deterministic art-directed wind, shared by cloud, water and light layers.
 * This is not live weather. Advection integrates positive wind speed, so
 * gusts change pace without teleporting a cloud or reversing a wake.
 */
(() => {
  'use strict';
  const TAU = Math.PI * 2, cache = new Map();
  const clamp = n => Math.max(0, Math.min(1, n));
  function parameters(id) {
    id = String(id || 'city');
    if (cache.has(id)) return cache.get(id);
    let h = 2166136261;
    for (const c of id) h = Math.imul(h ^ c.charCodeAt(0), 16777619) >>> 0;
    const random = () => { h = (Math.imul(h, 1664525) + 1013904223) >>> 0; return h / 4294967296; };
    const p = {a: random()*TAU, b: random()*TAU, c: random()*TAU,
      w1: TAU/(31+random()*24), w2: TAU/(9+random()*8),
      coverRate: TAU/(47+random()*32), groupRate: TAU/(12+random()*9)};
    if (cache.size >= 512) cache.delete(cache.keys().next().value);
    cache.set(id, p); return p;
  }
  function sample(id, seconds) {
    const t = Number.isFinite(seconds) ? Math.max(0, seconds) : 0, p = parameters(id);
    const wind = 1 + .22*Math.sin(t*p.w1+p.a) + .10*Math.sin(t*p.w2+p.b);
    const advection = t - .22/p.w1*(Math.cos(t*p.w1+p.a)-Math.cos(p.a))
      - .10/p.w2*(Math.cos(t*p.w2+p.b)-Math.cos(p.b));
    return {wind, advection, gust: clamp((wind-.68)/.64),
      cloudCover: clamp(.48+.20*Math.sin(t*p.coverRate+p.c)+.10*Math.sin(t*p.w1*.47+p.a)),
      waveGroup: clamp(.5+.32*Math.sin(t*p.groupRate+p.b)+.15*Math.sin(t*p.w1+p.c)),
      shimmer: clamp(.5+.32*Math.sin(t*1.17+p.a)+.15*Math.sin(t*.43+p.b))};
  }
  const api = {sample, source: 'art-directed-not-live-weather'};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else window.KOneEnvironmentV10 = api;
})();
