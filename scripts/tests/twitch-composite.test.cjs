const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const { test } = require("node:test");
const { runInNewContext } = require("node:vm");

const source = readFileSync(resolve(__dirname,
  "../../apps/android/app/src/main/assets/twitch/composite.js"), "utf8");
const session = "1234567890abcdef1234567890abcdef";

function harness(search = `?channel=arcajazz&session=${session}`) {
  const listeners = {};
  const events = {};
  const calls = [];
  const replies = [];
  const elements = Object.fromEntries(["status", "start", "sound"].map(id =>
    [id, { disabled: true, addEventListener: (event, fn) => { events[`${id}:${event}`] = fn; } }]));
  const parent = { postMessage: (data, origin) => replies.push({ data, origin }) };
  const state = { volume: 0.5, muted: true, fail: false };
  const record = (method, value) => {
    if (state.fail) throw new Error("secret provider detail");
    calls.push([method, value]);
  };
  function Player(id, options) {
    calls.push(["construct", id, options]);
    this.addEventListener = (event, fn) => { events[event] = fn; };
    this.play = () => record("play");
    this.pause = () => record("pause");
    this.setMuted = value => { record("muted", value); state.muted = value; };
    this.setVolume = value => { record("volume", value); state.volume = value; };
    this.getVolume = () => state.volume;
  }
  for (const event of ["READY", "ONLINE", "OFFLINE", "PLAYING", "PAUSE", "ENDED", "PLAYBACK_BLOCKED"]) {
    Player[event] = event;
  }
  const window = { parent, addEventListener: (event, fn) => { listeners[event] = fn; } };
  runInNewContext(source, {
    document: { getElementById: id => elements[id] }, location: { search },
    URLSearchParams, window, Twitch: { Player }
  });
  const request = (command = "play", changes = {}, eventChanges = {}) => {
    listeners.message({ source: parent, origin: "https://abema.tv",
      data: { version: 1, type: "command", requestId: 1, command, frameSession: session, ...changes },
      ...eventChanges });
  };
  return { elements, events, calls, replies, state, request, parent };
}

test("SDK preserves the original embed and declares every ancestor", () => {
  const h = harness();
  const options = h.calls[0][2];
  assert.equal(options.channel, "arcajazz");
  assert.equal(options.muted, true);
  assert.equal(options.width, "100%");
  assert.deepEqual(Array.from(options.parent), ["appassets.androidplatform.net", "abema.tv", "www.abema.tv"]);
});

test("not-ready commands acknowledge without calling SDK controls", () => {
  const h = harness();
  h.request();
  assert.equal(h.replies[0].data.status, "not ready");
  assert.equal(h.calls.length, 1);
  assert.equal(h.elements.start.disabled, true);
});

test("wrong source, origin, session or schema is silently rejected", () => {
  const h = harness();
  h.events.READY();
  for (const origin of ["http://abema.tv", "https://abema.tv:444", "https://abema.tv.evil.example", "null"]) {
    h.request("play", {}, { origin });
  }
  h.request("play", {}, { source: {} });
  for (const changes of [
    { version: 2 }, { type: "other" }, { requestId: 0 }, { requestId: 1.5 },
    { requestId: Number.MAX_SAFE_INTEGER + 1 }, { frameSession: "stale" },
    { command: "seek" }, { command: {} }, { extra: "unexpected" }
  ]) h.request("play", changes);
  for (const data of [null, [], "play", {}]) h.request("play", {}, { data });
  assert.equal(h.replies.length, 0);
  assert.equal(h.calls.length, 1);
});

test("commands are acknowledged only as requests to the exact parent origin", () => {
  const h = harness();
  h.events.READY();
  h.request("pause", { requestId: 4 }, { origin: "https://www.abema.tv" });
  assert.equal(h.calls.at(-1)[0], "pause");
  assert.equal(h.replies[0].origin, "https://www.abema.tv");
  assert.equal(h.replies[0].data.requestId, 4);
  assert.equal(h.replies[0].data.frameSession, session);
  assert.equal(h.replies[0].data.status, "pause requested");
  h.request("mute");
  assert.equal(h.state.muted, true);
  h.state.volume = 0;
  h.request("unmute");
  assert.equal(h.state.volume, 0.5);
  assert.equal(h.state.muted, false);
});

test("volume steps are rounded and bounded; invalid values fail closed", () => {
  const h = harness();
  h.events.READY();
  for (const [volume, command, expected] of [[0.95, "volume_up", 1], [0.02, "volume_down", 0], [0.3, "volume_up", 0.4]]) {
    h.state.volume = volume;
    h.request(command);
    assert.equal(h.state.volume, expected);
    assert.equal(h.replies.at(-1).data.status, `volume ${expected * 100}% requested`);
  }
  h.state.volume = NaN;
  h.request("volume_up");
  assert.equal(h.replies.at(-1).data.status, "failed");
});

test("SDK failures never expose provider error details", () => {
  const h = harness();
  h.events.READY();
  h.state.fail = true;
  h.request();
  assert.equal(h.replies.at(-1).data.status, "failed");
  assert.equal(h.elements.status.textContent, "failed");
  h.events["start:click"]();
  assert.equal(h.elements.status.textContent, "Player command failed");
});

test("direct Start and Sound remain available for user activation", () => {
  const h = harness();
  h.events.READY();
  assert.equal(h.elements.start.disabled, false);
  assert.equal(h.elements.sound.disabled, false);
  h.events["start:click"]();
  h.events["sound:click"]();
  assert.equal(h.calls[1][0], "play");
  assert.equal(h.state.muted, false);
  assert.equal(h.replies.length, 0);
});

test("invalid channel or missing frame session prevents SDK initialization", () => {
  for (const search of ["?channel=bad-channel&session=" + session, "?channel=arcajazz", "?channel=arcajazz&session=bad"]) {
    const h = harness(search);
    assert.equal(h.calls.length, 0);
    assert.equal(h.elements.status.textContent, "Invalid composite configuration");
  }
});

function parentHarness() {
  // Execute the actual embedded adapter script, substituting its fixed Kotlin constants.
  const kotlin = readFileSync(resolve(__dirname,
    "../../apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/diagnostic/AbemaTwitchSingleWebContentsProbe.kt"), "utf8");
  const script = kotlin.split('return """')[1].split('""".trimIndent()')[0]
    .replaceAll("${TwitchCompositeEndpoint.ORIGIN}", "https://appassets.androidplatform.net")
    .replaceAll("${TwitchCompositeEndpoint.PAGE_PATH}", "/assets/twitch/composite.html")
    .replaceAll("$twitchChannel", "arcajazz");
  const listeners = {};
  const frames = new Map();
  const location = new URL("https://abema.tv/video/episode/test");
  let timer;
  let nonce = 0;
  const sent = [];
  const window = {
    addEventListener: (event, fn) => { listeners[event] = fn; },
    removeEventListener: event => { delete listeners[event]; }
  };
  const context = { window, location, URL, Uint32Array,
    crypto: { getRandomValues: values => values.fill(++nonce) },
    setInterval: fn => { timer = fn; return 1; }, clearInterval: () => { timer = null; },
    document: {
      getElementById: id => frames.get(id),
      createElement: () => {
        const frame = { style: {}, contentWindow: {
          postMessage: (data, origin) => sent.push({ data, origin })
        }, remove: () => frames.delete(frame.id) };
        return frame;
      },
      documentElement: { appendChild: frame => frames.set(frame.id, frame) }
    }
  };
  runInNewContext(script, context);
  const frame = () => frames.get("tachiai-twitch-audio-focus-probe");
  const reply = (changes = {}, eventChanges = {}) => {
    listeners.message({ source: frame().contentWindow, origin: "https://appassets.androidplatform.net",
      data: { version: 1, type: "result", requestId: 11, channel: "arcajazz",
        frameSession: window.__tachiaiComposite.frameSession, status: "play requested", ...changes },
      ...eventChanges });
  };
  return { window, location, frame, reply, tick: () => timer(), frames, listeners,
    hasTimer: () => timer !== null, context, sent };
}

test("native endpoint sends only to the wrapper and polls the matching request", () => {
  const kotlin = readFileSync(resolve(__dirname,
    "../../apps/android/app/src/main/kotlin/net/fstab/tachiai/provider/twitch/TwitchCompositeEndpoint.kt"), "utf8");
  const substitute = script => script.replaceAll("$requestId", "11")
    .replaceAll("$name", "play").replaceAll("$ORIGIN", "https://appassets.androidplatform.net");
  const send = substitute(kotlin.split('send = """')[1].split('""".trimIndent()')[0]);
  const poll = substitute(kotlin.split('poll = """')[1].split('""".trimIndent()')[0]);
  const h = parentHarness();
  assert.equal(runInNewContext(send, h.context), "pending");
  assert.equal(h.sent[0].origin, "https://appassets.androidplatform.net");
  assert.equal(h.sent[0].data.requestId, 11);
  assert.equal(h.sent[0].data.command, "play");
  assert.equal(h.sent[0].data.frameSession, h.window.__tachiaiComposite.frameSession);
  assert.equal(runInNewContext(poll, h.context), null);
  h.reply();
  assert.equal(runInNewContext(poll, h.context), "play requested");
  h.window.__tachiaiComposite.requestId = 12;
  assert.equal(runInNewContext(poll, h.context), null);
  h.location.pathname = "/account";
  assert.equal(runInNewContext(send, h.context), "unavailable");
  assert.equal(runInNewContext(poll, h.context), "unavailable");
  assert.equal(h.sent.length, 1);
});

test("parent rejects wrong origin/source and stale or malformed acknowledgments", () => {
  const h = parentHarness();
  const endpoint = h.window.__tachiaiComposite;
  endpoint.requestId = 11;
  for (const changes of [
    { requestId: 10 }, { frameSession: "stale" }, { channel: "other" },
    { version: 2 }, { type: "command" }, { status: "raw secret error" },
    { status: "volume 101% requested" }, { extra: "unexpected" }
  ]) h.reply(changes);
  h.reply({}, { origin: "https://player.twitch.tv" });
  h.reply({}, { source: {} });
  assert.equal(endpoint.result, null);
  h.reply();
  assert.equal(endpoint.result, "play requested");
});

test("replacement changes session and leaving playback routes removes all probe state", () => {
  const h = parentHarness();
  const endpoint = h.window.__tachiaiComposite;
  const oldFrame = h.frame();
  const oldSession = endpoint.frameSession;
  endpoint.requestId = 11;
  oldFrame.remove();
  h.tick();
  assert.notEqual(endpoint.frameSession, oldSession);
  assert.equal(endpoint.requestId, null);
  endpoint.requestId = 11;
  h.reply({ frameSession: oldSession });
  h.reply({}, { source: oldFrame.contentWindow });
  assert.equal(endpoint.result, null);
  h.location.pathname = "/account";
  h.tick();
  assert.equal(h.frames.size, 0);
  assert.equal(h.window.__tachiaiComposite, undefined);
  assert.equal(h.listeners.message, undefined);
  assert.equal(h.hasTimer(), false);
});
