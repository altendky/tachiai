import shaka from 'shaka-player/dist/shaka-player.compiled';
import { createClosedBrowserFetch } from './twitch-transport';

// This diagnostic replaces Shaka's HTTPS scheme plugin, so no built-in XHR or
// default Fetch implementation can follow redirects or send ambient cookies.
export function installTwitchMediaTransport(admit: (uri: string) => boolean,
  observedStatus: (status: number) => void): void {
  const fetcher = createClosedBrowserFetch((url, method) =>
    ['GET', 'HEAD'].includes(method) && admit(url.href));
  const plugin: shaka.extern.SchemePlugin = (uri, request, _type, progress, received) => {
    const controller = new AbortController();
    const promise = (async (): Promise<shaka.extern.Response> => {
      try {
        if (request.body !== null || !admit(uri)) throw new Error();
        const headers: Record<string, string> = {};
        for (const [name, value] of Object.entries(request.headers)) {
          if (name.toLowerCase() !== 'range' || !/^bytes=[0-9]+-[0-9]*$/.test(value)) throw new Error();
          headers.Range = value;
        }
        const response = await fetcher(uri, { method: request.method, headers,
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)]) });
        if (Number.isSafeInteger(response.status) && response.status >= 100 && response.status <= 599) observedStatus(response.status);
        if (![200, 206].includes(response.status)) { await response.body?.cancel(); throw new Error(); }
        const responseHeaders = Object.fromEntries(response.headers.entries());
        received(responseHeaders);
        const chunks: Uint8Array[] = [];
        let size = 0;
        const started = performance.now();
        let lastProgress = started;
        if (request.method !== 'HEAD') {
          if (!response.body) throw new Error();
          const reader = response.body.getReader();
          try {
            for (;;) {
              const next = await reader.read();
              if (next.done) break;
              size += next.value.byteLength;
              if (size > 32 * 1024 * 1024) throw new Error();
              chunks.push(next.value);
              if (request.streamDataCallback) await request.streamDataCallback(next.value);
              const now = performance.now();
              progress(now - lastProgress, next.value.byteLength, 0);
              lastProgress = now;
            }
          } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
        } else await response.body?.cancel();
        const data = new Uint8Array(size);
        let offset = 0;
        for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.length; }
        return { data, headers: responseHeaders, originalRequest: request, originalUri: uri,
          uri, status: response.status, timeMs: performance.now() - started };
      } catch {
        throw new shaka.util.Error(shaka.util.Error.Severity.CRITICAL, shaka.util.Error.Category.NETWORK,
          controller.signal.aborted ? shaka.util.Error.Code.OPERATION_ABORTED : shaka.util.Error.Code.HTTP_ERROR);
      }
    })();
    return new shaka.util.AbortableOperation(promise, () => { controller.abort(); return Promise.resolve(); });
  };
  shaka.net.NetworkingEngine.unregisterScheme('http');
  shaka.net.NetworkingEngine.unregisterScheme('data');
  shaka.net.NetworkingEngine.unregisterScheme('https');
  shaka.net.NetworkingEngine.registerScheme('https', plugin, shaka.net.NetworkingEngine.PluginPriority.APPLICATION, true);
}
