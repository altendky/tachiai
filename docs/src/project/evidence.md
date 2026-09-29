# Evidence

This evidence set was reviewed on 2026-09-27 and 2026-09-28. Provider behavior can
change; recheck these sources when implementing or diagnosing playback.

## ABEMA

- [Supported devices and environments](https://help.abema.tv/hc/ja/articles/360013743211-ABEMA%E3%82%92%E5%88%A9%E7%94%A8%E3%81%A7%E3%81%8D%E3%82%8B%E3%83%87%E3%83%90%E3%82%A4%E3%82%B9)
  lists current Android Chrome and WebView, iPhone Safari, Windows Edge/Chrome,
  macOS Safari/Chrome, and supported television devices. Linux is not listed.
- [What ABEMA is](https://help.abema.tv/hc/ja/articles/360013822011--ABEMA-%E3%81%A8%E3%81%AF)
  describes linear television channels as free and usable without registration,
  while paid plans cover additional video content.
- [Television channels](https://help.abema.tv/hc/ja/articles/360022905712-%E3%83%86%E3%83%AC%E3%83%93%E3%81%A8%E3%81%AF)
  explains that some event channels appear only near their scheduled programs.
- [Regional availability](https://help.abema.tv/hc/ja/articles/360013447232-%E6%B5%B7%E5%A4%96%E3%81%8B%E3%82%89%E5%88%A9%E7%94%A8%E3%81%99%E3%82%8B%E3%81%93%E3%81%A8%E3%81%AF%E3%81%A7%E3%81%8D%E3%81%BE%E3%81%99%E3%81%8B)
  says some countries and programs are unavailable and VPN use is outside the
  supported environment.
- [Mobile-browser content limitations](https://help.abema.tv/hc/ja/articles/360015494032-%E8%A6%96%E8%81%B4%E3%81%A7%E3%81%8D%E3%81%AA%E3%81%84%E3%82%B3%E3%83%B3%E3%83%86%E3%83%B3%E3%83%84%E3%81%8C%E3%81%82%E3%82%8A%E3%81%BE%E3%81%99)
  say some phone and tablet browser content must be watched in the native app.
- [Pay-per-view device restrictions](https://help.abema.tv/hc/ja/articles/360045102391-%E3%83%9A%E3%82%A4%E3%83%91%E3%83%BC%E3%83%93%E3%83%A5%E3%83%BC%E3%82%92%E8%A6%96%E8%81%B4%E3%81%A7%E3%81%8D%E3%82%8B%E3%83%87%E3%83%90%E3%82%A4%E3%82%B9)
  are a concrete content-class example: Android applications and desktop
  browsers are supported while phone and tablet browsers are not.
- [One-time-password account sharing](https://help.abema.tv/hc/ja/articles/360025244332-%E3%83%AF%E3%83%B3%E3%82%BF%E3%82%A4%E3%83%A0%E3%83%91%E3%82%B9%E3%83%AF%E3%83%BC%E3%83%89%E3%82%92%E7%99%BA%E8%A1%8C%E3%81%99%E3%82%8B)
  documents linking another device with an account ID and a password valid for
  ten minutes.
- [ABEMA settings](https://help.abema.tv/hc/ja/articles/360022870852-%E3%82%A2%E3%83%97%E3%83%AA%E3%81%AE-%E8%A8%AD%E5%AE%9A-%E3%81%AB%E3%81%A4%E3%81%84%E3%81%A6)
  documents email/password and device-linking account controls.
- [Basic operations](https://help.abema.tv/hc/ja/articles/360023117251-%E5%9F%BA%E6%9C%AC%E6%93%8D%E4%BD%9C-%E4%BE%BF%E5%88%A9%E6%A9%9F%E8%83%BD%E3%81%AB%E3%81%A4%E3%81%84%E3%81%A6)
  warns that external tools must not improperly record or save content.
- [Picture-in-picture and background playback](https://help.abema.tv/hc/ja/articles/30852650425497-%E3%83%94%E3%82%AF%E3%83%81%E3%83%A3-%E3%82%A4%E3%83%B3-%E3%83%94%E3%82%AF%E3%83%81%E3%83%A3-%E3%83%90%E3%83%83%E3%82%AF%E3%82%B0%E3%83%A9%E3%82%A6%E3%83%B3%E3%83%89%E5%86%8D%E7%94%9F%E3%81%AE%E8%A8%AD%E5%AE%9A%E6%96%B9%E6%B3%95)
  document native-app coexistence features, not an embeddable player surface.
- Searches of current official ABEMA help, site, and developer material found
  no documented public iframe player, JavaScript player SDK, Android playback
  SDK, oEmbed endpoint, or playback API. Absence from the reviewed public
  material is not proof that private partner integrations do not exist.
- [ABEMA terms](https://abema.tv/about/terms) require review before expanding
  beyond intact provider playback.
- The unofficial
  [Streamlink ABEMA plugin](https://github.com/streamlink/streamlink/blob/master/src/streamlink/plugins/abematv.py)
  implements private endpoints and ABEMA-specific license handling. It is
  evidence of reverse-engineered technical feasibility, not an official API.

## Twitch

- [Embedding Twitch](https://dev.twitch.tv/docs/embed/) requires HTTPS, a
  matching `parent` parameter, approved unobscured player elements, and minimum
  dimensions.
- [Video and clip embeds](https://dev.twitch.tv/docs/embed/video-and-clips/)
  document live and VOD embeds. Mobile playback requires user interaction.
  The interactive API supports live play/pause, but seek and current time do
  not work for live streams.
- [Full interactive embed](https://dev.twitch.tv/docs/embed/everything/)
  includes Twitch-controlled login, chat, follow, and subscription behavior.
- [Twitch authentication](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth)
  provides a device-code flow for limited-input devices. Those API tokens
  should not be assumed to authenticate the embedded website session.

## Android

- [WebView guidance](https://developer.android.com/develop/ui/views/layout/webapps/webview)
  documents hosting web content and notes that app WebView data is not shared
  with the system browser.
- [`PermissionRequest`](https://developer.android.com/reference/android/webkit/PermissionRequest)
  includes protected-media access used by web content to generate EME license
  requests. Grant only explicitly recognized resources and origins.
- [Media3 live streaming](https://developer.android.com/media/media3/exoplayer/live-streaming)
  documents live offsets, live-window seeking, and small playback-speed
  corrections. These become relevant only if Tachiai obtains an authorized
  native media timeline.
- [Android TV guidance](https://developer.android.com/training/tv/games)
  says Android TV does not provide a general browser application but WebView
  may be used inside applications. Playback suitability still needs testing.

## Apple distribution

- [Ad hoc distribution](https://developer.apple.com/help/account/provisioning-profiles/create-an-ad-hoc-provisioning-profile)
  requires registered devices and an appropriate provisioning profile.
- [TestFlight](https://developer.apple.com/testflight/) supports external
  testers but involves beta review and time-limited builds.
- [`WKWebsiteDataStore`](https://developer.apple.com/documentation/webkit/wkwebsitedatastore)
  provides persistent WebView cookies and site data, but does not by itself
  establish that ABEMA permits playback in WKWebView.

## Local project references

- `/home/altendky/repos/dosegoose` is the primary reference for a
  documentation-first, multi-platform repository layout and a conventional
  Kotlin/Compose Android shell under `apps/android/`.
- `/home/altendky/repos/mujou` and `/home/altendky/repos/onshape-mcp` provide
  newer examples of CI orchestration, pre-commit, mise locking, Renovate, and
  documentation checks.
- Tachiai should copy intent and current conventions, not project-specific
  Rust architecture or stale dependency versions.
