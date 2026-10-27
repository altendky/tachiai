import type shaka from 'shaka-player/dist/shaka-player.compiled';
import { playheadFormat, type FormatCodec, type PlayheadFormat } from './formats';

export type QualityKind = 'video' | 'audio';
export interface QualityTrack {
  kind: QualityKind; codec: FormatCodec; bitrateBps?: number;
  width?: number; height?: number; channelCount?: number; sampleRateHz?: number;
}
// null is explicit Auto; undefined belongs to the viewer and means inheritance.
export type QualityRequest = QualityTrack | null;
export interface QualityPreferences { video: QualityRequest; audio: QualityRequest }
export interface QualityControl {
  requested: QualityRequest; outcome: 'AUTO' | 'PENDING' | 'REQUESTED' | 'UNAVAILABLE';
  options: QualityTrack[]; omitted: number;
}
export interface QualitySnapshot {
  video: QualityControl; audio: QualityControl; bandwidthEstimateBps?: number;
}
export const autoPreferences = (): QualityPreferences => ({ video: null, audio: null });

export function validRequest(value: unknown, kind: QualityKind): value is QualityRequest {
  if (value === null) return true;
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const v = value as Record<string, unknown>;
  const keys = kind === 'video' ? ['kind', 'codec', 'bitrateBps', 'width', 'height'] :
    ['kind', 'codec', 'bitrateBps', 'channelCount', 'sampleRateHz'];
  if (Object.keys(v).some(key => !keys.includes(key)) || v.kind !== kind) return false;
  const number = (n: unknown, max: number) => typeof n === 'number' && Number.isSafeInteger(n) && n > 0 && n <= max;
  if (v.bitrateBps !== undefined && !number(v.bitrateBps, 1_000_000_000)) return false;
  return kind === 'video' ? ['AVC', 'HEVC', 'AV1', 'VP8', 'VP9'].includes(v.codec as string) &&
    number(v.width, 16384) && number(v.height, 16384) :
    ['AAC', 'OPUS', 'VORBIS', 'AC3', 'EAC3', 'MP3'].includes(v.codec as string) &&
    number(v.channelCount, 64) && number(v.sampleRateHz, 768000);
}

export function sameRequest(a: QualityRequest, b: QualityRequest): boolean {
  return a === null || b === null ? a === b : a.kind === b.kind && a.codec === b.codec &&
    a.bitrateBps === b.bitrateBps && a.width === b.width && a.height === b.height &&
    a.channelCount === b.channelCount && a.sampleRateHz === b.sampleRateHz;
}
export const copyRequest = (value: QualityRequest): QualityRequest => value ? { ...value } : null;
export function qualityTitle(value: QualityRequest): string {
  if (!value) return 'Auto';
  const rate = value.bitrateBps === undefined ? '?' : Math.floor(value.bitrateBps / 1000);
  return value.kind === 'video' ? `${value.width}×${value.height} ${value.codec} ${rate} kb/s` :
    `${value.codec} ${rate} kb/s ${value.channelCount} ch ${value.sampleRateHz} Hz`;
}
export function observedQualityTitle(value?: PlayheadFormat): string {
  if (!value) return 'unavailable';
  const rate = value.declaredBitrateBps === undefined ? '?' : Math.floor(value.declaredBitrateBps / 1000);
  return value.kind === 'video' ? `${value.width ?? '?'}×${value.height ?? '?'} ${value.codec} ${rate} kb/s` :
    `${value.codec} ${rate} kb/s ${value.channelCount ?? '?'} ch ${value.sampleRateHz ?? '?'} Hz`;
}
export function qualityExplanation(control: QualityControl): string {
  return { AUTO: 'Auto selection', PENDING: 'Waiting for supported tracks; Auto until available',
    REQUESTED: 'Manual selection requested; actual format is reported separately',
    UNAVAILABLE: 'Requested quality unavailable or ambiguous; Auto until available' }[control.outcome];
}

type Variant = shaka.extern.Variant;
type Stream = shaka.extern.Stream;
function descriptor(stream: Stream, kind: QualityKind): QualityTrack | undefined {
  const format = playheadFormat({ contentType: kind, codecs: stream.codecs, bandwidth: stream.bandwidth,
    width: stream.width, height: stream.height, channelsCount: stream.channelsCount,
    audioSamplingRate: stream.audioSamplingRate });
  if (!format) return;
  const value: QualityTrack = kind === 'video' ? { kind, codec: format.codec,
    bitrateBps: format.declaredBitrateBps, width: stream.width ?? undefined, height: stream.height ?? undefined } :
    { kind, codec: format.codec, bitrateBps: format.declaredBitrateBps,
      channelCount: stream.channelsCount ?? undefined, sampleRateHz: stream.audioSamplingRate ?? undefined };
  return validRequest(value, kind) ? value : undefined;
}

// This policy sees only variants already admitted by the pinned SDK's content
// criteria and support checks. It never constructs a language/role/camera group.
// The original ABR object still owns measurement, adaptation and all callbacks.
export class PlayerQuality {
  private requests = autoPreferences();
  private applied = autoPreferences();
  private applicationValid = false;
  private controls: Record<QualityKind, QualityControl> = {
    video: { requested: null, outcome: 'PENDING', options: [], omitted: 0 },
    audio: { requested: null, outcome: 'PENDING', options: [], omitted: 0 },
  };
  private manager?: shaka.extern.AbrManager;
  private switch?: shaka.extern.AbrManager.SwitchCallback;
  private allowed: Variant[] = [];
  private retired = false;
  private managerGeneration = 0;
  private changing = false;

  factory(original: shaka.extern.AbrManager.Factory): shaka.extern.AbrManager.Factory {
    return () => {
      const manager = original(), generation = ++this.managerGeneration;
      if (!this.retired) this.manager = manager;
      const current = () => !this.retired && generation === this.managerGeneration && this.manager === manager;
      return new Proxy(manager, { get: (target, key) => {
        if (key === 'init') return (callback: shaka.extern.AbrManager.SwitchCallback,
          disabled: shaka.extern.AbrManager.DisableStreamCallback) => {
          if (!current()) return;
          const guarded: shaka.extern.AbrManager.SwitchCallback = (...args) => {
            if (current() && !this.changing && this.allowed.includes(args[0])) callback(...args);
          };
          this.switch = guarded;
          target.init(guarded, (...args) => { if (current()) disabled(...args); });
        };
        if (key === 'setVariants') return (variants: Variant[], lowLatency: boolean) => {
          if (!current()) return target.setVariants([], lowLatency);
          this.allowed = this.resolve(variants);
          return target.setVariants(this.allowed, lowLatency);
        };
        const value = Reflect.get(target, key);
        return typeof value === 'function' ? value.bind(target) : value;
      } });
    };
  }

  private resolve(variants: Variant[]): Variant[] {
    // Auto uses the SDK screen restriction. Manual options must also retain a
    // physical-screen bound; pane dimensions, mix and primary role are irrelevant.
    const width = window.screen.width * window.devicePixelRatio;
    const height = window.screen.height * window.devicePixelRatio;
    const fits = (s: Stream) => !s.width || !s.height || width <= 0 || height <= 0 ||
      s.width <= width && s.height <= height || s.width <= height && s.height <= width;
    const choices = (kind: QualityKind) => {
      const streams = [...new Set(variants.map(v => v[kind]).filter((s): s is Stream => s !== null))];
      const described = streams.map(stream => ({ stream, value: descriptor(stream, kind) }))
        .filter((x): x is { stream: Stream; value: QualityTrack } => x.value !== undefined && (kind !== 'video' || fits(x.stream)));
      return described.filter(x => described.filter(y => sameRequest(x.value, y.value)).length === 1);
    };
    let eligible = variants;
    // Audio-first is meaningful only among SDK-comparable supported streams.
    // Audio-only sources keep ordinary adaptation in Auto.
    for (const kind of ['audio', 'video'] as const) {
      const options = choices(kind), request = this.requests[kind];
      const match = request && options.find(x => sameRequest(x.value, request));
      const available = match && eligible.some(v => v[kind] === match.stream);
      this.controls[kind] = { requested: copyRequest(request),
        outcome: !request ? 'AUTO' : !variants.length ? 'PENDING' : available ? 'REQUESTED' : 'UNAVAILABLE',
        options: options.slice(0, 24).map(x => ({ ...x.value })), omitted: Math.max(0, options.length - 24) };
      if (available) eligible = eligible.filter(v => v[kind] === match.stream);
      else if (kind === 'audio' && variants.some(v => v.video)) {
        const audio = [...new Set(eligible.map(v => v.audio).filter((s): s is Stream => s !== null))];
        // The SDK has already ranked content/channel/codec compatibility. Within
        // that group retain one highest declared bitrate, without global forcing.
        const best = audio.reduce<Stream | undefined>((best, s) =>
          !best || (s.bandwidth ?? 0) > (best.bandwidth ?? 0) ? s : best, undefined);
        if (best) eligible = eligible.filter(v => v.audio === best);
      }
    }
    return eligible;
  }

  request(preferences: QualityPreferences): boolean {
    if (this.retired || !validRequest(preferences.video, 'video') || !validRequest(preferences.audio, 'audio')) return false;
    this.applicationValid = false;
    this.requests = { video: copyRequest(preferences.video), audio: copyRequest(preferences.audio) };
    for (const kind of ['video', 'audio'] as const) this.controls[kind] = { ...this.controls[kind],
      requested: copyRequest(this.requests[kind]), outcome: this.requests[kind] ? 'PENDING' : 'AUTO' };
    // configure() enables the SDK manager, which can immediately suggest a
    // no-clear adaptation. Hold that callback until the explicit request can
    // dispatch once with buffer clearing; otherwise old buffered media may win.
    this.changing = true;
    return true;
  }
  matches(preferences: QualityPreferences): boolean {
    return this.applicationValid && validRequest(preferences.video, 'video') && validRequest(preferences.audio, 'audio') &&
      sameRequest(this.applied.video, preferences.video) && sameRequest(this.applied.audio, preferences.audio) &&
      sameRequest(this.requests.video, preferences.video) && sameRequest(this.requests.audio, preferences.audio);
  }
  dispatch(): void {
    this.changing = false;
    if (this.retired || !this.manager || !this.switch) return;
    const variant = this.manager.chooseVariant();
    if (variant && this.allowed.includes(variant)) this.switch(variant, true, 0);
  }
  cancelDispatch(): void { this.changing = false; }
  committed(): void {
    this.applied = { video: copyRequest(this.requests.video), audio: copyRequest(this.requests.audio) };
    this.applicationValid = !this.retired;
  }
  snapshot(): QualitySnapshot {
    const control = (kind: QualityKind) => ({ ...this.controls[kind], requested: copyRequest(this.controls[kind].requested),
      options: this.controls[kind].options.map(x => ({ ...x })) });
    const estimate = this.manager?.getBandwidthEstimate();
    return { video: control('video'), audio: control('audio'),
      bandwidthEstimateBps: estimate !== undefined && Number.isFinite(estimate) && estimate > 0 && estimate <= 1e12 ? Math.round(estimate) : undefined };
  }
  close(): void {
    this.applicationValid = false;
    this.retired = true; this.allowed = []; this.manager = undefined; this.switch = undefined;
    for (const kind of ['video', 'audio'] as const) this.controls[kind] = {
      requested: copyRequest(this.requests[kind]), outcome: 'PENDING', options: [], omitted: 0 };
  }
}
