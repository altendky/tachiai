// Observational only. Nothing in this module authorizes a CDP command, repairs
// ownership, retries a request, or changes the transport's admission decisions.
import { createHmac, randomBytes } from 'node:crypto';
import { types } from 'node:util';
import schema from './mixed-browser-lifecycle-schema.json' with { type: 'json' };

const enums = new Map(Object.entries(schema.enums).map(([key, values]) => [key, new Set(values)]));
const integers = new Map(Object.entries(schema.integers));
const booleans = new Set(schema.booleans);
const fields = new Set([...enums.keys(), ...integers.keys(), ...booleans]);
const decisions = new Set([...enums.get('firstRequest')].filter(value => !['UNSEEN', 'UNKNOWN'].includes(value)));
const commands = new Set([...enums.get('previousCommand')].filter(value => value !== 'NONE'));
const results = new Set(['OK', 'INVALID_INTERCEPTION_ID', 'OTHER']);
const retirements = new Set(['RESPONSE_SETTLED', 'NETWORK_FAILED', 'NETWORK_FINISHED']);
const errorReasons = new Set([...enums.get('errorReason')].filter(value => !['NONE', 'OTHER'].includes(value)));
const LIMIT = 2048, SEQUENCE_LIMIT = 1_000_000;
const identifier = value => typeof value === 'string' && value.length > 0 && value.length <= 256;

export function validateLifecycleObservation(value) {
  try {
    if (!value || typeof value !== 'object' || Array.isArray(value) || types.isProxy(value) ||
      ![Object.prototype, null].includes(Object.getPrototypeOf(value))) return false;
    const keys = Reflect.ownKeys(value);
    if (keys.length !== fields.size || keys.some(key => typeof key !== 'string' || !fields.has(key))) return false;
    for (const key of keys) {
      const descriptor = Object.getOwnPropertyDescriptor(value, key);
      if (!descriptor || !Object.hasOwn(descriptor, 'value') || !descriptor.enumerable) return false;
      const item = descriptor.value;
      if (enums.has(key) ? !enums.get(key).has(item) : integers.has(key)
        ? !Number.isSafeInteger(item) || item < 0 || item > integers.get(key) : typeof item !== 'boolean') return false;
    }
    return true;
  } catch { return false; }
}

export const isResponseErrorReason = value => errorReasons.has(value);

// Read only own data properties; malformed event/getter/proxy objects cannot
// execute application code or become retained diagnostic payloads.
function data(object, key) {
  if (!object || typeof object !== 'object' || types.isProxy(object)) return undefined;
  const descriptor = Object.getOwnPropertyDescriptor(object, key);
  return descriptor && Object.hasOwn(descriptor, 'value') ? descriptor.value : undefined;
}

export function createLifecycleHistory() {
  let key;
  try { key = randomBytes(32); } catch { key = undefined; }
  let complete = !!key, cleared = false, sequence = 0;
  const fetches = new Map(), networks = new Map();
  let tokens = new WeakMap();
  const next = () => {
    if (cleared || sequence >= SEQUENCE_LIMIT) { complete = false; return undefined; }
    return ++sequence;
  };
  const record = (id, create = false) => {
    if (!identifier(id) || cleared) { complete = false; return undefined; }
    if (!fetches.has(id) && create) {
      if (fetches.size >= LIMIT) { complete = false; return undefined; }
      fetches.set(id, { count: 0, first: 'UNSEEN', last: 'UNSEEN', firstSequence: 0, lastSequence: 0,
        retirement: 'NONE', retirementSequence: 0, command: 'NONE', result: 'NONE', commandSequence: 0 });
    }
    return fetches.get(id);
  };
  const network = id => {
    if (!identifier(id) || cleared) { complete = false; return undefined; }
    if (!networks.has(id)) {
      if (networks.size >= LIMIT) { complete = false; return undefined; }
      networks.set(id, { terminal: 'NONE', initiator: 'UNSEEN', redirected: false });
    }
    return networks.get(id);
  };
  const tuple = event => {
    const request = data(event, 'request');
    const values = [data(request, 'url'), data(request, 'method'), data(event, 'frameId'), data(event, 'resourceType')];
    if (!key || values.some((value, index) => typeof value !== 'string' || value.length === 0 ||
      value.length > [65536, 32, 256, 64][index])) return undefined;
    return createHmac('sha256', key).update(JSON.stringify(values)).digest();
  };
  const link = (entry, id) => {
    if (!entry || !identifier(id)) return;
    if (entry.networkId === undefined) entry.networkId = id;
    else if (entry.networkId !== id) complete = false; // Do not retain unbounded redirect/ID chains.
  };
  const empty = responseSequence => ({
    firstRequest: complete ? 'UNSEEN' : 'UNKNOWN', lastRequest: complete ? 'UNSEEN' : 'UNKNOWN',
    requestCount: 0, firstRequestSequence: 0, lastRequestSequence: 0,
    retirement: complete ? 'NONE' : 'UNKNOWN', retirementSequence: 0,
    previousCommand: 'NONE', previousResult: 'NONE', responseSequence, ackSequence: 0,
    relatedRequests: 0, tupleRelation: complete ? 'NONE' : 'UNKNOWN',
    networkTerminal: complete ? 'NONE' : 'UNKNOWN', initiator: complete ? 'UNSEEN' : 'UNKNOWN',
    errorReason: 'NONE', redirected: false, complete,
  });
  const observe = action => {
    try { action(); } catch { complete = false; }
  };

  return Object.freeze({
    request(event, decision) {
      observe(() => {
        const at = next();
        if (at === undefined) return;
        if (!decisions.has(decision)) { complete = false; return; }
        const entry = record(data(event, 'requestId'), true);
        if (!entry) return;
        if (!entry.count) {
          entry.first = decision; entry.firstSequence = at; entry.tuple = tuple(event);
        }
        entry.count = Math.min(SEQUENCE_LIMIT, entry.count + 1);
        entry.last = decision; entry.lastSequence = at;
        entry.redirected ||= identifier(data(event, 'redirectedRequestId'));
        link(entry, data(event, 'networkId'));
      });
    },

    response(event) {
      let snapshot;
      observe(() => {
        const at = next();
        const id = data(event, 'requestId'), networkId = data(event, 'networkId');
        const entry = at === undefined ? record(id) : record(id, true);
        link(entry, networkId);
        const related = identifier(networkId) ? [...fetches.entries()]
          .filter(([otherId, other]) => otherId !== id && other.networkId === networkId) : [];
        const reference = entry?.count ? entry : related.length === 1 ? related[0][1] : undefined;
        const responseTuple = tuple(event);
        const relation = reference ? reference.tuple && responseTuple
          ? reference.tuple.equals(responseTuple) ? 'SAME' : 'DIFFERENT' : 'UNKNOWN'
          : related.length ? 'UNKNOWN' : complete ? 'NONE' : 'UNKNOWN';
        const net = identifier(networkId) ? networks.get(networkId) : undefined;
        const reason = data(event, 'responseErrorReason');
        snapshot = Object.freeze({ ...empty(at ?? sequence),
          ...(entry ? { firstRequest: !entry.count && !complete ? 'UNKNOWN' : entry.first,
            lastRequest: !entry.count && !complete ? 'UNKNOWN' : entry.last, requestCount: entry.count,
            firstRequestSequence: entry.firstSequence, lastRequestSequence: entry.lastSequence,
            retirement: entry.retirement, retirementSequence: entry.retirementSequence,
            previousCommand: entry.command, previousResult: entry.result } : {}),
          relatedRequests: related.length, tupleRelation: relation,
          networkTerminal: net?.terminal ?? (complete ? 'NONE' : 'UNKNOWN'),
          initiator: net?.initiator ?? (complete ? 'UNSEEN' : 'UNKNOWN'),
          errorReason: reason === undefined ? 'NONE' : isResponseErrorReason(reason) ? reason : 'OTHER',
          redirected: !!entry?.redirected || !!net?.redirected || identifier(data(event, 'redirectedRequestId')), complete,
        });
      });
      return snapshot ?? Object.freeze(empty(sequence));
    },

    command(id, command) {
      const token = Object.freeze(Object.create(null));
      observe(() => {
        const at = next();
        if (at === undefined) return;
        if (!commands.has(command)) { complete = false; return; }
        const entry = record(id, true);
        if (!entry) return;
        entry.command = command; entry.result = 'PENDING'; entry.commandSequence = at;
        tokens.set(token, { entry, at, settled: false });
      });
      return token;
    },

    settle(token, result) {
      observe(() => {
        const owned = tokens.get(token);
        if (!owned || owned.settled || cleared) return;
        owned.settled = true;
        if (!results.has(result)) { complete = false; return; }
        if (owned.entry.commandSequence !== owned.at || next() === undefined) return;
        owned.entry.result = result;
      });
    },

    ack(snapshot) {
      if (!validateLifecycleObservation(snapshot)) { complete = false; return Object.freeze(empty(sequence)); }
      return Object.freeze({ ...snapshot, ackSequence: sequence, complete: snapshot.complete && complete });
    },

    retire(id, reason) {
      observe(() => {
        const at = next();
        if (at === undefined) return;
        if (!retirements.has(reason)) { complete = false; return; }
        const entry = record(id, true);
        if (entry && entry.retirement === 'NONE') { entry.retirement = reason; entry.retirementSequence = at; }
      });
    },

    networkRequest(event) {
      observe(() => {
        if (next() === undefined) return;
        const entry = network(data(event, 'requestId'));
        if (!entry) return;
        const initiator = data(data(event, 'initiator'), 'type');
        const preflight = initiator === 'preflight' || data(event, 'type') === 'Preflight';
        if (preflight) entry.initiator = 'PREFLIGHT_SEEN';
        else if (entry.initiator !== 'PREFLIGHT_SEEN' && (typeof initiator === 'string' || entry.initiator === 'UNSEEN'))
          entry.initiator = typeof initiator === 'string' ? 'OTHER_SEEN' : 'UNKNOWN';
        const redirect = data(event, 'redirectResponse');
        entry.redirected ||= redirect !== null && typeof redirect === 'object' && !types.isProxy(redirect);
      });
    },

    networkTerminal(event, failedLoad) {
      observe(() => {
        if (next() === undefined) return;
        const entry = network(data(event, 'requestId'));
        if (!entry) return;
        const terminal = failedLoad === false ? 'FINISHED' : failedLoad === true ?
          data(event, 'canceled') === true ? 'CANCELED' : data(event, 'canceled') === false ? 'FAILED' : 'FAILED_UNKNOWN' : 'UNKNOWN';
        if (entry.terminal !== 'NONE' && entry.terminal !== terminal) { entry.terminal = 'UNKNOWN'; complete = false; }
        else entry.terminal = terminal;
      });
    },

    clear() {
      key?.fill(0); key = undefined; complete = false; cleared = true;
      fetches.clear(); networks.clear(); tokens = new WeakMap();
    },
  });
}
