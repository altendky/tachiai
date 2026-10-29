import shaka from 'shaka-player/dist/shaka-player.compiled';
import { allowedTwitchMedia } from './twitch-proof';
import { createTwitchMediaPlugin } from './twitch-shaka-transport';
import { TwitchMediaObservers, type MediaObserver } from './twitch-media-diagnostic';

// One realm-wide scheme handler, routed by the exact request object. A URI is
// never an ownership key: two accounts/players may request identical URIs.
const bindings = new WeakMap<shaka.extern.Request, TwitchMediaRoute>();
const claimed = new WeakSet<shaka.extern.Request>();
let installed = false;
const denied = () => new shaka.util.Error(shaka.util.Error.Severity.CRITICAL,
  shaka.util.Error.Category.NETWORK, shaka.util.Error.Code.OPERATION_ABORTED);

export const twitchMediaDispatcher: shaka.extern.SchemePlugin = (...args) => {
  const request = args[1];
  const owner = bindings.get(request);
  bindings.delete(request);
  if (!owner?.active) return new shaka.util.AbortableOperation(Promise.reject(denied()), () => Promise.resolve());
  return owner.dispatch(...args);
};

export function installTwitchMediaRouter(): void {
  if (installed) return;
  installed = true;
  for (const scheme of ['http', 'https', 'data']) shaka.net.NetworkingEngine.unregisterScheme(scheme);
  shaka.net.NetworkingEngine.registerScheme('https', twitchMediaDispatcher,
    shaka.net.NetworkingEngine.PluginPriority.APPLICATION, true);
}

export class TwitchMediaRoute {
  private retired = false;
  private readonly observers = new TwitchMediaObservers();
  private readonly pending = new Set<shaka.extern.IAbortableOperation<shaka.extern.Response>>();
  private readonly plugin: shaka.extern.SchemePlugin;

  constructor(private readonly current: () => boolean) {
    this.plugin = createTwitchMediaPlugin(uri => this.active && allowedTwitchMedia(uri, false),
      request => this.observers.take(request));
  }

  get active(): boolean { return !this.retired && this.current(); }

  bind(request: shaka.extern.Request, observer: MediaObserver): void {
    if (!this.active || claimed.has(request)) throw denied();
    // Guard streamed bytes as well as the final plugin response after retirement.
    const streamed = request.streamDataCallback;
    if (streamed) request.streamDataCallback = async data => {
      if (!this.active) throw denied();
      await streamed(data);
      if (!this.active) throw denied();
    };
    this.observers.bind(request, event => { if (this.active) observer(event); });
    claimed.add(request);
    bindings.set(request, this);
  }

  dispatch(...args: Parameters<shaka.extern.SchemePlugin>): shaka.extern.IAbortableOperation<shaka.extern.Response> {
    if (!this.active) return new shaka.util.AbortableOperation(Promise.reject(denied()), () => Promise.resolve());
    const operation = this.plugin(...args);
    this.pending.add(operation);
    void operation.promise.then(() => this.pending.delete(operation), () => this.pending.delete(operation));
    const guarded = operation.promise.then(response => {
      if (!this.active) throw denied();
      return response;
    });
    return new shaka.util.AbortableOperation(guarded, () => operation.abort());
  }

  retire(): void {
    if (this.retired) return;
    this.retired = true;
    for (const operation of this.pending) void operation.abort().catch(() => {});
  }
}
