import {t, systemText} from './i18n.js';
// Talks to the console backend: same-origin JSON, a CSRF token on every change.

let csrf = null;
const listeners = {unauthorized: [], reachable: []};

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

export const setCsrf = token => { csrf = token || null; };
export const on = (event, callback) => listeners[event].push(callback);
const emit = (event, value) => listeners[event].forEach(callback => callback(value));

async function send(path, options) {
  let response;
  try {
    response = await fetch(path, {credentials: 'same-origin', cache: 'no-store', ...options});
  } catch {
    emit('reachable', false);
    throw new ApiError(t('后台暂时连不上'), 0);
  }
  emit('reachable', true);
  return response;
}

async function json(response, notifyUnauthorized = true) {
  let body = null;
  try { body = await response.json(); } catch { body = null; }
  if (!response.ok) {
    const error = new ApiError(systemText(body?.error, '操作没有完成'), response.status);
    if (response.status === 401 && notifyUnauthorized) emit('unauthorized', error);
    throw error;
  }
  if (body === null) throw new ApiError(t('后台回应异常'), response.status);
  return body;
}

export async function get(path) {
  return json(await send(path, {headers: {Accept: 'application/json'}}));
}

export async function status() {
  const result = await get('/api/auth/status');
  setCsrf(result.csrfToken);
  return result;
}

export async function post(path, body = {}, retried = false) {
  const response = await send(path, {
    method: 'POST',
    headers: {Accept: 'application/json', 'Content-Type': 'application/json', ...(csrf ? {'X-CSRF-Token': csrf} : {})},
    body: JSON.stringify(body),
  });
  // A page left open past a restart holds an old token: fetch a fresh one once and resend.
  if (response.status === 403 && !retried && !path.startsWith('/api/auth/')) {
    const fresh = await status().catch(() => null);
    if (fresh && fresh.authenticated) return post(path, body, true);
  }
  return json(response, !path.startsWith('/api/auth/'));
}

/** Downloads a file the backend sends as an attachment, under the given name. */
export async function download(path, filename) {
  const response = await send(path, {});
  if (!response.ok) {
    if (response.status === 401) emit('unauthorized', new ApiError(t('请先登录'), 401));
    throw new ApiError(t('下载没有完成'), response.status);
  }
  const url = URL.createObjectURL(await response.blob());
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1500);
}
