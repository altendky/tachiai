import { test, expect } from '@playwright/test';
import { TwitchMediaDiagnostics, TwitchMediaObservers, ownedMediaObserver } from '../src/twitch-media-diagnostic';
import { twitchDiagnosticOutput } from '../scripts/twitch-diagnostic-output.mjs';

test('first transport refusal survives later responses and numeric SDK annotation without raw error access', () => {
  const evidence = new TwitchMediaDiagnostics();
  let http: number | undefined, failures = 0;
  const observe = ownedMediaObserver(() => true, evidence, value => { http = value; }, () => { failures++; });
  observe({ checkpoint: 'fetch', requestKind: 'manifest', method: 'GET', refusal: 'fetch-rejected' });
  observe({ checkpoint: 'response', requestKind: 'segment', method: 'HEAD', http: 200 });
  evidence.sdk({ category: 1, code: 1002, get message() { throw new Error('Do not read'); },
    get data() { throw new Error('Do not read'); }, get stack() { throw new Error('Do not read'); } });
  evidence.loadTimeout();
  expect(evidence.snapshot()).toEqual({ checkpoint: 'fetch', requestKind: 'manifest', method: 'GET',
    refusal: 'fetch-rejected', sdkCategory: 1, sdkCode: 1002 });
  expect(http).toBeUndefined(); expect(failures).toBe(1);
  const snapshot = evidence.snapshot(); snapshot.sdkCode = 1;
  expect(evidence.snapshot().sdkCode).toBe(1002);
});

test('SDK-only and explicit load deadline failures remain distinct and reject invalid numeric evidence', () => {
  for (const value of [undefined, null, true, '1002', 0, -1, 1.5, NaN, Infinity, 100000]) {
    const evidence = new TwitchMediaDiagnostics(); evidence.sdk({ category: value, code: value });
    expect(evidence.snapshot()).toEqual({ checkpoint: 'sdk', refusal: 'sdk' });
  }
  const timed = new TwitchMediaDiagnostics(); timed.loadTimeout(); timed.sdk(new Error('synthetic'));
  expect(timed.snapshot()).toEqual({ checkpoint: 'load-timeout', refusal: 'load-timeout' });
  const unreadable = new TwitchMediaDiagnostics();
  expect(() => unreadable.sdk({ get code() { throw new Error('synthetic'); } })).not.toThrow();
  expect(unreadable.snapshot()).toEqual({ checkpoint: 'sdk', refusal: 'sdk' });
});

test('filter-time request bindings never rebind an old plugin or callback to a replacement generation', () => {
  const observers = new TwitchMediaObservers(), oldRequest = {}, newRequest = {};
  const old = new TwitchMediaDiagnostics(), replacement = new TwitchMediaDiagnostics();
  let generation = 1, oldHttp = 0, newHttp = 0;
  observers.bind(oldRequest, ownedMediaObserver(() => generation === 1, old, () => { oldHttp++; }, () => {}));
  generation = 2;
  observers.bind(newRequest, ownedMediaObserver(() => generation === 2, replacement, () => { newHttp++; }, () => {}));
  const late = observers.take(oldRequest);
  late({ checkpoint: 'response', http: 403, requestKind: 'manifest', method: 'GET', refusal: 'http-status' });
  observers.take(newRequest)({ checkpoint: 'response', http: 200, requestKind: 'segment', method: 'GET' });
  observers.take(oldRequest)({ checkpoint: 'response', http: 500, refusal: 'http-status' });
  expect(old.snapshot()).toEqual({ checkpoint: 'sdk' }); expect(oldHttp).toBe(0); expect(newHttp).toBe(1);
  expect(replacement.snapshot()).toEqual({ checkpoint: 'response', requestKind: 'segment', method: 'GET' });
});

test('CLI independently projects media enums and bounded numbers only at player stages', () => {
  const privateValue = 'PRIVATE_SYNTHETIC_DIAGNOSTIC';
  const media = { checkpoint: 'fetch', refusal: 'fetch-rejected', requestKind: 'manifest', method: 'GET',
    sdkCategory: 1, sdkCode: 1002, uri: privateValue, message: privateValue };
  const safe = twitchDiagnosticOutput({ stage: 'player-load', http: 403, media, privateField: privateValue });
  expect(JSON.stringify(safe).includes(privateValue)).toBe(false);
  expect(safe).toEqual({ stage: 'player-load', http: 403, media: { checkpoint: 'fetch', refusal: 'fetch-rejected',
    requestKind: 'manifest', method: 'GET', sdkCategory: 1, sdkCode: 1002 } });
  expect(twitchDiagnosticOutput({ stage: 'token-validation', reason: 'token-type', media })).toEqual({ stage: 'token-validation' });
  expect(twitchDiagnosticOutput({ stage: 'token-validation', reason: 'expiry-invalid', media })).toEqual({ stage: 'token-validation', reason: 'expiry-invalid' });
  expect(twitchDiagnosticOutput({ stage: privateValue, media })).toEqual({ stage: 'unavailable' });
  expect(twitchDiagnosticOutput({ stage: 'player-load', media: { ...media, checkpoint: privateValue } })).toEqual({ stage: 'player-load' });
  expect(twitchDiagnosticOutput({ stage: 'player-load', media: { checkpoint: 'sdk', refusal: privateValue,
    requestKind: privateValue, method: privateValue, sdkCategory: 11, sdkCode: 100000 }, http: 0 }))
    .toEqual({ stage: 'player-load', media: { checkpoint: 'sdk' } });
  for (const number of [true, '1', 0, -1, 1.5, NaN, Infinity])
    expect(twitchDiagnosticOutput({ stage: 'player-load', http: number, media: { checkpoint: 'sdk', sdkCode: number, sdkCategory: number } }))
      .toEqual({ stage: 'player-load', media: { checkpoint: 'sdk' } });
});
