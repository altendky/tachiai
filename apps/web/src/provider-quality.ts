import type { CommandResult } from './player';
import { autoPreferences, copyRequest, validRequest, type QualityPreferences, type QualitySnapshot } from './quality';

// This finite provider proof has admitted no manual track selection. Auto means
// no imposed constraint; it does not claim that the SDK is adapting its bitrate.
export class ProviderDefaultQuality {
  private preferences = autoPreferences();
  private retired = false;
  request(value: QualityPreferences): CommandResult {
    if (this.retired) return 'unavailable';
    if (!value || typeof value !== 'object' || Object.keys(value).sort().join(',') !== 'audio,video' ||
      !validRequest(value.video, 'video') || !validRequest(value.audio, 'audio')) return 'invalid';
    this.preferences = { video: copyRequest(value.video), audio: copyRequest(value.audio) };
    return value.video || value.audio ? 'unsupported' : 'completed';
  }
  snapshot(): QualitySnapshot {
    const control = (kind: 'video' | 'audio') => ({ requested: copyRequest(this.preferences[kind]),
      outcome: this.preferences[kind] ? 'UNAVAILABLE' as const : 'AUTO' as const,
      options: [], omitted: 0, selectionPolicy: 'sdk-default-only' as const });
    return { video: control('video'), audio: control('audio') };
  }
  close(): void { this.retired = true; this.preferences = autoPreferences(); }
}
