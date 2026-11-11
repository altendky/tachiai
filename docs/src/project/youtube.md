# YouTube integration

The initial Android integration imports public YouTube video links into the
existing provider-instance catalogue and resolves playback with NewPipeExtractor
v0.26.5. Media3 owns rendering, timing, audio mixing and lifecycle in the common
viewer. This is an unofficial integration; it does not establish provider support.

Only System network is supported for playback in this first implementation.
Selected saved routes fail closed. Public muxed progressive streams and
unencrypted HLS are accepted; separate video/audio streams, protected content,
account-only videos and unsupported provider responses fail rather than being
reported as playable. Import validates identity without claiming metadata or
availability. Live timing capabilities depend on the actual native timeline.

Google account connection and the subscriptions catalogue are not implemented.
The settings link opens YouTube's own subscriptions page; signing in there does
not connect an account to Tachiai. A supported account integration needs Google
OAuth application configuration, protected grants and a verified catalogue path.

The resolver performs bounded HTTPS requests to explicit provider domain
families. Redirect destinations are revalidated, cancellation owns outstanding
connections, and signed media URLs and provider response bodies stay out of logs
and saved source records. Media requests use Tachiai's existing lifetime and
origin checks. The YouTube-owned media transport follows at most five redirects,
revalidating each GoogleVideo destination before opening it. Other providers keep
their existing redirect policy. Media3's fixed progressive metadata hint is
accepted and discarded; credentials and arbitrary headers are still refused.
No browser cookies or passwords are collected.

NewPipeExtractor is GPL-3.0-or-later; its upstream license accompanies the APK.
This dependency introduces distribution obligations that must be resolved before
publishing a release. Project license files have not been changed.

The opt-in `YouTubePlaybackDeviceTest` checks a real public video for a decoded
native frame and advancing position, with muted audio and no saved-state writes.
Builds and fixture tests alone do not establish actual provider playback,
physical-device performance, mixed-provider acceptance or real-TV behavior.

On November 7, 2026 UTC, a Linux host smoke using the exact v0.26.5 resolver and
its published dependencies resolved public video `M7lc1UVf-VE` without account
credentials. Its returned public identity matched, one muxed stream and an HLS
URL were available, and a muxed range request returned HTTP 206 and 1,024 bytes.
No browser engine was involved; egress region was not independently verified.
The production bounded downloader subsequently passed the same host check,
including initial media bytes with default Java, Dalvik and Firefox user agents.

The persistent `tachiai-dev` emulator (Android 16/API 36, x86-64,
`sdk_gphone64_x86_64`) passed the final single-source and two-source checks
together in 19.953 seconds, followed by a fresh-process two-source check in
12.659 seconds. Public `M7lc1UVf-VE` produced decoded native
frames and advancing positions. Two independent sessions used the common viewer,
joint Play/Pause and volume controls; paused positions held, and cleanup was
confirmed. Audio was muted: these runs provide no acoustic or physical-device
evidence. No browser engine or Google account participated; egress region was not
independently verified. The fixtures own temporary sessions and contain no saved
provider, route or account writes. Earlier failures identified Media3's metadata
hint and an intermittent HTTP 302 media response; both are handled by the bounded
progressive/YouTube paths. Extraction is serialized around NewPipe's shared mutable
caches while native playback stays independent. All 2,486 debug JVM tests passed
with one opt-in network test skipped; the separate production host network smoke,
release isolation, inspection policy and documentation build also passed.
Mixed-provider, live, physical-TV and full persisted
picker-to-viewer acceptance remain separate checks.
