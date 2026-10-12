# Language assistance for provider account pages

Research for [issue #198](https://github.com/altendky/tachiai/issues/198), reviewed
2026-10-12. The first case is understanding ABEMA's Japanese account pages while
connecting an existing account by email. This is a discussion document;
recommendations below are proposals, not an implementation or architecture
decision. No credentials or authenticated page contents were sent to a
translation service during this research.

## Current flow and evidence

The implemented [ABEMA email connection](abema-catalog-access.md#existing-account-email-connection-implementation)
opens the provider's account page in an isolated WebView using the captured
provider route. The user switches accounts, chooses email, enters their address
and emailed code on ABEMA's pages, opens Account management, then returns to
Tachiai for native validation. Native account adoption and revalidation have
bounded acceptance on tachiai-dev; translation was not tested. This emulator
observation does not establish phone, tablet or TV behavior.

ABEMA's [official account-sharing instructions](https://help.abema.tv/hc/ja/articles/28570588900377)
describe this browser email/code sequence, as well as separate app/TV account
sharing methods. Other authentication methods remain the human-led
[issue #196](https://github.com/altendky/tachiai/issues/196); language assistance
must not quietly replace email setup with another method. These reviewed public
instructions do not establish an English account-page language setting.

The assistance needs are different at each stage:

| Stage | What needs explaining | Evidence limit |
| --- | --- | --- |
| Account selection | Which control switches an existing account; email versus other methods | Public instructions and the completed email flow establish the sequence. |
| Email and code | Send, code entry, completion, resend and expiry meanings | Basic steps are documented; every possible validation error has not been observed. |
| Account management | Whether the provider page shows the intended account rather than a guest | Opening the page alone is not native connection success. |
| Consent or alternate approval | What is being shared or approved, and how to cancel | Other methods need their own verified flow; do not invent a generic consent screen. |
| Refusal | Region denial versus display failure, wrong/expired code or network failure | The two different Sorry pages were owner-observed; a translator must not collapse them into one diagnosis. |
| Return to Tachiai | Return, then Finish email connection and its validation result | Tachiai-owned controls already have English text; browser completion is separate from Connected. |

Public labels suitable for a reviewed glossary include アカウント管理 (Account
management), メールアドレス (Email address), 送信 (Send) and 完了する (Complete).
The official guide says アカウントを切り替える; an owner-observed page instead
used アカウント切り替え for the same general account-switch action. Keep the
original Japanese beside its meaning and review variants. These examples are
research notes, not a complete, human-reviewed glossary or an error classifier.

## Constraints from the current implementation

Follow the [authentication and privacy boundaries](security-and-privacy.md):
credential entry stays on provider-controlled pages, with no login-page script
injection, native JavaScript bridge, password collection, cookie transfer or
secret-bearing diagnostics. The approval Activity sets `FLAG_SECURE` and disables
WebView debugging. Ordinary screenshots are therefore an unsuitable acquisition
path for a built-in OCR feature. Android documents screenshot and non-secure
display protection, while warning that the flag is not a complete defense
against overlays. This does not prove the behavior of every privileged system
translator. [Android secure-activity documentation](https://developer.android.com/security/fraud-prevention/activities)

The WebView proxy is owned by the isolated approval process, with destination
admission, normal TLS, route proof and confirmed cleanup. Opening the same URL in
Chrome or a Custom Tab does not inherit that proxy, private browser profile or
passive grant handoff. The saved Windscribe route's working email observation
does not verify an external browser on the same route. Assistance must preserve
the selected route and provider's real denial. A translation/model download is
a separate network operation, not permission to widen provider network policy.

## Options and fit

All external sources linked here were accessed 2026-10-12. Documented capability
is distinguished from this project's observations and proposed integrations.

| Option | Content accessed or disclosed | Fit and main limitation | Cost and maintenance |
| --- | --- | --- | --- |
| Provider language setting | Provider handles its own page and account data | Best fit if ABEMA offers a verified English account UI; currently unestablished | Low integration effort; provider controls wording and availability. |
| Separate bilingual guidance | Maintained public labels/instructions; no live form values or page extraction | Fits the existing routed WebView, including secure pages; unknown errors remain untranslated | Editorial review and updates when provider wording changes; no runtime translation service in this proposal. |
| Chrome page/selected-text translation | Page translation sends pages to Google; selected-text processing and secret-field exclusion need verification | Browser capability is documented, but replacing the current WebView requires separate route/account architecture | Less translation implementation, more browser integration and external-service dependency. |
| Android framework translation | Text explicitly supplied by participating views to an available on-device service | Native-view APIs exist; installed WebView support and safe field selection are not established | Capability/service checks plus engine/OEM-specific validation; no universal availability. |
| User-invoked screen translation | Visible screen content becomes input to a system/assistant service | Useful on supported phones, but secure-page behavior and processing guarantees are unverified | Device/service dependency and user steps; touch tools are not a TV baseline. |
| Local translation/OCR SDK | App must obtain a string or image before local processing | Local translation is feasible for guidance/public text; secure login-page acquisition remains unresolved | Models, storage, download/lifecycle handling, attribution and quality review. |

These costs include human review, development and device resources, not just
service fees. Fixed guidance requires no runtime translation service. Google
describes [ML Kit as a free SDK](https://developers.google.com/ml-kit/guides),
although integration, model downloads and quality review still cost effort and
resources. Monetary terms for any other service or future cloud integration
would need checking before selection; no paid service is selected here.

### Provider-native language

First inspect an explicitly offered provider language control, if one exists.
Changing Android or browser language does not itself establish that ABEMA will
localize its account UI. Reviewed ABEMA
[settings guidance](https://help.abema.tv/hc/ja/articles/360022870852) and account
instructions do not settle this question; absence in these pages is not proof
that no such feature exists. If verified, the user could select English and
continue the unchanged provider flow. If unavailable, retain the Japanese page
and offer separate guidance. Provider-native terminology would avoid a second
party translating consent or account errors.

### Browser translation and Custom Tabs

Chrome Android documents whole-page translation from its menu and selected-text
translation from the selection menu. Its settings describe sending foreign
language pages to Google Translate. This is not an offline guarantee or evidence
that email, code, password and account values are excluded. It also separates
Chrome's own UI language from page translation. [Chrome Android help](https://support.google.com/chrome/answer/173424?co=GENIE.Platform%3DAndroid&hl=en)

Custom Tabs use a browser's rendering engine and browser state; available
features vary by browser/version. Neither their general availability nor a
Chrome Translate menu proves the same feature in Tachiai's WebView or every
Custom Tab. [Custom Tabs overview](https://developer.chrome.com/docs/android/custom-tabs),
[browser support](https://developer.chrome.com/docs/android/custom-tabs/browser-support)

A possible future flow would have the user explicitly request browser
translation, review its processing implications, complete provider sign-in and
return through a separately verified account handoff. That would first need a
solution for Tachiai's selected route and session ownership. The unavailable
fallback must not silently launch another browser, change routes or copy
cookies. Dynamic errors, popup text, restoration of original text and translated
layout/focus need actual validation; this research does not claim complete page
coverage. Brave's translation availability and processing are also unverified
here; its successful email reference is not a translation test.

### Android framework and system features

From API 31, `TranslationManager` can query on-device language capabilities and
create a translator. A translator can be unavailable, OEMs may provide no
service/settings, and capability queries must run on a worker thread. This API
translates supplied text; it does not safely acquire provider DOM content.
[TranslationManager reference](https://developer.android.com/reference/android/view/translation/TranslationManager)

`UiTranslationManager` describes native-view inline translation and requires
explicit hooks for virtual browser hierarchies. Its public methods register and
unregister state callbacks, rather than start translation of an arbitrary
WebView. [UiTranslationManager reference](https://developer.android.com/reference/android/view/translation/UiTranslationManager)
AOSP [WebView](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/webkit/WebView.java)
forwards translation hooks to its provider, whose
[interface](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/main/android-35/android/webkit/WebViewProvider.java)
has empty defaults. The inspected
[Chromium delegate](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/android_webview/glue/java/src/com/android/webview/chromium/WebViewChromium.java)
does not override those hooks. The inference is that this inspected path does
not supply framework page translation; installed versions/OEM implementations
remain unverified. A framework capability should not be presented as a ready
WebView feature.

Circle to Search documents screen and scrolling translation on selected Android
phones, including Pixel 6 and later, with a warning about confidential apps.
The user invokes it through the navigation handle/Home button and chooses
Translate. Google assistant/screen-data settings and service availability are
dependencies. Its behavior in Tachiai's secure browser and exclusion of entered
secrets have not been established. Do not weaken `FLAG_SECURE` to make it work or
offer an offline/privacy guarantee. [Circle to Search help](https://support.google.com/android/answer/14508957?hl=en)

Pixel help separately describes on-device Live Translate for supported chat
apps, and screen translation through Circle to Search. Local chat translation
does not establish offline translation of arbitrary secure login pages.
[Pixel translation help](https://support.google.com/pixelphone/answer/11209263?hl=en)
If a future synthetic check establishes a suitable feature, its user flow could
preserve original-text recovery and leave the provider page untouched. Missing
service, unsupported language or secure-window refusal should fall back to
guidance, not another cloud service. No such device feature was exercised here.

### On-device translation and OCR

ML Kit performs translation locally with downloaded models and describes its
intended quality as casual/simple. Authentication, consent, negation and expiry
wording need a Japanese reader's review; machine fluency is not proof of correct
meaning. Its usage guidelines also require attribution.
[ML Kit translation](https://developers.google.com/ml-kit/language/translation)
Android support starts at API 23; language models are about 30 MB, with explicit
download/readiness/deletion and translator lifecycle handling.
[Android integration guide](https://developers.google.com/ml-kit/language/translation/android)

Japanese OCR is available through bundled or dynamically downloaded models, but
the API needs an input image. It does not grant access to secure WebView pixels.
[ML Kit text recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
Similarly, Google Translate offers offline language downloads on phones/tablets;
this does not integrate it with Tachiai's protected page or solve text
acquisition. [Offline Translate help](https://support.google.com/translate/answer/6142473?co=GENIE.Platform%3DAndroid&hl=en)

A local engine could translate explicitly permitted public instructions or
maintained guidance after model readiness. For Japanese/English guidance,
human-reviewed fixed wording may have less runtime complexity and more reliable
auth terminology. Unknown live errors cannot be acquired through forbidden DOM
injection or by asking the user to paste a secret-bearing screen into a native
form. Missing models must remain a visible unavailable state, without cloud
fallback. Cloud translation or an LLM receiving the authenticated page would
add a new disclosure boundary and is outside this research's current shortlist.

## Recommended directions for discussion

Start with separate, human-reviewed bilingual guidance for the verified email
flow. The user could consult the Japanese label and English meaning, perform
the action on ABEMA's unchanged page, then return and finish native validation.
Keep it optional, preserve original text, and distinguish provider instructions
from Tachiai's own controls. A provider-specific glossary belongs behind a
narrow adapter; generic presentation should not depend on ABEMA selectors.

Guidance offers offline availability without extracting live page contents,
keeps the configured route and secure window, and can work with accessibility
tools. It still needs maintenance and cannot translate arbitrary new errors.
An unrecognized message should stay unrecognized: offer public help or
return/cancel, not a guessed login-success or region diagnosis. No implementation
is included in this document.

Keep provider-native language as the preferred option if verified. Investigate
system/framework translation only on synthetic content first. Retain browser
translation as a conditional alternative requiring a route/account design and
privacy decision; do not change the working email setup merely to obtain it.

On phones/tablets, evaluate large font, TalkBack reading order, keyboard
visibility, portrait/landscape and restoring focus after guidance. On Android
TV, guidance must support D-pad focus, selection and Back without a touch-only
gesture; Android's [TV navigation guidance](https://developer.android.com/training/tv/get-started/navigation)
requires remote navigation. Phone-only screen translation and text selection
are not assumed available on TV. Any phone-assisted alternative account method
belongs to #196, not a translation shortcut. TV email and guidance acceptance
remain untested.

## Cheapest useful validation before implementation

1. Review the small public-label glossary and email sequence with a Japanese
   reader. Check account switching versus account creation, send versus resend,
   completion versus consent, and region refusal versus display/network failure.
   Record reviewed meanings and provider wording variants, not private images.
2. Use app-owned synthetic Japanese text in native views and a WebView, including
   dynamic errors and dummy email/password/code fields. Compare normal and secure
   windows without weakening the real login Activity. Query available translation
   pairs on a worker thread; record only capability/service/model categories.
3. On a supported, explicitly targeted device, verify whether user-invoked system
   translation works on those synthetic secure pages and what content/processors
   it uses. Stop if secret exclusion or service behavior cannot be established.
   Emulator behavior does not establish physical-device fidelity.
4. If browser translation remains interesting, verify its menu and dynamic/error
   behavior using synthetic pages in each proposed browser/Custom Tab version.
   Resolve route, browser-profile and account-return ownership separately before
   involving ABEMA. Do not reuse a desktop cookie or native grant.
5. Evaluate guidance with large fonts/TalkBack and TV D-pad/Back. If considering a
   local engine, compare only public/synthetic phrases against reviewed meanings,
   test missing/offline models and measure storage/readiness.

The remaining decisions are whether guidance alone is enough, whether any
verified system feature can be optional assistance, and whether an external
browser's translation benefit warrants a different account architecture and
processing disclosure. No provider language switch, WebView translation,
secret-field filtering or TV support is claimed as settled.
