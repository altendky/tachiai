(() => {
  'use strict';
  if (globalThis.TachiaiTimingTools) return;
  // Diagnostic only: fixed numeric media state, never resource URLs or errors.
  class TimingTools {
    constructor(backend, now = () => performance.now()) {
      this.backend = backend;
      this.now = now;
      this.last = null;
      this.timer = null;
      this.disposed = false;
    }
    sample() {
      if (this.disposed) return null;
      try { return this.backend.sample(); } catch { return null; }
    }
    read() {
      return JSON.stringify({ current: this.sample(), action: this.last });
    }
    command(action) {
      if (action === 'read') return this.read();
      if (this.disposed || this.timer !== null) return this.read();
      const before = this.sample();
      if (!before) { this.last = { operation: action, result: 'unavailable' }; return this.read(); }
      let token;
      try { token = this.backend.token(); }
      catch { this.last = { operation: action, result: 'source unavailable' }; return this.read(); }
      this.last = { operation: action, before, atMs: Math.round(this.now()), result: 'requested' };
      try {
        if (action === 'play') {
          const job = this.last;
          this.backend.play()?.catch(() => { job.result = 'play request failed'; });
        }
        else if (action === 'pause') this.backend.pause();
        else if (action === 'back' || action === 'forward' || action === 'edge') {
          if (!this.backend.canSeek) this.last.result = 'unsupported for this source';
          else if (!before.paused) this.last.result = 'pause first for isolated seek';
          else if (!Number.isFinite(before.time)) this.last.result = 'clock unavailable';
          else {
            const desired = action === 'edge' ? this.backend.edge(before)
              : before.time + (action === 'forward' ? 5 : -5);
            const target = this.backend.target(before, desired);
            this.last.desired = Number.isFinite(desired) ? desired : null;
            this.last.target = Number.isFinite(target) ? target : null;
            if (!Number.isFinite(target)) this.last.result = 'no permitted seek range';
            else {
              this.backend.seek(target);
              this.last.result = target === desired ? 'seek requested' : 'boundary-limited seek requested';
            }
          }
        } else if (action === 'hold5' || action === 'hold15') {
          const job = this.last;
          this.backend.pause();
          this.timer = setTimeout(() => {
            this.timer = null;
            let same = false;
            try { same = this.backend.token() === token && Boolean(this.sample()); } catch { /* Sanitized below. */ }
            if (this.disposed || !same) {
              job.result = 'source changed; resume cancelled'; return;
            }
            job.held = this.sample();
            job.elapsedMs = Math.round(this.now() - job.atMs);
            job.result = before.paused ? 'hold elapsed; prior pause preserved' : 'hold elapsed; prior playback requested';
            if (!before.paused) {
              try {
                this.backend.play()?.catch(() => { job.result = 'resume request failed'; });
              } catch { job.result = 'resume request failed'; }
            }
          }, action === 'hold5' ? 5000 : 15000);
        } else this.last.result = 'unknown operation';
      } catch { this.last.result = 'operation failed'; }
      return this.read();
    }
    dispose() {
      this.disposed = true;
      if (this.timer !== null) clearTimeout(this.timer);
      this.timer = null;
    }
  }
  const finite = value => Number.isFinite(value) ? Math.round(value * 1000) / 1000 : null;
  const ranges = value => Array.from({ length: Math.min(value.length, 8) }, (_, i) =>
    [finite(value.start(i)), finite(value.end(i))]);
  // This backend is used only on the exact selected first-party ABEMA playback route.
  const mediaBackend = (find, kind) => ({
    canSeek: true,
    token: find,
    sample() {
      const media = find();
      if (!media) return null;
      const seekable = ranges(media.seekable);
      const time = finite(media.currentTime);
      const edge = seekable.length ? seekable[seekable.length - 1][1] : null;
      return { kind, time, duration: finite(media.duration), paused: media.paused,
        seeking: media.seeking, ready: media.readyState, seekable,
        buffered: ranges(media.buffered), edge,
        lag: kind === 'live' && edge !== null && time !== null ? finite(edge - time) : null };
    },
    pause() { find().pause(); },
    play() { return find().play(); },
    seek(target) { find().currentTime = target; },
    edge(sample) { return sample.edge; },
    target(sample, desired) {
      if (!Number.isFinite(desired) || !sample.seekable.length) return null;
      if (sample.seekable.some(([start, end]) => desired >= start && desired <= end)) return desired;
      const endpoints = sample.seekable.flat();
      return endpoints.reduce((best, point) => Math.abs(point - desired) < Math.abs(best - desired) ? point : best);
    },
  });
  globalThis.TachiaiTimingTools = { TimingTools, finite, mediaBackend };
})();
