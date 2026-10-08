(() => {
  "use strict";
  const status = document.getElementById("status");
  const start = document.getElementById("start");
  const sound = document.getElementById("sound");
  const setStatus = value => { status.textContent = value; };
  const query = new URLSearchParams(location.search);
  const channel = query.get("channel");
  const frameSession = query.get("session");
  const allowedParents = new Set(["https://abema.tv", "https://www.abema.tv"]);
  const commands = new Set(["play", "pause", "mute", "unmute", "volume_down", "volume_up"]);
  let player = null;
  let ready = false;

  if (!/^[A-Za-z0-9_]{3,25}$/.test(channel ?? "") ||
      !/^[a-f0-9]{32}$/.test(frameSession ?? "") || window.parent === window) {
    setStatus("Invalid composite configuration");
    return;
  }

  const execute = command => {
    if (!ready || player === null) return "not ready";
    switch (command) {
      case "play": player.play(); return "play requested";
      case "pause": player.pause(); return "pause requested";
      case "mute": player.setMuted(true); return "mute requested";
      case "unmute":
        if (player.getVolume() === 0) player.setVolume(0.5);
        player.setMuted(false);
        return "unmute requested";
      case "volume_down":
      case "volume_up": {
        const current = player.getVolume();
        if (!Number.isFinite(current)) return "failed";
        const volume = Math.min(1, Math.max(0,
          Math.round((current + (command === "volume_up" ? 0.1 : -0.1)) * 10) / 10));
        player.setVolume(volume);
        return `volume ${Math.round(volume * 100)}% requested`;
      }
      default: return "failed";
    }
  };

  const requestKeys = ["version", "type", "requestId", "command", "frameSession"];
  window.addEventListener("message", event => {
    if (event.source !== window.parent || !allowedParents.has(event.origin)) return;
    const data = event.data;
    if (!data || typeof data !== "object" || Array.isArray(data) ||
        Object.keys(data).length !== requestKeys.length ||
        !requestKeys.every(key => Object.hasOwn(data, key)) ||
        data.version !== 1 || data.type !== "command" ||
        !Number.isSafeInteger(data.requestId) || data.requestId <= 0 ||
        data.frameSession !== frameSession || !commands.has(data.command)) return;
    let outcome = "failed";
    try { outcome = execute(data.command); } catch (_) { /* Never return SDK error details. */ }
    setStatus(outcome);
    window.parent.postMessage({
      version: 1, type: "result", requestId: data.requestId,
      channel, frameSession, status: outcome
    }, event.origin);
  });

  const directCommand = command => {
    try { setStatus(execute(command)); } catch (_) { setStatus("Player command failed"); }
  };
  start.addEventListener("click", () => directCommand("play"));
  sound.addEventListener("click", () => directCommand("unmute"));
  window.addEventListener("error", () => setStatus("Twitch page error"));
  window.addEventListener("unhandledrejection", () => setStatus("Twitch promise rejected"));

  try {
    player = new Twitch.Player("player", {
      channel, width: "100%", height: "100%", autoplay: true, muted: true,
      parent: ["appassets.androidplatform.net", "abema.tv", "www.abema.tv"]
    });
    [
      [Twitch.Player.ONLINE, "Online"], [Twitch.Player.OFFLINE, "Offline"],
      [Twitch.Player.PLAYING, "Playing"], [Twitch.Player.PAUSE, "Paused"],
      [Twitch.Player.ENDED, "Ended"],
      [Twitch.Player.PLAYBACK_BLOCKED, "Tap Start or the player to begin"]
    ].forEach(([event, label]) => player.addEventListener(event, () => setStatus(label)));
    player.addEventListener(Twitch.Player.READY, () => {
      ready = true;
      start.disabled = false;
      sound.disabled = false;
      setStatus("Ready");
    });
  } catch (_) {
    setStatus("Twitch embed unavailable");
  }
})();
