# Security and privacy

Tachiai loads authenticated third-party pages and therefore handles sensitive
browser state even though it does not operate an account service.

## Authentication boundary

- Provider credentials are entered only into provider-controlled pages.
- Tachiai does not add key listeners, form listeners, DOM serialization, or
  debugging instrumentation to login and account pages.
- Adapter injection is enabled only on exact allowlisted playback origins and
  routes after navigation is complete.
- Passwords, cookies, OAuth tokens, license requests, and signed media URLs are
  excluded from logs, crash reports, exports, and saved presentations.
- A saved presentation contains public resource identity such as a channel or
  page URL, layout, audio policy, and requested offset—not authenticated state.

## Browser hosting

- Use normal persistent site storage so the provider, not Tachiai, owns the
  login session.
- Offer per-provider logout guidance and an explicit clear-site-data action.
- Do not silently clear cookies on upgrade or ordinary playback failure.
- Do not share a WebView data directory with unrelated untrusted content.
- Keep WebView debugging disabled in shared/release builds.
- Never ignore certificate, hostname, safe-browsing, or mixed-content errors to
  make a provider page load.
- External navigation leaves Tachiai or opens a clearly identified full-site
  surface rather than inheriting provider adapter privileges.

## Script and bridge isolation

A provider page must not receive a broad `addJavascriptInterface` object. A
compromised page or provider-side cross-site-scripting bug could otherwise call
native methods with the application's authority.

Prefer evaluating small commands into an allowlisted page and reading narrow,
structured results. If asynchronous page-to-native messages become necessary,
validate the source origin, frame, message version, command set, and payload
size before accepting them.

Shared provider scripts must be packaged with the application or loaded from a
project-controlled, integrity-protected source. They must not be updated from
an arbitrary remote URL merely to repair a selector.

## Media boundary

Focus mode changes layout around the original provider player. It does not
capture decoded frames, remove DRM, record segments, suppress advertisements,
or retransmit content.

Screenshots and screen recording may yield protected black surfaces; Tachiai
must not attempt to bypass that behavior. Diagnostic capture should be limited
to Tachiai UI and non-sensitive textual state.

## Distribution trust

People installing a sideloaded APK must trust the binary that renders provider
login pages. Keep the project source available, make builds reproducible where
practical, publish checksums, use a stable signing identity, and clearly label
debug or experimental builds. Never commit signing keys.
