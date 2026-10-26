import { milliseconds } from './timing';

// Only this closed projection leaves Shaka. Its event also contains labels,
// language and roles; never retain the event or spread SDK objects into status.
export type FormatCodec = 'AVC' | 'HEVC' | 'AV1' | 'VP8' | 'VP9' | 'AAC' |
  'OPUS' | 'VORBIS' | 'AC3' | 'EAC3' | 'MP3' | 'OTHER';
export interface VideoFormat {
  kind: 'video'; codec: FormatCodec; declaredBitrateBps?: number;
  width?: number; height?: number; frameRate?: number; observedAtPositionMs?: number;
}
export interface AudioFormat {
  kind: 'audio'; codec: FormatCodec; declaredBitrateBps?: number;
  channelCount?: number; sampleRateHz?: number; observedAtPositionMs?: number;
}
export type PlayheadFormat = VideoFormat | AudioFormat;
export interface PlayheadFormats {
  basis: 'last-sdk-buffered-playhead'; video?: VideoFormat; audio?: AudioFormat;
}

function bounded(value: unknown, maximum: number, integer = true): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 && value <= maximum &&
    (!integer || Number.isSafeInteger(value)) ? value : undefined;
}

function codec(value: unknown, kind: 'video' | 'audio'): FormatCodec {
  if (typeof value !== 'string' || value.length > 128) return 'OTHER';
  const name = value.toLowerCase();
  if (kind === 'video') {
    if (/^avc[13]\.[0-9a-f]{6}$/.test(name)) return 'AVC';
    if (/^(hev1|hvc1)\.[a-z0-9.]+$/.test(name)) return 'HEVC';
    if (/^av01\.[a-z0-9.]+$/.test(name)) return 'AV1';
    if (/^(vp8|vp8\.0|vp08\.[0-9.]+)$/.test(name)) return 'VP8';
    if (/^(vp9|vp9\.0|vp09\.[0-9.]+)$/.test(name)) return 'VP9';
  } else {
    if (/^mp4a\.40\.[0-9]+$/.test(name)) return 'AAC';
    if (name === 'opus') return 'OPUS';
    if (name === 'vorbis') return 'VORBIS';
    if (name === 'ac-3') return 'AC3';
    if (name === 'ec-3') return 'EAC3';
    if (name === 'mp3' || /^mp4a\.(69|6b)$/.test(name)) return 'MP3';
  }
  return 'OTHER';
}

export function playheadFormat(value: unknown, position?: unknown): PlayheadFormat | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const raw = value as Record<string, unknown>;
  if (raw.contentType !== 'audio' && raw.contentType !== 'video') return undefined;
  const common = {
    codec: codec(raw.codecs, raw.contentType), declaredBitrateBps: bounded(raw.bandwidth, 2_147_483_647),
    observedAtPositionMs: typeof position === 'number' && position >= 0 ? milliseconds(position) : undefined,
  };
  return raw.contentType === 'video' ? {
    kind: 'video', ...common, width: bounded(raw.width, 16384), height: bounded(raw.height, 16384),
    frameRate: bounded(raw.frameRate, 1000, false),
  } : {
    kind: 'audio', ...common, channelCount: bounded(raw.channelsCount, 64), sampleRateHz: bounded(raw.audioSamplingRate, 768000),
  };
}
