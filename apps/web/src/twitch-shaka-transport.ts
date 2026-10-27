import shaka from 'shaka-player/dist/shaka-player.compiled';
import { createClosedBrowserFetch } from './twitch-transport';
import type { MediaCheckpoint, MediaObserver, MediaRefusal } from './twitch-media-diagnostic';

export function twitchMediaRequest(type: shaka.net.NetworkingEngine.RequestType, method: string) {
  const kinds = shaka.net.NetworkingEngine.RequestType;
  return { requestKind: type === kinds.MANIFEST ? 'manifest' as const : type === kinds.SEGMENT ? 'segment' as const : 'other' as const,
    method: method === 'GET' ? 'GET' as const : method === 'HEAD' ? 'HEAD' as const : 'OTHER' as const };
}

// This diagnostic replaces Shaka's HTTPS scheme plugin, so no built-in XHR or
// default Fetch implementation can follow redirects or send ambient cookies.
export function createTwitchMediaPlugin(admit: (uri: string) => boolean,
  beginObservation: (request: shaka.extern.Request) => MediaObserver): shaka.extern.SchemePlugin {
  const plugin: shaka.extern.SchemePlugin = (uri, request, _type, progress, received) => {
    const controller = new AbortController();
    let timeout: AbortSignal | undefined;
    const observe = beginObservation(request);
    const identity = twitchMediaRequest(_type, request.method);
    const state: { checkpoint: MediaCheckpoint } = { checkpoint: 'plugin-admission' };
    let refusal: MediaRefusal | undefined;
    const report = (step: MediaCheckpoint, why?: MediaRefusal, http?: number) => {
      state.checkpoint = step; refusal ??= why;
      observe({ ...identity, checkpoint: step, ...(refusal ? { refusal } : {}), ...(http === undefined ? {} : { http }) });
    };
    const deny = (step: MediaCheckpoint, why: MediaRefusal): never => { report(step, why); throw new Error('Media refused'); };
    const fetcher = createClosedBrowserFetch((url, method) => {
      const allowed = ['GET', 'HEAD'].includes(method) && admit(url.href);
      if (!allowed) report('fetch-admission', 'policy');
      return allowed;
    });
    const promise = (async (): Promise<shaka.extern.Response> => {
      try {
        report('plugin-admission');
        if (request.body !== null || !admit(uri)) deny('plugin-admission', 'policy');
        const headers: Record<string, string> = {};
        for (const [name, value] of Object.entries(request.headers)) {
          if (name.toLowerCase() !== 'range' || !/^bytes=[0-9]+-[0-9]*$/.test(value)) deny('plugin-admission', 'policy');
          headers.Range = value;
        }
        report('fetch');
        timeout = AbortSignal.timeout(10_000);
        const response = await fetcher(uri, { method: request.method, headers,
          signal: AbortSignal.any([controller.signal, timeout]) });
        report('response', undefined, Number.isSafeInteger(response.status) && response.status >= 100 && response.status <= 599 ? response.status : undefined);
        if (![200, 206].includes(response.status)) {
          report('response', 'http-status'); await response.body?.cancel(); throw new Error('Media refused');
        }
        const responseHeaders = Object.fromEntries(response.headers.entries());
        received(responseHeaders);
        const chunks: Uint8Array[] = [];
        let size = 0;
        const started = performance.now();
        let lastProgress = started;
        if (request.method !== 'HEAD') {
          report('body');
          if (!response.body) return deny('body', 'body-read');
          let reader: ReadableStreamDefaultReader<Uint8Array>;
          try { reader = response.body.getReader(); } catch { return deny('body', 'body-read'); }
          try {
            for (;;) {
              let next: ReadableStreamReadResult<Uint8Array>;
              try { next = await reader.read(); }
              catch {
                return deny('body', controller.signal.aborted ? 'fetch-aborted' : timeout?.aborted ? 'fetch-timeout' : 'body-read');
              }
              if (next.done) break;
              size += next.value.byteLength;
              if (size > 32 * 1024 * 1024) deny('body', 'body-limit');
              chunks.push(next.value);
              if (request.streamDataCallback) {
                try { await request.streamDataCallback(next.value); } catch { deny('body', 'processing'); }
              }
              const now = performance.now();
              try { progress(now - lastProgress, next.value.byteLength, 0); } catch { deny('body', 'processing'); }
              lastProgress = now;
            }
          } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
        } else await response.body?.cancel();
        let data: Uint8Array;
        try {
          data = new Uint8Array(size);
          let offset = 0;
          for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.length; }
        } catch { return deny(state.checkpoint, 'processing'); }
        report('complete');
        return { data, headers: responseHeaders, originalRequest: request, originalUri: uri,
          uri, status: response.status, timeMs: performance.now() - started };
      } catch {
        if (!refusal) report(state.checkpoint, controller.signal.aborted ? 'fetch-aborted' : timeout?.aborted ? 'fetch-timeout' :
          state.checkpoint === 'fetch' ? 'fetch-rejected' : 'processing');
        throw new shaka.util.Error(shaka.util.Error.Severity.CRITICAL, shaka.util.Error.Category.NETWORK,
          controller.signal.aborted ? shaka.util.Error.Code.OPERATION_ABORTED : shaka.util.Error.Code.HTTP_ERROR);
      }
    })();
    return new shaka.util.AbortableOperation(promise, () => { controller.abort(); return Promise.resolve(); });
  };
  return plugin;
}

export function installTwitchMediaTransport(admit: (uri: string) => boolean,
  beginObservation: (request: shaka.extern.Request) => MediaObserver): void {
  shaka.net.NetworkingEngine.unregisterScheme('http');
  shaka.net.NetworkingEngine.unregisterScheme('data');
  shaka.net.NetworkingEngine.unregisterScheme('https');
  shaka.net.NetworkingEngine.registerScheme('https', createTwitchMediaPlugin(admit, beginObservation),
    shaka.net.NetworkingEngine.PluginPriority.APPLICATION, true);
}
