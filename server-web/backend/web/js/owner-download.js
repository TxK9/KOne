import {t} from './i18n.js';
// Keep authenticated APK requests inside the browser's own network/session.
// Completed chunks survive a retry in this sheet; no credentials go into a URL.
export function createOwnerDownload(release, {request = fetch, chunkBytes = 512 * 1024, timeoutMs = 20000, retryDelayMs = 600} = {}) {
  const {url, bytes: total, sha256} = release;
  if (!/^\/downloads\/KOne-owner-b[1-9][0-9]{0,8}\.apk$/.test(url) ||
      !Number.isSafeInteger(total) || total <= 0 || total > 256 * 1024 * 1024 || !/^[0-9a-f]{64}$/.test(sha256)) {
    throw new Error(t('安装包信息无效，请刷新页面'));
  }
  const etag = `"${sha256}"`;
  let parts = [], received = 0, running = false, completed = null;
  function fatal(message) { const error = new Error(message); error.retry = false; return error; }
  return {
    async run(signal, progress = () => {}) {
      if (running) throw new Error(t('下载正在进行'));
      if (completed) return completed;
      running = true;
      try {
        while (received < total) {
          const end = Math.min(total - 1, received + chunkBytes - 1);
          for (let attempt = 0; ; attempt++) {
            signal.throwIfAborted();
            const controller = new AbortController();
            const abort = () => controller.abort();
            signal.addEventListener('abort', abort, {once: true});
            let timer, reader;
            const resetTimeout = () => { clearTimeout(timer); timer = setTimeout(abort, timeoutMs); };
            try {
              resetTimeout();
              const response = await request(url, {credentials: 'same-origin', cache: 'no-store', redirect: 'error',
                headers: {Range: `bytes=${received}-${end}`, 'If-Range': etag}, signal: controller.signal});
              if (response.status === 401) throw fatal(t('登录已过期，请重新登录后下载'));
              if (response.status === 404) throw fatal(t('安装包已更新，请关闭后重新检查版本'));
              if (response.status >= 500) throw new Error(t('下载服务暂时不可用'));
              if (response.status !== 206 || response.headers.get('Content-Range') !== `bytes ${received}-${end}/${total}` ||
                  response.headers.get('ETag') !== etag) throw fatal(t('下载响应不完整，请刷新页面后重试'));
              reader = response.body.getReader();
              const chunk = []; let count = 0;
              while (true) {
                resetTimeout();
                const {done, value} = await reader.read();
                if (done) break;
                count += value.byteLength;
                if (count > end - received + 1) throw fatal(t('安装包大小异常'));
                chunk.push(value);
                progress({received: received + count, total, phase: 'download'});
              }
              if (count !== end - received + 1) throw new Error(t('下载连接中断'));
              parts.push(...chunk); received += count;
              break;
            } catch (error) {
              if (signal.aborted) throw signal.reason;
              if (error.retry === false) throw error;
              if (attempt >= 2) throw new Error(t('连接中断，可点继续下载；请保持当前网络和代理连接'));
              progress({received, total, phase: 'retry'});
              await new Promise(resolve => setTimeout(resolve, retryDelayMs));
            } finally {
              clearTimeout(timer);
              signal.removeEventListener('abort', abort);
              if (reader) await reader.cancel().catch(() => {});
              controller.abort();
            }
          }
        }
        signal.throwIfAborted();
        progress({received, total, phase: 'verify'});
        const blob = new Blob(parts, {type: 'application/vnd.android.package-archive'});
        const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
        signal.throwIfAborted();
        if ([...new Uint8Array(digest)].map(v => v.toString(16).padStart(2, '0')).join('') !== sha256) {
          parts = []; received = 0;
          throw fatal(t('安装包校验未通过，请重新下载'));
        }
        parts = []; completed = blob;
        return blob;
      } finally { running = false; }
    },
  };
}
