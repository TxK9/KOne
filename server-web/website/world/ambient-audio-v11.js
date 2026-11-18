/* Optional local soundscape. No AudioContext exists before a user calls enable().
 * Noise is synthesized locally, never downloaded or recorded. The same audio
 * graph follows scene/weather getters; visibility and motion controls mute it.
 */
(() => {
  'use strict';
  const MASTER_LIMIT = .09, MASTER_LEVEL = .075, CONTROL_MS = 200, FADE_SECONDS = .65;
  const clamp = (n, a = 0, b = 1) => Math.max(a, Math.min(b, n));
  const finite = n => typeof n === 'number' && Number.isFinite(n);
  const seaWeights = {sea: 1, ocean: 1, coast: 1, bay: .85, strait: .9, harbour: .32, harbor: .32, lagoon: .36, marina: .22};
  function create(options = {}) {
    const AudioEngine = window.AudioContext || window.webkitAudioContext;
    const media = matchMedia('(prefers-reduced-motion: reduce)');
    let context = null, master = null, layers = null, sources = [];
    let enabled = false, playing = true, destroyed = false, raf = 0, lastControl = null, updates = 0;
    let generation = 0, resumePending = null, status = AudioEngine ? 'off' : 'unavailable', error = null;
    let fadeUntil = 0, targetMaster = 0, gains = {wind: 0, rain: 0, sea: 0};
    let profileId = 'clear', weatherSource = 'manual', sceneId = null, waterType = 'land';
    const get = (name, fallback) => { try { const result = options[name]?.(); return result === undefined ? fallback : result; } catch (_) { return fallback; } };
    function mutedReason() {
      if (destroyed) return 'destroyed';
      if (!enabled) return 'off';
      if (document.hidden) return 'hidden';
      if (media.matches) return 'reduced-motion';
      if (!playing || get('isPlaying', true) === false) return 'paused';
      return null;
    }
    function target(param, value, seconds = .28) {
      if (!context || !param) return;
      param.cancelScheduledValues(context.currentTime);
      param.setTargetAtTime(value, context.currentTime, seconds);
    }
    function zeroImmediately() {
      if (context && master) {
        master.gain.cancelScheduledValues(context.currentTime);
        master.gain.setValueAtTime(0, context.currentTime);
      }
      targetMaster = 0;
    }
    function cancelLoop() { if (raf) cancelAnimationFrame(raf); raf = 0; lastControl = null; }
    function suspend() {
      if (!context || context.state !== 'running') return;
      const current = context;
      try { const result = current.suspend(); result?.catch?.(cause => { if (!destroyed) error = String(cause?.message || cause); }); }
      catch (cause) { if (!destroyed) error = String(cause?.message || cause); }
    }
    function stop(immediate = false) {
      targetMaster = 0;
      if (!context || !master || context.state !== 'running') { cancelLoop(); if (!destroyed && AudioEngine) status = enabled ? 'muted' : 'off'; return; }
      if (immediate) {
        zeroImmediately(); fadeUntil = 0; cancelLoop(); suspend();
        if (!destroyed) status = enabled ? 'muted' : 'off';
      } else {
        // The short RAF tail exists only while the audio graph is audibly fading.
        // No timeout survives a pause, hidden document or disabled soundscape.
        if (fadeUntil === 0) { target(master.gain, 0, .12); fadeUntil = context.currentTime + FADE_SECONDS; }
        status = 'fading'; if (!raf) raf = requestAnimationFrame(tick);
      }
    }
    function noiseBuffer(kind, seed) {
      const sampleRate = finite(context.sampleRate) ? context.sampleRate : 24000;
      const buffer = context.createBuffer(1, Math.round(sampleRate * 4.2), sampleRate), data = buffer.getChannelData(0);
      let value = seed >>> 0, last = 0;
      for (let i = 0; i < data.length; i++) {
        value = (Math.imul(value, 1664525) + 1013904223) >>> 0;
        const white = value / 2147483648 - 1;
        if (kind === 'rain') data[i] = white * .7;
        else { last = kind === 'sea' ? (last + .025 * white) / 1.025 : last * .98 + white * .07;
          data[i] = clamp(last * (kind === 'sea' ? 3.4 : 1.6), -.95, .95); }
      }
      // Avoid a hard seam at the loop join. This is synthesized audio data,
      // independent of wallpaper artwork and external sound recordings.
      const edge = Math.min(Math.floor(sampleRate * .06), Math.floor(data.length / 4));
      for (let i = 0; i < edge; i++) { const mix = i / edge, end = data.length - edge + i;
        data[end] = data[end] * (1 - mix) + data[i] * mix; }
      return buffer;
    }
    function build() {
      if (context) return;
      context = new AudioEngine({latencyHint: 'playback'});
      master = context.createGain(); master.gain.setValueAtTime(0, context.currentTime); master.connect(context.destination);
      layers = {};
      const configs = {wind: {low: 620, high: 55, seed: 913}, rain: {low: 6200, high: 950, seed: 419}, sea: {low: 530, high: 35, seed: 2671}};
      Object.entries(configs).forEach(([name, settings]) => {
        const source = context.createBufferSource(), high = context.createBiquadFilter(), low = context.createBiquadFilter(), gain = context.createGain();
        source.buffer = noiseBuffer(name, settings.seed); source.loop = true;
        source.loopStart = .06; source.loopEnd = source.buffer.duration;
        high.type = 'highpass'; high.frequency.setValueAtTime(settings.high, context.currentTime); high.Q.setValueAtTime(.35, context.currentTime);
        low.type = 'lowpass'; low.frequency.setValueAtTime(settings.low, context.currentTime); low.Q.setValueAtTime(.35, context.currentTime);
        gain.gain.setValueAtTime(0, context.currentTime);
        source.connect(high); high.connect(low); low.connect(gain); gain.connect(master);
        source.start(0, name === 'wind' ? .7 : name === 'sea' ? 1.9 : 0);
        sources.push(source); layers[name] = {gain, high, low};
      });
    }
    function updateMix() {
      const p = get('getProfile', {}) || {}, scene = get('getScene', {}) || {};
      profileId = String(p.id || 'clear'); weatherSource = String(p.source || 'manual'); sceneId = scene.id || null;
      waterType = String(scene.waterType || scene.marine?.type || 'land').toLowerCase();
      const wind = finite(p.wind) ? clamp(p.wind, .5, 2) : 1;
      const rain = finite(p.rain) ? clamp(p.rain) : 0;
      const t = context.currentTime, breath = .88 + .07 * Math.sin(t * .17) + .05 * Math.sin(t * .049 + 1.8);
      const swell = .68 + .2 * Math.sin(t * .53 + .6) + .12 * Math.sin(t * .21);
      gains = {wind: clamp((wind - .35) * .12 * breath, 0, .23), rain: rain * .27,
        sea: (seaWeights[waterType] || 0) * (.13 + .055 * wind) * swell};
      Object.entries(gains).forEach(([name, value]) => target(layers[name].gain.gain, value, .35));
      target(layers.wind.low.frequency, 360 + wind * 180, .5);
      targetMaster = Math.min(MASTER_LIMIT, MASTER_LEVEL); target(master.gain, targetMaster, .24);
      fadeUntil = 0; status = 'running'; updates++;
    }
    function tick(timestamp) {
      raf = 0;
      if (destroyed || !context || context.state !== 'running') { lastControl = null; return; }
      const reason = mutedReason();
      if (reason) {
        if (reason === 'hidden' || reason === 'reduced-motion') { stop(true); return; }
        if (!fadeUntil) stop(false);
        if (context.currentTime >= fadeUntil) { zeroImmediately(); fadeUntil = 0; cancelLoop(); suspend(); status = enabled ? 'muted' : 'off'; return; }
      } else if (lastControl === null || timestamp - lastControl >= CONTROL_MS) { updateMix(); lastControl = timestamp; }
      // stop(false) can enqueue the fade tail. Keep exactly one RAF callback.
      if (!raf) raf = requestAnimationFrame(tick);
    }
    async function resumeIfAllowed(token = generation) {
      if (!context || mutedReason()) { stop(true); return false; }
      try {
        if (context.state === 'running' && status === 'running' && raf) return true;
        const wasRunning = context.state === 'running';
        if (!resumePending) resumePending = Promise.resolve(context.resume()).finally(() => { resumePending = null; });
        await resumePending;
        if (destroyed || token !== generation) return false;
        if (mutedReason()) { stop(true); return false; }
        if (!wasRunning) zeroImmediately();
        updateMix(); lastControl = null;
        if (!raf) raf = requestAnimationFrame(tick);
        return context.state === 'running';
      } catch (cause) {
        if (!destroyed && token === generation) { error = String(cause?.message || cause); status = 'blocked'; zeroImmediately(); cancelLoop(); }
        return false;
      }
    }
    const onVisibility = () => { if (document.hidden) stop(true); else if (enabled) resumeIfAllowed(); };
    const onReduced = () => { if (media.matches) stop(true); else if (enabled) resumeIfAllowed(); };
    document.addEventListener('visibilitychange', onVisibility); media.addEventListener('change', onReduced);
    return {
      async enable() {
        if (destroyed) return false;
        if (!AudioEngine) { status = 'unavailable'; return false; }
        enabled = true; error = null; const token = ++generation;
        try { build(); } catch (cause) { error = String(cause?.message || cause); status = 'unavailable'; enabled = false; return false; }
        return resumeIfAllowed(token);
      },
      disable() { if (destroyed) return; enabled = false; generation++; stop(false); },
      setPlaying(value) { if (destroyed) return; playing = Boolean(value); generation++;
        if (mutedReason()) stop(document.hidden || media.matches); else if (enabled) resumeIfAllowed(); },
      destroy() {
        if (destroyed) return; destroyed = true; enabled = false; generation++; zeroImmediately(); cancelLoop();
        document.removeEventListener('visibilitychange', onVisibility); media.removeEventListener('change', onReduced);
        sources.forEach(source => { try { source.stop(); source.disconnect(); } catch (_) { /* Already stopped. */ } });
        Object.values(layers || {}).forEach(layer => Object.values(layer).forEach(node => { try { node.disconnect(); } catch (_) { /* Disconnected. */ } }));
        try { master?.disconnect(); const close = context?.close(); close?.catch?.(() => {}); } catch (_) { /* Closed. */ }
        sources = []; layers = null; master = null; status = 'destroyed';
      },
      get state() { return {available: Boolean(AudioEngine), enabled, playing, destroyed, status, error,
        running: Boolean(raf) && context?.state === 'running', contextState: context?.state || 'not-created',
        mutedReason: mutedReason(), maxMasterGain: MASTER_LIMIT, masterGain: targetMaster, gains: {...gains},
        profileId, weatherSource, sceneId, waterType, updates, controlHz: 1000 / CONTROL_MS, sourceCount: sources.length}; }
    };
  }
  window.KOneAmbientV11 = {create};
})();
