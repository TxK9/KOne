import {t, errorText} from './i18n.js';
// Things that sit above the page: the side sheet, the toast, small menus, two-step confirms.

import {h, fill} from './core.js';
import {icon} from './icons.js';

// ---- Toast ---------------------------------------------------------------------------------

let toastEl = null, toastTimer = 0;

export function toast(message, tone = '') {
  if (!toastEl) {
    toastEl = h('div', {class: 'toast', role: 'status', 'aria-live': 'polite'});
    document.body.append(toastEl);
  }
  toastEl.textContent = tone === 'error' ? errorText(message) : message;
  toastEl.className = 'toast is-shown' + (tone ? ' toast-' + tone : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toastEl.className = 'toast'; }, tone === 'error' ? 4200 : 2400);
}

// ---- Sheet ---------------------------------------------------------------------------------

let current = null;

const focusable = root => [...root.querySelectorAll('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]')]
  .filter(el => el.getClientRects().length);

/**
 * Opens a side sheet (a bottom sheet on phones). `body` is a node or a function given the sheet.
 * Actions: [{text, primary, danger, run: async sheet => result}], a run that returns false keeps
 * the sheet open. Errors thrown by run show inside the sheet.
 */
export function sheet({title, kicker = '', body, actions = [], wide = false, compact = false}) {
  close(true);
  const before = document.activeElement;
  const error = h('p', {class: 'sheet-error', role: 'alert'});
  const content = h('div', {class: 'sheet-body'});
  const foot = h('div', {class: 'sheet-actions'});
  const panel = h('section', {class: 'sheet' + (wide ? ' sheet-wide' : ''), role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'sheet-title', tabindex: '-1'},
    h('header', {class: 'sheet-head'},
      h('div', null, kicker ? h('span', {class: 'sheet-kicker'}, kicker) : null, h('h2', {id: 'sheet-title'}, title)),
      h('button', {type: 'button', class: 'icon-button', 'aria-label': t('关闭'), onClick: () => api.close()}, icon('close'))),
    content, error, foot);
  const layer = h('div', {class: 'sheet-layer' + (compact ? ' sheet-layer-compact' : '')}, h('div', {class: 'sheet-scrim', onClick: () => api.close()}), panel);
  let busy = false;
  const api = {
    panel, content, error,
    setError(message) { error.textContent = message || ''; },
    setBusy(value) {
      busy = value;
      panel.classList.toggle('is-busy', value);
      panel.querySelectorAll('button, input, select, textarea').forEach(el => {
        if (value) { el.dataset.wasDisabled = el.disabled ? '1' : ''; el.disabled = true; }
        else if (el.dataset.wasDisabled !== undefined) { el.disabled = el.dataset.wasDisabled === '1'; delete el.dataset.wasDisabled; }
      });
    },
    setActions(list) {
      fill(foot, list.map(action => {
        const button = h('button', {type: 'button', class: 'button' + (action.primary ? ' button-primary' : '') + (action.danger ? ' button-danger' : '') + (action.quiet ? ' button-quiet' : '')}, action.text);
        button.addEventListener('click', async () => {
          if (busy) return;
          if (!action.run) { api.close(); return; }
          api.setError('');
          api.setBusy(true);
          try {
            const result = await action.run(api);
            api.setBusy(false);
            if (result !== false) api.close();
          } catch (e) {
            api.setBusy(false);
            api.setError(errorText(e.message));
          }
        });
        return button;
      }));
    },
    close(force = false) {
      if (busy && !force) return;
      if (current !== api) return;
      current = null;
      panel.querySelectorAll('.choice').forEach(el => el.close?.());
      layer.classList.add('is-leaving');
      document.removeEventListener('keydown', keys, true);
      setTimeout(() => layer.remove(), 180);
      document.querySelector('.app')?.removeAttribute('inert');
      if (before && before.isConnected) before.focus({preventScroll: true});
    },
  };
  function keys(event) {
    if (document.querySelector('.choice.is-open')) return;
    if (event.key === 'Escape') { event.preventDefault(); api.close(); return; }
    if (event.key !== 'Tab') return;
    const items = focusable(panel);
    if (!items.length) return;
    const first = items[0], last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) { last.focus(); event.preventDefault(); }
    else if (!event.shiftKey && document.activeElement === last) { first.focus(); event.preventDefault(); }
  }
  fill(content, typeof body === 'function' ? body(api) : body);
  api.setActions(actions);
  document.body.append(layer);
  document.querySelector('.app')?.setAttribute('inert', '');
  document.addEventListener('keydown', keys, true);
  current = api;
  // Chromium WebView can resolve an auto-height flex dialog to its border alone.
  // Give compact dialogs a measured, viewport-bounded height before the first paint.
  const sizeCompact = () => {
    if (!compact || !layer.isConnected) return;
    const viewport = window.visualViewport?.height || window.innerHeight;
    const padding = parseFloat(getComputedStyle(layer).paddingTop) || 0;
    const max = Math.max(120, viewport - padding * 2);
    const wanted = panel.querySelector('.sheet-head').offsetHeight + content.scrollHeight
      + error.scrollHeight + foot.offsetHeight + 2;
    panel.style.height = Math.min(max, Math.max(220, wanted)) + 'px';
    panel.style.maxHeight = max + 'px';
  };
  const resize = new ResizeObserver(sizeCompact);
  if (compact) {
    resize.observe(content);
    resize.observe(error);
    window.visualViewport?.addEventListener('resize', sizeCompact);
  }
  const originalClose = api.close;
  api.close = force => {
    if (busy && !force) return;
    resize.disconnect();
    window.visualViewport?.removeEventListener('resize', sizeCompact);
    originalClose(force);
  };
  sizeCompact();
  requestAnimationFrame(() => {
    layer.classList.add('is-open');
    const first = panel.querySelector('input:not([type=checkbox]):not(:disabled), select, textarea') || focusable(panel)[0];
    first?.focus({preventScroll: true});
  });
  return api;
}

export function close(force = false) {
  if (current) current.close(force);
}

export const sheetOpen = () => current !== null;

// ---- Two-step confirm ------------------------------------------------------------------------

/**
 * Turns a button into a two-step action: the first press asks for confirmation.
 * It quietly goes back after a few seconds. No dialog.
 */
export function twoStep(button, {ask, run}) {
  const original = [...button.childNodes].map(node => node.cloneNode(true));
  let armed = false, timer = 0;
  const reset = () => {
    armed = false;
    button.classList.remove('is-armed');
    button.replaceChildren(...original.map(node => node.cloneNode(true)));
  };
  button.addEventListener('click', async event => {
    event.stopPropagation();
    if (!armed) {
      armed = true;
      button.classList.add('is-armed');
      button.textContent = ask;
      clearTimeout(timer);
      timer = setTimeout(reset, 4000);
      return;
    }
    clearTimeout(timer);
    button.disabled = true;
    try { await run(); } finally {
      if (button.isConnected) { button.disabled = false; reset(); }
    }
  });
  button.addEventListener('blur', () => { if (armed) { clearTimeout(timer); timer = setTimeout(reset, 600); } });
  return button;
}

// ---- Menu ------------------------------------------------------------------------------------

/** A ⋯ button with a small menu of [{text, run, danger, disabled}], or a function giving them. */
export function moreMenu(items, label = t('更多')) {
  const list = h('div', {class: 'menu', role: 'menu', hidden: true});
  const button = h('button', {type: 'button', class: 'icon-button', 'aria-label': label, 'aria-haspopup': 'true', 'aria-expanded': 'false'}, icon('more'));
  const wrap = h('div', {class: 'menu-wrap'}, button, list);
  const hide = () => { list.hidden = true; button.setAttribute('aria-expanded', 'false'); document.removeEventListener('click', outside, true); };
  const outside = event => { if (!wrap.contains(event.target)) hide(); };
  button.addEventListener('click', () => {
    const open = list.hidden;
    if (!open) { hide(); return; }
    fill(list, (typeof items === 'function' ? items() : items).filter(Boolean).map(item => h('button', {type: 'button', role: 'menuitem', class: item.danger ? 'is-danger' : '', disabled: item.disabled,
      onClick: () => { hide(); item.run(); }}, item.text)));
    list.hidden = false;
    button.setAttribute('aria-expanded', 'true');
    document.addEventListener('click', outside, true);
    list.querySelector('button:not(:disabled)')?.focus({preventScroll: true});
  });
  wrap.addEventListener('keydown', event => { if (event.key === 'Escape' && !list.hidden) { event.stopPropagation(); hide(); button.focus(); } });
  return wrap;
}

// ---- Form bits ---------------------------------------------------------------------------------

export function field(label, control, hint) {
  const id = control.id || ('f' + Math.random().toString(36).slice(2, 8));
  control.id = id;
  const title = h('label', {for: id, id: id + '-label'}, label);
  const trigger = control.querySelector?.('[role="combobox"]');
  if (trigger) {
    trigger.setAttribute('aria-labelledby', title.id);
    title.addEventListener('click', () => trigger.focus());
  }
  return h('div', {class: 'field'}, title, control, hint ? h('small', {class: 'field-hint'}, hint) : null);
}

export const input = (props = {}) => h('input', {class: 'input', autocomplete: 'off', spellcheck: 'false', ...props});

export function select(options, value, props = {}) {
  const id = 'choice-' + Math.random().toString(36).slice(2, 10);
  const flat = options.flatMap(o => o.group ? o.options : [o]);
  let picked = flat.find(o => o.value === value) || flat[0], open = false, cursor = 0;
  const text = h('span', {class: 'choice-value'});
  const trigger = h('button', {type: 'button', class: 'input choice-trigger', role: 'combobox',
    'aria-haspopup': 'listbox', 'aria-expanded': 'false', 'aria-controls': id, ...props}, text, icon('down'));
  const control = h('div', {class: 'choice'}, trigger);
  const list = h('div', {class: 'choice-menu', id, role: 'listbox', hidden: true});
  let rows = [], observer = null;
  const enabled = () => rows.filter(r => !r.button.disabled);
  const label = () => {
    text.textContent = picked?.text || t('请选择');
    trigger.title = picked?.text || '';
    rows.forEach(r => r.button.setAttribute('aria-selected', String(r.option === picked)));
  };
  Object.defineProperty(control, 'value', {get: () => picked?.value || '', set: v => { picked = flat.find(o => o.value === v) || flat[0]; label(); }});
  const hide = (restore = false) => {
    if (!open) return;
    open = false;
    control.classList.remove('is-open');
    trigger.setAttribute('aria-expanded', 'false');
    list.remove(); list.hidden = true;
    document.removeEventListener('pointerdown', outside, true);
    window.removeEventListener('resize', position);
    document.removeEventListener('scroll', position, true);
    observer?.disconnect();
    if (restore && trigger.isConnected) trigger.focus({preventScroll: true});
  };
  control.close = hide;
  const outside = event => { if (!control.contains(event.target) && !list.contains(event.target)) hide(); };
  const position = () => {
    if (!control.isConnected) { hide(); return; }
    const rect = trigger.getBoundingClientRect();
    const width = Math.min(Math.max(rect.width, 220), window.innerWidth - 24);
    list.style.width = width + 'px';
    list.style.left = Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)) + 'px';
    const below = window.innerHeight - rect.bottom - 16, above = rect.top - 16;
    const up = below < 180 && above > below;
    list.style.maxHeight = Math.max(80, Math.min(340, up ? above : below)) + 'px';
    list.style.top = (up ? Math.max(12, rect.top - list.offsetHeight - 6) : rect.bottom + 6) + 'px';
  };
  const choose = option => {
    const changed = picked !== option;
    picked = option; label(); hide(true);
    if (changed) control.dispatchEvent(new Event('change', {bubbles: true}));
  };
  const focusAt = index => {
    const items = enabled();
    if (!items.length) return;
    cursor = (index + items.length) % items.length;
    items.forEach((r, i) => r.button.tabIndex = i === cursor ? 0 : -1);
    items[cursor].button.focus({preventScroll: true});
    items[cursor].button.scrollIntoView({block: 'nearest'});
  };
  const show = () => {
    if (trigger.disabled) return;
    document.querySelectorAll('.choice.is-open').forEach(el => el.close?.());
    if (!rows.length) {
      const add = option => {
        const button = h('button', {type: 'button', role: 'option', class: 'choice-option', disabled: option.disabled,
          tabindex: '-1', 'aria-selected': String(option === picked), onClick: () => choose(option)},
          h('span', {class: 'choice-option-copy'}, h('strong', null, option.text), option.note ? h('small', null, option.note) : null), icon('check'));
        rows.push({option, button}); list.append(button);
      };
      options.forEach(o => { if (o.group) { list.append(h('div', {class: 'choice-group'}, o.group)); o.options.forEach(add); } else add(o); });
    }
    open = true; control.classList.add('is-open'); trigger.setAttribute('aria-expanded', 'true');
    list.setAttribute('aria-label', document.getElementById(trigger.getAttribute('aria-labelledby'))?.textContent || t('选项'));
    document.body.append(list); list.hidden = false; position();
    focusAt(Math.max(0, enabled().findIndex(r => r.option === picked)));
    document.addEventListener('pointerdown', outside, true);
    window.addEventListener('resize', position);
    document.addEventListener('scroll', position, true);
    observer = new MutationObserver(() => { if (!control.isConnected) hide(); });
    observer.observe(document.body, {childList: true, subtree: true});
  };
  trigger.addEventListener('click', () => open ? hide(true) : show());
  const keys = event => {
    if (['ArrowDown', 'ArrowUp', 'Home', 'End', 'Escape'].includes(event.key)) {
      event.preventDefault(); event.stopPropagation();
      if (event.key === 'Escape') { hide(true); return; }
      if (!open) { show(); return; }
      focusAt(event.key === 'Home' ? 0 : event.key === 'End' ? enabled().length - 1 : cursor + (event.key === 'ArrowDown' ? 1 : -1));
    } else if (event.key === 'Tab') { hide(true); }
    else if (open && event.key.length === 1 && !event.ctrlKey && !event.metaKey && event.key !== ' ') {
      const found = enabled().findIndex(r => r.option.text.toLocaleLowerCase().startsWith(event.key.toLocaleLowerCase()));
      if (found >= 0) focusAt(found);
    }
  };
  trigger.addEventListener('keydown', keys); list.addEventListener('keydown', keys);
  label();
  return control;
}
