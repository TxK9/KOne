// Line icons on a 24-unit square, drawn for KOne: a landscape, a tower, a bridge, a scroll.

const PATHS = {
  overview: '<path d="M2.5 19.5l5.6-7.6 3.9 4.7 2.9-3.4 6.6 6.3"/><circle cx="16.8" cy="6.6" r="2.3"/>',
  servers: '<path d="M8 21V9.6l4-2.6 4 2.6V21"/><path d="M12 7V3"/><path d="M4.5 21h15"/><path d="M10.6 12.2h2.8M10.6 15.2h2.8M10.6 18.2h2.8"/><path d="M5.5 21v-6.5H8M18.5 21v-6.5H16"/>',
  nodes: '<circle cx="5" cy="12" r="2.5"/><circle cx="18" cy="5" r="2.5"/><circle cx="18" cy="19" r="2.5"/><path d="M7.2 10.8l8.6-4.6M7.2 13.2l8.6 4.6"/>',
  routes: '<path d="M6 20.5V4.5M18 20.5V4.5"/><path d="M6 6.2c2.6 6.6 9.4 6.6 12 0"/><path d="M2 15.5h20"/><path d="M2 9.2c1.6 2.6 2.7 4 4 4.6M22 9.2c-1.6 2.6-2.7 4-4 4.6"/><path d="M9.2 10.9v4.6M12 11.7v3.8M14.8 10.9v4.6"/>',
  customers: '<circle cx="9" cy="8.4" r="3.2"/><path d="M3.4 19.6c.8-3.3 3-5 5.6-5s4.8 1.7 5.6 5"/><path d="M15.6 5.5a3.2 3.2 0 0 1 0 6"/><path d="M17.6 14.6c1.6.7 2.6 2.3 3 5"/>',
  configs: '<path d="M3.8 4.5h16.4"/><path d="M6 4.5v13.8h12V4.5"/><path d="M4.3 18.3h15.4"/><path d="M12 2.4v2.1"/><path d="M9 9h6M9 12.2h6M9 15.2h3.6"/>',
  records: '<path d="M6.5 3.5h11a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5h-11z"/><path d="M6.5 3.5A1.5 1.5 0 0 0 5 5v14a1.5 1.5 0 0 0 1.5 1.5"/><path d="M10 8h5.5M10 11.5h5.5M10 15h3.5"/>',
  moon: '<path d="M19.4 14.6A7.8 7.8 0 0 1 9.4 4.6a7.8 7.8 0 1 0 10 10z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.8v2.2M12 19v2.2M2.8 12H5M19 12h2.2M5.5 5.5l1.6 1.6M16.9 16.9l1.6 1.6M5.5 18.5l1.6-1.6M16.9 7.1l1.6-1.6"/>',
  logout: '<path d="M13.5 4.5H6.5v15h7"/><path d="M10.5 12h10M17 8.5l3.5 3.5-3.5 3.5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  search: '<circle cx="10.8" cy="10.8" r="6"/><path d="M20 20l-4.8-4.8"/>',
  refresh: '<path d="M19.4 12.5a7.5 7.5 0 1 1-2.3-6"/><path d="M19.6 4.4v4.2h-4.2"/>',
  copy: '<rect x="8.5" y="8.5" width="11" height="11" rx="2"/><path d="M15.5 8.5V6A1.5 1.5 0 0 0 14 4.5H6A1.5 1.5 0 0 0 4.5 6v8A1.5 1.5 0 0 0 6 15.5h2.5"/>',
  download: '<path d="M12 4v11M7.5 10.5L12 15l4.5-4.5"/><path d="M5 19.5h14"/>',
  upload: '<path d="M12 15.5v-11M7.5 9L12 4.5 16.5 9"/><path d="M5 19.5h14"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
  trash: '<path d="M4 6.5h16M9 6.5v-3h6v3M6.5 6.5l1 14h9l1-14M10 10v6.5M14 10v6.5"/>',
  down: '<path d="M6 9l6 6 6-6"/>',
  check: '<path d="M5 12.5l4.2 4.2L19 7"/>',
  left: '<path d="M14.5 5.5L8 12l6.5 6.5"/>',
  right: '<path d="M9.5 5.5L16 12l-6.5 6.5"/>',
  key: '<circle cx="8" cy="15.5" r="3.8"/><path d="M10.8 12.8L19.5 4.2M16.4 7.2l2.6 2.6M14 9.6l2 2"/>',
  phone: '<rect x="7" y="3" width="10" height="18" rx="2.2"/><path d="M11 18h2"/>',
  pause: '<path d="M9 6.5v11M15 6.5v11"/>',
  play: '<path d="M8.5 5.8v12.4l9.8-6.2z"/>',
  pen: '<path d="M4.5 19.5l1-4.2L15.2 5.6a1.9 1.9 0 0 1 2.7 0l.5.5a1.9 1.9 0 0 1 0 2.7L8.7 18.5z"/><path d="M13.6 7.2l3.2 3.2"/>',
  globe: '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.4 2.5 3.6 5.4 3.6 8.5s-1.2 6-3.6 8.5c-2.4-2.5-3.6-5.4-3.6-8.5S9.6 6 12 3.5z"/>',
  terminal: '<rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M7 10l3 2.5L7 15M12.5 15.5H17"/>',
  arrow: '<path d="M4.5 12h15M14 6.5l5.5 5.5-5.5 5.5"/>',
  out: '<path d="M8 16L16.5 7.5M9 7.5h7.5V15"/>',
  more: '<circle cx="5.5" cy="12" r="1.3" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.3" fill="currentColor" stroke="none"/><circle cx="18.5" cy="12" r="1.3" fill="currentColor" stroke="none"/>',
  shield: '<path d="M12 3.2l7 2.8v5.5c0 4.4-2.9 7.6-7 9.3-4.1-1.7-7-4.9-7-9.3V6z"/><path d="M8.8 12.2l2.3 2.3 4.2-4.6"/>',
  bolt: '<path d="M13 3L5.5 13.4H11L10 21l8-10.4h-5.6z"/>',
  eye: '<path d="M2.8 12c2.2-4 5.3-6 9.2-6s7 2 9.2 6c-2.2 4-5.3 6-9.2 6s-7-2-9.2-6z"/><circle cx="12" cy="12" r="2.8"/>',
  wrench: '<path d="M14.6 4.3a4.6 4.6 0 0 0-5 6.4L4 16.3a1.9 1.9 0 0 0 2.7 2.7l5.6-5.6a4.6 4.6 0 0 0 6.4-5l-2.8 2.8-2.6-.6-.6-2.6z"/>',
  ban: '<circle cx="12" cy="12" r="8.5"/><path d="M6 6l12 12"/>',
  file: '<path d="M6 3.5h8.5L19 8v12.5H6z"/><path d="M14.5 3.5V8H19"/>',
};

/** An inline SVG icon; decorative unless given a label. */
export function icon(name, label) {
  const markup = PATHS[name] || '';
  const span = document.createElement('span');
  span.className = 'icon';
  span.innerHTML = '<svg viewBox="0 0 24 24" ' + (label ? 'role="img" aria-label="' + label.replace(/"/g, '') + '"' : 'aria-hidden="true"') + '>' + markup + '</svg>';
  return span;
}

/** KOne's mark: the mountain with the small house at its foot, filled. */
export function mark(className = 'mark') {
  const span = document.createElement('span');
  span.className = className;
  span.innerHTML = '<svg viewBox="0 0 108 108" aria-hidden="true"><g transform="translate(0,-4)" fill="currentColor"><path fill-rule="evenodd" d="M12,90 L27,72 L33,66 L43,63 L49,51 L53,45 L55,38 L60,33 L64,25 L68,28 L70,29 L74,40 L76,43 L78,49 L82,50 L88,62 L95,73 L105,90 L74,87 L52,86 L34,88 Z M66,31 L69,49 L82,76 L86,85 L73,69 L65,43 Z M52,52 L55,59 L58,75 L66,83 L51,70 L47,64 Z"/><path d="M52,41 H55 V39 H56 V41 H58 V44 H56 V43 H55 V45 H52 Z"/></g></svg>';
  return span;
}
