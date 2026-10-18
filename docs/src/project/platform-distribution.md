# Distribution across target platforms

Reviewed 2026-10-17. This comparison includes development installation, private
ongoing use and public distribution outside official stores. It is not a
release implementation or a promise that a provider accepts an application.
Platform policies change; verify the linked requirements before release.

## Practical comparison

| Target | Outside-store path | Signing, expiration and account constraints | Update ownership |
| --- | --- | --- | --- |
| Android phone/tablet | Authorized ADB install; direct signed APK; alternative marketplace | APK signing is required. Direct APKs avoid Apple's recurring provisioning model, but still depend on OS compatibility, developer-verification scope and signing-key continuity. Unknown-app approval is per installer source on Android 8+. | Direct hosting needs versioned APKs and explicit system-mediated updates or a separately implemented updater; another store supplies its own update channel. |
| Android TV / Google TV | Same APK/ADB mechanisms, with device-specific transfer/installer UI | Verify OS/ABI and TV launcher metadata. Current outside-Play verification rollout should not be generalized to all TV sideloads. ADB is a distinct developer channel. | Rehearse in-place updates on the actual TV; a downloadable APK alone does not create a usable home-screen entry or TV updater. |
| iOS / iPadOS | Xcode development install, paid registered-device/ad hoc distribution; eligible regional alternative distribution | Development and ad hoc are provisioned, constrained channels requiring renewal. Free Personal Team profiles expire after seven days. Regional marketplace/direct-web programs have separate eligibility, notarization and user/device conditions. They are not worldwide unrestricted sideloading. | Development installs require reprovisioning; ad hoc requires profile/device maintenance. Eligible distribution channels have their own signing/update rules. |
| Apple TV / tvOS | Xcode development and paid registered-device/ad hoc installation | Apple's Xcode workflow covers Personal Teams and wireless tvOS devices; verify the actual profile/account allocation and required capabilities. A paid registered-device path is explicit. Regional iPhone/iPad marketplace and web-install programs do not establish tvOS distribution. | Plan signing/profile renewal and an explicit installation process on paired hardware. There is no established independent Tachiai consumer updater. |
| Desktop HTTPS/PWA | Self-hosted website, optional browser install | No app-store listing or native provisioning profile is needed for a regular website. HTTPS, hosting/domain custody and browser compatibility remain requirements. PWA installation does not grant native routing, extension permissions or new DRM support. | Deploy versioned assets; coordinate service-worker caches and persistent configuration migrations. Keep UI/Wasm and any companion protocol versions compatible. |
| Desktop extension | Browser-specific distribution; developer loading is a separate path | Chrome durable self-hosting on Windows/macOS generally requires managed enterprise policies; Linux permits manual packed installation. Firefox supports Mozilla-signed, unlisted self-distribution. macOS Safari extensions can use a Developer ID-signed/notarized host app outside Mac App Store. | Own update metadata and signing where supported. Unpacked developer-mode loading is useful for local trials but is not a general consumer release strategy. |
| Desktop native companion | Direct Windows installer, macOS app/package or Linux packages | Separate binary identity, platform trust/signing/notarization and supported OS/ABI decisions. A companion does not automatically capture the browser's provider/media traffic. | Own installer, safe updates, protocol compatibility and rollback. Browser attachment and complete route coverage need explicit verification. |

Primary sources and detailed conditions are in the
[Android TV distribution review](android-tv-interface-review.md#distribution-including-outside-official-stores),
[Apple distribution review](tvos-interface-review.md#distribution-and-installation),
[iOS review](ios-interface-review.md) and
[desktop distribution review](desktop-browser-review.md).
Android's direct-install foundation is documented in
[alternative distribution][android-direct] and [app signing][android-signing];
the [verification FAQ][android-verification] distinguishes regions, form factors,
participating stores, direct sideloads and ADB. Do not turn one rollout date
into a permanent worldwide claim.

## Development, beta and enduring installation

TestFlight is useful for Apple beta distribution, but builds expire after
90 days and external testing has Apple review requirements. It is not a
permanent independent release channel. Enterprise distribution is for eligible
organizations' internal use, not a personal public-distribution workaround.
The Apple review records current sources and eligibility gaps; free development,
paid ad hoc, beta and public distribution must remain separate choices.

For personal ongoing use, direct Android APKs and self-hosted desktop web have
the least provisioning overhead among the proposed targets. Apple TV is not
ruled out by installation policy, but private installation brings account,
signing, pairing and renewal work. Playback feasibility remains a separate
gate on every platform.

For desktop native companions, macOS Developer ID/notarization offers a route
outside Mac App Store; Windows signing affects trust/reputation and Linux
packages need repository/update trust. The [desktop review](desktop-browser-review.md)
records the platform-specific sources. These are candidate components, not
additional applications already delivered by this repository.

## What exists in Tachiai today

The current CI workflow builds the ordinary Android application. Main/manual
jobs produce an **unsigned release APK** and a debug-signed APK with the matching
native-source companion; artifacts have limited retention. An unsigned release
APK is not a ready-to-install production release, and a debug-signed artifact is
not a public release identity. The local inspection build is intentionally
excluded from CI/distribution. See [Android development](android-development.md).

CI also produces reusable Android core and generated native/web binding
artifacts. There is no signed Apple application, TV-specific acceptance run,
hosted Tachiai web application, released extension or native companion.

Before establishing a release channel, choose stable package/bundle identities,
signing-key custody, supported OS/ABIs, artifact/source/license publication,
integrity metadata and an update path that preserves saved settings. Verify
whether changing channels preserves signatures and account storage; do not
promise interchangeable Play/direct or development/release updates without
checking their actual installed identities.

Downloaded runtime helper bundles, transport libraries and browser/DRM
components also have licensing and provider-policy implications. Distribution
permission does not establish provider authorization, entitlement, route
coverage or permission to ship a CDM/helper implementation.

[android-direct]: https://developer.android.com/distribute/marketing-tools/alternative-distribution
[android-signing]: https://developer.android.com/studio/publish/app-signing
[android-verification]: https://developer.android.com/developer-verification/guides/faq
