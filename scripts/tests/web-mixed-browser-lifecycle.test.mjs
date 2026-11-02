// In-memory synthetic CDP events only. No browser, network, profile or provider.
import test from 'node:test';
import assert from 'node:assert/strict';
import schema from '../../apps/web/scripts/mixed-browser-lifecycle-schema.json' with { type: 'json' };
import { createLifecycleHistory, validateLifecycleObservation, isResponseErrorReason } from '../../apps/web/scripts/mixed-browser-lifecycle.mjs';

const packet = (changes = {}) => ({ requestId: 'PRIVATE_FETCH_ID', networkId: 'PRIVATE_NETWORK_ID',
  frameId: 'PRIVATE_FRAME_ID', resourceType: 'Fetch',
  request: { url: 'https://owned.invalid/PRIVATE_PATH?token=PRIVATE_TOKEN', method: 'GET',
    headers: { Authorization: 'PRIVATE_GRANT' }, postData: 'PRIVATE_BODY' }, ...changes });
const response = changes => packet({ responseErrorReason: 'Aborted', ...changes });
const network = changes => ({ requestId: 'PRIVATE_NETWORK_ID', initiator: { type: 'script' }, ...changes });
function closed(value) {
  assert.equal(validateLifecycleObservation(value), true);
  assert.equal(Object.isFrozen(value), true);
  assert.equal(Reflect.ownKeys(value).length, 18);
  assert.equal(/PRIVATE_|owned\.invalid|Authorization|postData|https:/.test(JSON.stringify(value)), false);
  return value;
}

test('shared schema has exactly eighteen primitive fields; validator never evaluates accessors/proxies', () => {
  const original = closed(createLifecycleHistory().response(response()));
  assert.equal(Object.keys(schema.enums).length + Object.keys(schema.integers).length + schema.booleans.length, 18);
  let calls = 0;
  const getter = { ...original };
  Object.defineProperty(getter, 'firstRequest', { enumerable: true, get() { calls++; return 'UNSEEN'; } });
  const proxy = new Proxy(original, { getPrototypeOf() { calls++; throw new Error('PRIVATE'); } });
  for (const value of [null, [], Object.create({ ...original }), { ...original, url: 'PRIVATE' },
    { ...original, [Symbol('PRIVATE')]: true }, getter, proxy, { ...original, requestCount: -1 },
    { ...original, requestCount: 1.5 }, { ...original, requestCount: 1_000_001 },
    { ...original, relatedRequests: 2049 }, { ...original, responseSequence: NaN },
    { ...original, complete: 'true' }, { ...original, retirement: 'PRIVATE' },
    { ...original, previousCommand: 'PRIVATE' }]) assert.equal(validateLifecycleObservation(value), false);
  const missing = { ...original }; delete missing.complete;
  assert.equal(validateLifecycleObservation(missing), false);
  assert.equal(calls, 0);
  assert.equal(validateLifecycleObservation(Object.assign(Object.create(null), original)), true);
});

test('a skipped request remains distinct from an unseen or actually retired owner', () => {
  const history = createLifecycleHistory();
  history.request(packet(), 'SKIPPED_ORIGIN');
  const known = closed(history.response(response()));
  assert.equal(known.firstRequest, 'SKIPPED_ORIGIN'); assert.equal(known.lastRequest, 'SKIPPED_ORIGIN');
  assert.equal(known.requestCount, 1); assert.equal(known.retirement, 'NONE');
  assert.equal(known.tupleRelation, 'SAME'); assert.equal(known.previousCommand, 'NONE');
  const unseen = closed(history.response(response({ requestId: 'another', networkId: 'unrelated' })));
  assert.equal(unseen.firstRequest, 'UNSEEN'); assert.equal(unseen.requestCount, 0);
  assert.equal(unseen.tupleRelation, 'NONE'); assert.equal(unseen.complete, true);
});

test('repeat Fetch IDs preserve first/last decisions, original tuple and first retirement', () => {
  const history = createLifecycleHistory();
  history.request(packet(), 'SKIPPED_METHOD');
  history.retire('PRIVATE_FETCH_ID', 'RESPONSE_SETTLED');
  history.request(packet({ frameId: 'changed', redirectedRequestId: 'private-previous' }), 'ADMITTED');
  history.retire('PRIVATE_FETCH_ID', 'NETWORK_FAILED');
  const value = closed(history.response(response({ frameId: 'changed' })));
  assert.equal(value.firstRequest, 'SKIPPED_METHOD'); assert.equal(value.lastRequest, 'ADMITTED');
  assert.equal(value.requestCount, 2); assert.ok(value.firstRequestSequence < value.lastRequestSequence);
  assert.equal(value.retirement, 'RESPONSE_SETTLED'); assert.ok(value.retirementSequence < value.lastRequestSequence);
  assert.equal(value.tupleRelation, 'DIFFERENT'); assert.equal(value.redirected, true);
});

test('older asynchronous or duplicate settlements cannot overwrite the latest command', () => {
  const history = createLifecycleHistory(); history.request(packet(), 'ADMITTED');
  const first = history.command('PRIVATE_FETCH_ID', 'CONTINUE_REQUEST');
  const newer = history.command('PRIVATE_FETCH_ID', 'CONTINUE_RESPONSE');
  assert.deepEqual(Reflect.ownKeys(first), []); assert.equal(Object.isFrozen(first), true);
  history.settle(first, 'OTHER');
  const pending = closed(history.response(response()));
  assert.equal(pending.previousCommand, 'CONTINUE_RESPONSE'); assert.equal(pending.previousResult, 'PENDING');
  history.settle(newer, 'OK'); history.settle(newer, 'OTHER');
  const settled = closed(history.response(response()));
  assert.equal(settled.previousResult, 'OK'); assert.equal(pending.previousResult, 'PENDING');
});

test('late response after response-settled retirement keeps actual prior command/result and cancellation independently', () => {
  const history = createLifecycleHistory(); history.request(packet(), 'ADMITTED');
  const token = history.command('PRIVATE_FETCH_ID', 'CONTINUE_RESPONSE'); history.settle(token, 'OK');
  history.retire('PRIVATE_FETCH_ID', 'RESPONSE_SETTLED');
  history.networkTerminal(network({ canceled: true }), true);
  const value = closed(history.response(response()));
  assert.equal(value.retirement, 'RESPONSE_SETTLED'); assert.equal(value.previousCommand, 'CONTINUE_RESPONSE');
  assert.equal(value.previousResult, 'OK'); assert.equal(value.networkTerminal, 'CANCELED');
  assert.equal(value.errorReason, 'Aborted');
});

test('a different Fetch ID can relate to one Network ID without inventing its own request admission', () => {
  const history = createLifecycleHistory(); history.request(packet(), 'SKIPPED_METHOD');
  history.networkRequest(network()); history.networkTerminal(network({ canceled: false }), true);
  history.retire('PRIVATE_FETCH_ID', 'NETWORK_FAILED');
  const value = closed(history.response(response({ requestId: 'new-fetch' })));
  assert.equal(value.firstRequest, 'UNSEEN'); assert.equal(value.requestCount, 0);
  assert.equal(value.relatedRequests, 1); assert.equal(value.tupleRelation, 'SAME');
  assert.equal(value.retirement, 'NONE'); assert.equal(value.networkTerminal, 'FAILED');
  assert.equal(value.initiator, 'OTHER_SEEN');
  const ambiguous = closed(history.response(response({ requestId: 'third-fetch' })));
  assert.equal(ambiguous.relatedRequests, 2); assert.equal(ambiguous.tupleRelation, 'UNKNOWN');
});

test('Network preflight and redirect observations do not infer a Fetch admission or retirement', () => {
  const history = createLifecycleHistory();
  history.networkRequest(network({ initiator: { type: 'preflight' }, redirectResponse: { url: 'PRIVATE_REDIRECT' } }));
  const value = closed(history.response(response()));
  assert.equal(value.initiator, 'PREFLIGHT_SEEN'); assert.equal(value.redirected, true);
  assert.equal(value.firstRequest, 'UNSEEN'); assert.equal(value.retirement, 'NONE');
  history.networkRequest(network({ initiator: { type: 'script' } }));
  assert.equal(closed(history.response(response())).initiator, 'PREFLIGHT_SEEN');
  const resource = createLifecycleHistory(); resource.networkRequest(network({ type: 'Preflight' }));
  assert.equal(closed(resource.response(response())).initiator, 'PREFLIGHT_SEEN');
});

test('terminal classifications are closed observations and never automatically retire a Fetch owner', () => {
  for (const [event, failed, expected] of [[network({ canceled: true }), true, 'CANCELED'],
    [network({ canceled: false }), true, 'FAILED'], [network(), true, 'FAILED_UNKNOWN'],
    [network({ canceled: 'PRIVATE' }), true, 'FAILED_UNKNOWN'], [network(), false, 'FINISHED']]) {
    const history = createLifecycleHistory(); history.request(packet(), 'ADMITTED');
    history.networkTerminal(event, failed);
    const value = closed(history.response(response()));
    assert.equal(value.networkTerminal, expected); assert.equal(value.retirement, 'NONE');
  }
});

test('all fourteen known response error reasons retain only their closed spelling', () => {
  const known = schema.enums.errorReason.filter(value => !['NONE', 'OTHER'].includes(value));
  assert.equal(known.length, 14);
  for (const reason of known) {
    assert.equal(isResponseErrorReason(reason), true);
    assert.equal(closed(createLifecycleHistory().response(response({ responseErrorReason: reason }))).errorReason, reason);
  }
  for (const reason of ['NONE', 'OTHER', 'PRIVATE_URL', null, {}, 0]) {
    assert.equal(isResponseErrorReason(reason), false);
    assert.equal(closed(createLifecycleHistory().response(response({ responseErrorReason: reason }))).errorReason, 'OTHER');
  }
  assert.equal(closed(createLifecycleHistory().response(packet())).errorReason, 'NONE');
});

test('tuple fingerprints distinguish URL/method/frame/resource without exporting private input', () => {
  for (const change of [{ request: { ...packet().request, url: 'https://owned.invalid/OTHER_PRIVATE_TOKEN' } },
    { request: { ...packet().request, method: 'HEAD' } }, { frameId: 'OTHER_PRIVATE_FRAME' }, { resourceType: 'XHR' }]) {
    const history = createLifecycleHistory(); history.request(packet(), 'ADMITTED');
    assert.equal(closed(history.response(response(change))).tupleRelation, 'DIFFERENT');
  }
  let calls = 0;
  const malicious = packet(); Object.defineProperty(malicious.request, 'url', { get() { calls++; return 'PRIVATE'; } });
  const history = createLifecycleHistory(); history.request(malicious, 'ADMITTED');
  assert.equal(closed(history.response(response())).tupleRelation, 'UNKNOWN'); assert.equal(calls, 0);
});

test('response snapshots are pre-command frozen values; acknowledgment only clones current sequence', () => {
  const history = createLifecycleHistory(); history.request(packet(), 'ADMITTED');
  const before = closed(history.response(response()));
  const command = history.command('PRIVATE_FETCH_ID', 'CONTINUE_REQUEST'); history.settle(command, 'INVALID_INTERCEPTION_ID');
  const ack = closed(history.ack(before));
  assert.notEqual(ack, before); assert.equal(before.ackSequence, 0); assert.ok(ack.ackSequence > before.responseSequence);
  assert.equal(ack.previousCommand, 'NONE'); assert.equal(ack.previousResult, 'NONE');
  assert.throws(() => { before.complete = false; }, TypeError);
  assert.equal(closed(history.response(response())).previousResult, 'INVALID_INTERCEPTION_ID');
});

test('Fetch history overflow preserves known entries and marks previously unseen IDs UNKNOWN', () => {
  const history = createLifecycleHistory();
  for (let index = 0; index < 2048; index++) history.request(packet({ requestId: String(index), networkId: String(index) }), 'ADMITTED');
  history.request(packet({ requestId: 'overflow', networkId: 'overflow' }), 'SKIPPED_URL');
  const absent = closed(history.response(response({ requestId: 'overflow', networkId: 'overflow' })));
  assert.equal(absent.complete, false); assert.equal(absent.firstRequest, 'UNKNOWN'); assert.equal(absent.tupleRelation, 'UNKNOWN');
  const known = closed(history.response(response({ requestId: '0', networkId: '0' })));
  assert.equal(known.firstRequest, 'ADMITTED'); assert.equal(known.requestCount, 1); assert.equal(known.complete, false);
});

test('Network history has its own finite cap and cannot invent an unseen initiator/terminal afterward', () => {
  const history = createLifecycleHistory();
  for (let index = 0; index < 2048; index++) history.networkRequest(network({ requestId: String(index) }));
  history.networkRequest(network({ requestId: 'overflow' }));
  history.networkTerminal(network({ requestId: 'overflow', canceled: true }), true);
  const value = closed(history.response(response({ networkId: 'overflow' })));
  assert.equal(value.complete, false); assert.equal(value.initiator, 'UNKNOWN'); assert.equal(value.networkTerminal, 'UNKNOWN');
  assert.equal(closed(history.response(response({ networkId: '0' }))).initiator, 'OTHER_SEEN');
});

test('unseen request placeholders become UNKNOWN after overflow; null redirects invent no observation', () => {
  const history = createLifecycleHistory();
  history.networkRequest(network({ redirectResponse: null }));
  history.networkRequest(network({ initiator: undefined }));
  const before = closed(history.response(response()));
  assert.equal(before.redirected, false); assert.equal(before.initiator, 'OTHER_SEEN');
  assert.equal(before.firstRequest, 'UNSEEN');
  for (let index = 0; index < 2049; index++) history.networkRequest(network({ requestId: String(index) }));
  const after = closed(history.response(response()));
  assert.equal(after.firstRequest, 'UNKNOWN'); assert.equal(after.complete, false);
});

test('sequence exhaustion stays bounded and cannot allocate meaningful new history', () => {
  const history = createLifecycleHistory(), event = network();
  for (let index = 0; index < 1_000_001; index++) history.networkTerminal(event, false);
  const value = closed(history.response(response()));
  assert.equal(value.responseSequence, 1_000_000); assert.equal(value.complete, false);
  assert.equal(value.firstRequest, 'UNKNOWN'); assert.equal(closed(history.ack(value)).ackSequence, 1_000_000);
});

test('clear drops private histories/key and makes late settlements/callbacks observationally unavailable', () => {
  const history = createLifecycleHistory(); history.request(packet(), 'ADMITTED');
  const token = history.command('PRIVATE_FETCH_ID', 'CONTINUE_REQUEST');
  const before = closed(history.response(response()));
  history.clear(); history.settle(token, 'OK'); history.request(packet(), 'ADMITTED');
  const after = closed(history.response(response()));
  assert.equal(after.firstRequest, 'UNKNOWN'); assert.equal(after.previousCommand, 'NONE'); assert.equal(after.complete, false);
  assert.equal(before.firstRequest, 'ADMITTED'); assert.equal(before.complete, true);
  assert.equal(closed(history.ack(before)).complete, false);
});

test('malformed observations/mutator inputs cannot throw into transport or echo private values', () => {
  const history = createLifecycleHistory();
  for (const action of [() => history.request(null, 'PRIVATE'), () => history.retire({}, 'PRIVATE'),
    () => history.networkRequest(null), () => history.networkTerminal(null, null),
    () => history.settle(null, 'PRIVATE'), () => history.command({}, 'PRIVATE'), () => history.clear()]) assert.doesNotThrow(action);
  closed(history.response(null)); closed(history.ack({ private: 'PRIVATE_TOKEN' }));
});
