(() => {
  'use strict';
  // Provider-independent bounded hold/seek/resume. The caller establishes
  // whether the clocks share a replay or a manually chosen anchor.
  class ReplayCoordinator {
    constructor(players, now = () => performance.now()) {
      this.players = players;
      this.now = now;
      this.pending = null;
      this.requestedOffset = null;
      this.nextOffset = null;
      this.status = 'Choose a pane; Forward skips later, Backward returns earlier';
      this.last = null;
      this.disposed = false;
    }
    read() {
      try {
        const pair = this.players.map(player => player.sample());
        return pair.every(s => s && s.ready && typeof s.paused === 'boolean' &&
          typeof s.resource === 'string' && s.resource.length > 0 &&
          Number.isFinite(s.position) && Number.isFinite(s.duration) &&
          s.duration > 0 && s.position >= 0 && s.position <= s.duration) ? pair : null;
      } catch { return null; }
    }
    adjust(selected, delta) {
      if (this.disposed || this.pending || ![0, 1].includes(selected) ||
          !Number.isFinite(delta) || Math.abs(delta) > 600) return false;
      const pair = this.read();
      if (!pair) return this.unavailable();
      const base = this.nextOffset ?? this.requestedOffset ?? (pair[1].position - pair[0].position);
      return this.start(selected, base + (selected === 1 ? delta : -delta), pair);
    }
    align(selected) {
      const pair = this.read();
      return pair ? this.start(selected, 0, pair) : this.unavailable();
    }
    unavailable() {
      if (!this.pending && !this.disposed) {
        this.requestedOffset = null;
        this.nextOffset = null;
        this.status = 'Replay clocks unavailable; alignment uncertain';
      }
      return false;
    }
    start(selected, offset, pair) {
      if (this.disposed || this.pending || ![0, 1].includes(selected) || !Number.isFinite(offset)) return false;
      this.requestedOffset = offset;
      this.pending = { selected, offset, initial: pair, phase: 'holding', stable: 0,
        previous: pair, sampledAt: this.now(), started: this.now(), deadline: this.now() + 4000 };
      this.status = 'Holding both players before relative adjustment';
      try { this.players.forEach(player => player.pause()); }
      catch { this.finish('Pause failed; alignment uncertain'); return false; }
      return true;
    }
    unchanged(pair, initial) {
      return pair.every((s, i) => s.resource === initial[i].resource &&
        Math.abs(s.duration - initial[i].duration) <= 0.05);
    }
    update() {
      if (this.disposed) return null;
      const pair = this.read();
      const job = this.pending;
      const now = this.now();
      if (!job) {
        if (!pair) { this.unavailable(); this.last = null; return null; }
        if (this.last) {
          const elapsed = Math.max(0, (now - this.last.at) / 1000);
          if (!this.unchanged(pair, this.last.pair) || pair.some((s, i) =>
            Math.abs(s.position - this.last.pair[i].position) > elapsed + 2)) {
            this.requestedOffset = null;
            this.nextOffset = null;
            this.status = 'Source or timeline changed; alignment uncertain';
          }
        }
        this.last = { pair, at: now };
        return pair;
      }
      if (!pair || !this.unchanged(pair, job.initial)) {
        this.finish('Source or clock changed; adjustment cancelled, alignment uncertain');
        return pair;
      }
      if (now >= job.deadline) {
        this.finish(job.phase === 'holding' ? 'Hold timed out; alignment uncertain' : 'Seek timed out; alignment uncertain');
        return pair;
      }
      const held = pair.every(s => s.paused);
      if (job.phase === 'holding' && job.previous && pair.some((s, i) =>
        Math.abs(s.position - job.previous[i].position) > (now - job.sampledAt) / 1000 + 1)) {
        this.finish('Timeline intervention detected; adjustment cancelled, alignment uncertain');
        return pair;
      }
      const stable = held && job.previous && pair.every((s, i) =>
        Math.abs(s.position - job.previous[i].position) <= 0.01);
      job.stable = stable ? job.stable + 1 : 0;
      job.previous = pair;
      job.sampledAt = now;
      if (job.phase === 'holding' && now - job.started >= 500 && job.stable >= 2) {
        const reference = pair[1 - job.selected].position;
        const requestedTarget = reference + (job.selected === 1 ? job.offset : -job.offset);
        job.target = Math.max(0, Math.min(pair[job.selected].duration, requestedTarget));
        job.clamped = job.target !== requestedTarget;
        job.reference = reference;
        job.phase = 'seeking';
        job.stable = 0;
        job.deadline = now + 5000;
        this.status = 'Held seek requested: ' + job.target.toFixed(3) + ' s' + (job.clamped ? ' (replay boundary)' : '');
        try { this.players[job.selected].seek(job.target); }
        catch { this.finish('Seek failed; alignment uncertain'); }
      } else if (job.phase === 'seeking') {
        if (!held || Math.abs(pair[1 - job.selected].position - job.reference) > 0.05) {
          this.finish('Player intervention detected; adjustment cancelled, alignment uncertain');
        } else if (job.stable >= 2 && Math.abs(pair[job.selected].position - job.target) <= 0.05) {
          this.finish('Held clock readback matched' + (job.clamped ? ' a boundary-limited target' : '') +
            '; prior playback requested, running alignment remains uncertain', true);
        }
      }
      return pair;
    }
    finish(message, matched = false) {
      const job = this.pending;
      this.pending = null;
      this.last = null;
      if (!matched) { this.requestedOffset = null; this.nextOffset = null; }
      else this.nextOffset = job.clamped
        ? (job.selected === 1 ? job.target - job.reference : job.reference - job.target)
        : job.offset;
      let failed = false;
      if (job && !this.disposed) this.players.forEach((player, i) => {
        try {
          const current = player.sample();
          // Never resume a replacement resource or undo a user's own Play.
          if (!job.initial[i].paused && current?.ready === true && current.resource === job.initial[i].resource &&
              Math.abs(current.duration - job.initial[i].duration) <= 0.05 && current.paused) player.play();
        } catch { failed = true; }
      });
      this.status = message + (failed ? '; playback restoration partly failed' : '');
    }
    cancel() {
      if (this.pending && !this.disposed) this.finish('Adjustment cancelled; alignment uncertain');
    }
    dispose() {
      this.disposed = true;
      this.pending = null;
      this.last = null;
    }
  }
  globalThis.TachiaiReplayCoordinator = ReplayCoordinator;
})();
