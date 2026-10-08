// Opt-in playback-only diagnostic. Does not inspect configurations or DRM sessions.
(() => {
  const allowed = () => window === window.top
    && location.origin === 'https://abema.tv'
    && ['/now-on-air/abema-news', '/video/episode/394-72_s10_p8529'].includes(location.pathname)
    && !location.search && !location.hash;
  if (!allowed()) return;
  const prefix = 'TACHIAI_EME___NONCE__|';
  const log = console.debug.bind(console);
  const emit = (value) => { try { if (allowed()) log(prefix + value); } catch {} };
  const original = navigator.requestMediaKeySystemAccess;
  if (typeof original !== 'function') { emit('UNAVAILABLE'); return; }
  const own = Object.getOwnPropertyDescriptor(navigator, 'requestMediaKeySystemAccess');
  const apply = Reflect.apply;
  const then = Promise.prototype.then;
  const clock = performance.now.bind(performance);
  const started = clock();
  let active = true;
  let calls = 0;
  const restore = () => {
    active = false;
    try {
      if (navigator.requestMediaKeySystemAccess === observed) {
        if (own) Object.defineProperty(navigator, 'requestMediaKeySystemAccess', own);
        else delete navigator.requestMediaKeySystemAccess;
      }
    } catch { emit('RESTORE_FAILED'); }
  };
  function observed(...args) {
    if (!active || !allowed() || clock() - started >= __DURATION_MS__) {
      restore();
      return apply(original, this, args);
    }
    if (calls >= 16) return apply(original, this, args);
    const id = ++calls;
    const system = typeof args[0] !== 'string' ? 'OTHER'
      : args[0] === 'com.widevine.alpha' ? 'WIDEVINE'
      : args[0] === 'org.w3.clearkey' ? 'CLEARKEY'
      : args[0] === 'com.microsoft.playready' ? 'PLAYREADY'
      : args[0] === 'com.apple.fps' ? 'FAIRPLAY' : 'OTHER';
    const mark = (outcome) => {
      if (active && clock() - started < __DURATION_MS__) emit(id + '|' + system + '|' + outcome);
    };
    mark('REQUEST');
    let result;
    try { result = apply(original, this, args); }
    catch (error) { mark('THREW'); throw error; }
    // Do not read the fulfillment value or rejection reason. Return the same promise.
    try { apply(then, result, [() => mark('ACCEPTED'), () => mark('REJECTED')]); }
    catch { mark('OBSERVER_FAILED'); }
    return result;
  }
  try {
    Object.defineProperty(navigator, 'requestMediaKeySystemAccess', {
      configurable: true, writable: true, value: observed,
      enumerable: own ? own.enumerable : false,
    });
    setTimeout(restore, __DURATION_MS__);
    window.addEventListener('pagehide', restore, { once: true });
    emit('READY');
  } catch { restore(); emit('UNAVAILABLE'); }
})();
