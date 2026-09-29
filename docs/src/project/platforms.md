# Platform plan

## Android phone and tablet

This is the first implementation and validation platform. The expected shell
is a Kotlin/Compose activity hosting one browser surface per visible pane.
Android WebView exposes protected-media permission requests and persistent
site storage. One Pixel 6 produced short ABEMA replay playback only with an
unsupported desktop identity; supported mobile identity, live content,
sustained playback, and other devices still require real-device tests.

The first layout is landscape side by side. Portrait may use one large pane
and one selectable or overlaid secondary pane rather than forcing two unusably
small players.

## Android TV

The Android project should be structured so the same application can expose a
TV launcher and television-specific navigation. Android TV does not normally
provide a general browser application, but it does provide WebView APIs. That
does not guarantee identical DRM, decoder, popup, or remote-control behavior.

The TV spike must verify:

- ABEMA playback and protected-media permission on the actual device.
- simultaneous decoding of ABEMA and Twitch at acceptable quality.
- D-pad reachability of every Tachiai control and necessary provider control.
- login and popup behavior.
- audio focus and muting when two WebViews play concurrently.
- lifecycle behavior after Home, sleep, input changes, and app resume.

The available NVIDIA Shield is the first documented target device.

## Windows

ABEMA documents current Microsoft Edge and Google Chrome as supported. The
first desktop experiment should therefore preserve one of those known browser
environments. Compare a browser extension or installed-browser controller with
a WebView2 shell; do not infer that Edge DRM support automatically means every
WebView2 configuration will work.

## macOS

ABEMA documents current Safari and Chrome as supported. Compare a browser
extension with a native WKWebView shell. If one consistent desktop browser
strategy can use installed Chrome on Windows, macOS, and Linux, it may be
simpler than three embedded-browser applications.

## Linux

Linux is not in ABEMA's documented desktop support list. Installed Google
Chrome may have the necessary Widevine component, whereas Chromium, CEF,
Electron, or WebKitGTK distributions may not. Linux remains experimental until
a named distribution, browser version, and content combination are verified.

## iPhone and iPad

ABEMA documents Safari on iPhone rather than WKWebView. A Safari extension may
therefore be a more reliable first experiment than a wrapper application.
WKWebView still deserves a focused playback test because it offers persistent
site data and a native two-pane shell if ABEMA permits playback there.

Distribution is a separate limitation. Ad hoc builds require registered
devices, and external TestFlight builds require beta review and expire. iOS is
a later target even if the player experiment succeeds.

## Cross-platform conclusion

Use shared product concepts and provider adapter assets, but select each
platform's browser host from evidence. A single cross-platform UI toolkit does
not make DRM or authentication behavior uniform.
