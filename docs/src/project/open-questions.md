# Open questions

- How should a relative-timing anchor be re-established after explicit live
  catch-up? The additive native relative examples now invalidate their requested
  movement ledger after a possible default-position dispatch; subsequent nudges
  cannot silently revalidate it. A two-live device repeat recovered both expired
  points separately and resumed to its existing five-minute cap without another
  exchange. Inspection delay can expire recovered points again. Current-window
  Play refusal and live-only recovery now pass both mixed live/replay directions,
  with the replay peer held fixed and replay catch-up refused without movement.
  This is not a measured common-event anchor, unlimited retention or renewal.
  See [recovery evidence](native-access-experiments.md#additive-native-relative-nudge-comparison--2026-10-07).

- Can native Twitch playback provide useful alignment and dual mixing?
  RelaxBeats native live accepted media, rendered video and played user-confirmed
  audible music on the Pixel 6; its two-minute budget then released/stopped it.
  Bob Ross returned an unclassified manifest 404. Replay accepted/parsing its
  initial playlist, then the strict host policy blocked the advertised
  `dgeft87wbj63p.cloudfront.net` destination. A separate comparison permits only
  that observed distribution while preserving the strict case; it fetched media,
  rendered the replay introduction and played user-confirmed audio. Replay
  timeout, explicit restart and Stop cleared/recreated the player as expected;
  background/return kept it stopped without automatic restart. Startup network
  cancellation and sustained decoding still need device tests.
  Do not broadly allow a multi-tenant CDN on assumption.
  Single-stream success does not establish Turbo, provider permission, sustained dual
  decoding/mixing or the terms/distribution gate.
  See [prototype boundaries](native-access-experiments.md#additive-native-twitch-playback-prototype).
  Additive native timing cases observed replay ±5-second settled clock/frame
  matches, running live ±5-second clock changes inside a 30-second window,
  about 37.5 seconds of additional hold/resume delay and default-live recovery.
  The music-artwork live fixture does not prove moving-event/frame-level alignment.
  Maximum retention, small seeks outside the current advertised window, eviction,
  ads, source transitions and two-feed acoustic alignment remain open. See
  [timing results](native-access-experiments.md#corrected-native-live-timing-results--2026-10-06).
  The additive same-replay native pair will test one group focus owner,
  independent audio balancing and signed relative timing transactions; its
  [implementation assumptions](native-access-experiments.md#additive-two-native-replay-comparison--2026-10-06)
  now have bounded Pixel 6 evidence: both rendered/advanced, the user heard
  doubled commentary, a running +1-second paired transaction resumed near the
  requested offset and the two-minute cap stopped the group. A second negative
  running transaction resumed with about 178 ms target error; Sync converged
  sampled clocks near zero and Home/Recents remained stopped. Independent volume
  isolation, external competing focus and sustained precision remain open.
  Explicit seven-day LOCAL extension passed validation/save and cold-process
  reuse without another approval, but actual long-term retention/revocation
  remains unverified. Playback tests stay capped at two minutes.

- Can the explicitly selected Smart TV grant support durable, acceptable playback
  beyond the now-observed bounded live/replay success on the Pixel 6?
  Its initial DEVICE request returned HTTP 200 and reached WAITING under the
  unchanged activation allowlist;
  private approval then produced TOKEN HTTP 200 but failed the strict grant
  lifetime check before official validation/save/access. A separate transient,
  30-second validation inspection then succeeded: grant expiry OMITTED, official
  validation HTTP 200 with expiry ZERO and scopes NULL, exact selected client
  and user checks passed. Its token was discarded without saving or handoff;
  that inspection did not test access. The user approved a separate fourth-slot
  local-retention experiment: at most one hour, revalidation before every use,
  at most 30 seconds per use capped by stored remaining retention, no renewal.
  Local save subsequently succeeded, and live/replay saved uses each passed
  fresh official validation and returned HTTP 200 / NO_ERROR / accessFields
  PRESENT. Those access-only cases requested no playlist/media. A later additive
  prototype played RelaxBeats live video and user-confirmed music; the separate
  exact-CDN replay case also rendered video and user-confirmed audio. LOCAL slot reuse
  and revalidation across an app/process update were observed. Turbo benefits,
  reboot/long-term reuse and provider permission remain unresolved. The chosen
  wall-clock-dependent policy is not Twitch expiry; long-term product retention
  remains unresolved.
  Zero must not be presented as permanent validity.
  A recent equivalent-client report supports testing it before mobile-web,
  but does not establish playback, Turbo or a supported integration. Its slot
  is independent of earlier slots. The strict-profile comparison changes identity
  only; local retention uses the explicit lifetime exception above. Unexpected
  request/scope/activation shapes remain rejected.

- Does the explicitly approved, separate provider-client device grant succeed
  and change live/replay access rejection? It is an unsupported debug experiment
  with an independent encrypted slot, not a resolved authentication architecture.
  The first device-start requests returned HTTP 400 and a closed provider-
  reported client rejection before any challenge; no grant or provider access
  test succeeded. The request matches the pinned reference's identity/form,
  but current client eligibility and the server-side cause are not established.
  Provider permission, account/distribution risk, Turbo and actual native playback
  remain open even if its access fields are present.

## Product

- Which primary/secondary layouts are necessary on a phone, tablet, and
  television?
- Which swap gesture or control is fastest while remaining clear for touch and
  D-pad users?
- Should saved presentations store a preferred reference pane and initial
  requested offset?
- What accessible descriptive label should accompany the **Matta!** control?
- Can both provider players expose reliable independent volume levels, or will
  some combinations require a reduced mute/unmute mix? The user confirmed
  independent ABEMA/Twitch balancing in a bounded Pixel 6 composite test;
  durability and other provider/device combinations remain open.
- How should initial mute be enforced when a provider creates or replaces its
  media element after main-frame load? The current command is best effort only.

## ABEMA

- What supported structured catalog/My List access and instance-owned account
  connection can Tachiai provide? The
  [2026-10-09 catalog research](abema-catalog-access.md) documents public resource
  identities and provider-controlled account sharing, but leaves external
  collection access, connection verification, session isolation and selected
  routes unresolved. Public-link import does not satisfy account-backed access.
- Does the sumo channel remain available between basho, and how do annual title
  identities, exact broadcast slots and catch-up episodes relate across years?
  Channel ordering is documented, but its account ownership, synchronization and
  retrieval are unverified. A series needs explicit child selection until a
  recurring resolver is established.

- Can the cached no-page bootstrap cover replay and durable operation as well
  as News? Two independently initialized native News copies rendered and advanced
  for the five-minute foreground budget without an original web video. A cached
  fresh-process repeat reached both first frames in 10.6 seconds. The fixed free
  sumo replay reached guest/token/program metadata HTTP 200, then the conservative
  restriction gate stopped it before source/licensing. Public DTO default trial
  and informational-record shapes motivated narrowly typed inactive-value
  corrections. Promotional external-content text is distinct from eligibility;
  its bounded shape is accepted without navigation/UI/tracking. Replay now reaches
  source preparation; a closed request diagnostic identified a missing standard
  dot/tilde character allowance in our gateway policy. The corrected repeat now
  completes source selection; the next phone repeat identified CSAI and its
  extra support chunk passed dependency preflight. That stop was our own adapter
  prerequisite, not an observed server demand for proof of ad playback. At the
  user's explicit request the prerequisite and extra startup dependency are
  removed. Direct content now proceeds without a separate client ad player or
  fabricated tracking, retaining the original provider-ranked source and URL/DRM
  configuration builders. The phone now reaches helper READY, then its existing
  replay-CDN policy rejects VOD_AKAMAI: the gateway selected the non-`ds-` VOD
  host, whereas the earlier page-backed source policy allows only the `ds-` host.
  No manifest/license request occurred in that repeat. The user subsequently
  approved the single-label `*-abematv.akamaized.net` family for the cached
  prototype, with unknown origins blocked and recorded for explicit approval
  or rejection; [the approval ledger](media-origin-approvals.md) defines the
  boundary. The subsequent Pixel 6 repeat fetched HTTP 200 manifests/media,
  completed independent native CDM exchanges and rendered both replay copies
  in about 14.9 seconds, without an original page/video. The saved journal
  showed only approved VOD origins in that run. Longer-term behavior remains
  unverified;
  full ad/analytics equivalence and server-stitched session resolution remain
  unimplemented. No later ad-free fallback is selected.
  Current remote flags,
  advertising handling, device classification, renewal and provider permission
  remain unresolved; successful News playback does not settle them. No new
  acoustic confirmation was obtained for this path. See
  [cached bootstrap evidence](native-access-experiments.md#cached-native-bootstrap-without-the-provider-page--2026-10-07).

- Can anonymous advertised News DASH access pass on the phone, and is there
  an authorized native source/license path for sumo? The computer-side exact
  advertised manifest returned HTTP 403 after metadata succeeded; that does
  not diagnose DRM, region or authentication. On 2026-10-06 the unchanged phone
  case returned HTTP 200 with protection markers under a user-reported system
  Proton Japan connection (Android confirmed a Proton VPN, not exit geography).
  The additive DASH format inspection found common-encryption signaling without
  a recognized DRM-system marker. The advertised HLS master returned 200 and
  its one-child comparison found an `abematv-license://` key-reference hint.
  No keys/licenses/media were requested; native playback and an authorized
  standard DRM/license path remain unverified. This is News live, not evidence
  for sumo or recordings. See the
  [format comparison](native-access-experiments.md#abema-native-format-comparison--2026-10-06).

- What documented/authorized native acquisition path corresponds to ABEMA's
  observed Clear Key browser configuration? The isolated Pixel 6/WebView
  inspection reported an attached Clear Key CDM and audio/video decrypting
  demuxers for News and the free sumo replay; the refined replay classifier
  reported CENC audio/video tracks. This narrows those browser experiments,
  not all providers/content/platforms or the public application's Widevine
  branch. No keys or license bodies were inspected, and no response matched
  the tracked license host. Which conditions select each path, and can the
  active path be integrated without private key acquisition, session transfer
  or reproducing proprietary processing? See the
  [media follow-up](native-access-experiments.md#related-target-and-media-follow-up).
  The subsequent public-code trace found separate capability/device-discovery
  and DASH-player stages: both Widevine and Clear Key are registered, with
  Widevine preferred. The device-type request and its channel-metadata consumers
  are not playback-resource acquisition. Both bounded News/replay startup traces
  then accepted the initial
  Widevine and Clear Key capability requests, followed by a later accepted
  Clear Key request. Initial Widevine capability rejection is therefore not
  the explanation for those runs; content-specific negotiation/resource
  selection remains unresolved. See the
  [startup results](native-access-experiments.md#startup-capability-results--2026-10-06).
  No public
  native Clear Key acquisition contract was found in the reviewed official
  material. See the
  [selection follow-up](native-access-experiments.md#public-key-system-selection-follow-up).
  The actual browser factory consumes stream/playbackUrl and opaque provider
  DRM configuration; neither that factory nor device discovery has established
  an independent native licensing helper. JavaScript remains an unverified
  option, while native DASH hosting and provider acquisition are separate gaps.
  See the [integration boundary](native-access-experiments.md#nativehelper-integration-boundary--2026-10-06).
  A deeper trace located PlaybackResourceGateway and the separate media-token/
  Zeus decision steps. The ABEMA DASH response filter has no observed DOM or
  existing-session dependency, making unchanged-JavaScript normalization a
  plausible, untested native helper. Matching provider identity, native request/
  response compatibility and an active license route remain unresolved. The
  broader phone network trace saturated other-provider markers without matching
  the static resource or tracked license origins; it did not identify the
  handshake. See the [source/handshake trace](native-access-experiments.md#source-and-handshake-interface-trace--2026-10-06).
  The additive [native request-format probe](native-access-experiments.md#native-request-format-probe--2026-10-06)
  can prepare a request from advertised standard initialization without license
  traffic; its result must not be treated as authorization or response/helper
  compatibility. Which active browser transport and unchanged response adapter
  correspond to that native request remains unresolved.
  The subsequent [configured-route comparison](native-access-experiments.md#configured-route-handshake-comparison--2026-10-06)
  resolved the API driver and observed the configured DASH license proxy POST
  return 200 JSON for News and replay, followed by attached Clear Key/CENC
  playback. The old zero-license count was a wrong-origin/coverage gap, not
  absent licensing. Native accepted licensing and unchanged-helper integration
  remain unverified: guest bootstrap is browser-profile coupled, and no public
  helper loader or native license contract was found. Further native integration
  needs provider documentation or a separately authorized, narrowly scoped
  undocumented helper experiment; the inspection exception alone does not
  authorize a provider-page bridge or cookie/token transfer. The user subsequently
  approved the additive [one-exchange broker prototype](native-access-experiments.md#authorized-one-exchange-broker-prototype--2026-10-06),
  with DevTools disabled and opaque response handoff to the same native CDM
  session. Its corrected phone loader reached WAITING_PLAYER, not READY;
  native license acceptance and playback were unverified at that checkpoint.
  The next gate was
  identifying the original page's selected player/initialization stage without
  exporting provider authorization or weakening the original DRM/ad flow.
  The [runtime follow-up](native-access-experiments.md#broker-runtime-follow-up--2026-10-06)
  found retained hooks and one installation, but no factory/create/load calls
  even while the original News video visibly advanced after the user's network
  reconnect. The missed runtime/player path remains unresolved; top-document
  play events and iframe presence alone do not establish license readiness.
  The additive shared-dash capture case subsequently reached READY and returned
  RESPONSE_ACCEPTED for one native Clear Key session using anonymous live News.
  At that checkpoint native decoding/playback, replay/sumo, renewal/key rotation
  and durable integration remained unverified. An additive two-minute native News playback
  case now owns its DRM session and generates/consumes its own challenge/
  response; the closed acceptance-probe session is not reused. Device decoding,
  native audio and the strict News media-root compatibility are the next gates.
  The first native attempt read the MPD with HTTP 200 but refused a subsequent
  media URI before any DRM request. The actual MPD-declared media route must be
  established before adjusting the experimental allowlist; this failure does
  not establish a CDM/decryption incompatibility.
  A categorical retry establishes CHANNEL_ROOT refusal: the requests use the
  expected HTTPS authority without query/user info/fragment, but lie outside
  the guessed News path root. Exact public MPD-declared media-path binding is
  now implemented with bounded Media3 parsing, not arbitrary CDN access.
  A fresh two-minute Pixel 6 run loaded its own native DRM keys, rendered changing
  News video and produced user-confirmed audio with the original web video mute
  requested. Manifest refreshes continued without an observed second license
  exchange. Native live paused ±5-second content-clock shifts, running seeks,
  retained ~10-second hold/resume delay and default-live recovery are now
  observed within a ~60-second window. A later refresh-policy refusal and
  edge-clamped seek failure remain unclassified; they are not proof of key
  rotation or failed DRM. The additive fixed free sumo replay now renders native
  moving video, settles held ±5-second seeks and produces user-confirmed audio
  while its original web video is paused/muted. This does not prove headless
  helper startup or permit disposing the WebView. Other replays, renewal/rotation, longer runs,
  sustained mixed audio and provider permission remain separate unresolved gates.
  A subsequent native News/RelaxBeats live pair produced user-confirmed mixed
  audio with advancing native clocks and the original web video paused/muted.
  Both native replay videos also advanced together, and a longer coordinated
  repeat confirmed mixed sumo/Rocket League audio with original web playback
  paused/muted. Paired replay ±5-second seeks matched exact held selected clocks
  while the other stayed fixed, followed by joint resume. None of these
  observations establishes a long-lived synchronization lock or renewal.
  A later native live pair refresh returned UNRECOGNIZED at protection preflight
  before terminal teardown. That category collapses absent default IDs and
  parser/structure/limit refusals; closed reason/count diagnostics are the next
  check, with the acceptance policy unchanged. An ad/program/clear transition
  is not established by this marker or the subsequent seek failure.
  The subsequent closed-diagnostic repeat identified a completed MPD parse with
  two periods/four adaptation sets but no protection elements/default-ID
  declarations, after an accepted two-period protected refresh. Our unchanged
  initialization-set preflight refused it; buffered playback continued briefly,
  then the pair stopped. How can an additive comparison distinguish explicitly
  unprotected transitions from unsupported/missing DRM metadata without relaxing
  unknown-protection, declared-file, session or renewal boundaries? This is not
  evidence of a failed license exchange or proof of advertisement identity.
  The separately selected LIVE_TRANSITIONS comparison now admits only wholly
  declaration-free documents after a protected start, with an initial-baseline
  protection/marker audit and a Media3 no-DRM-data model check. The original
  strict cases remain. Synthetic JVM and real SDK parser tests pass; one full
  five-minute protected live pair reached its intended cap without failure.
  Can an actual declaration-free interval decode and return to protected
  content using the retained original session, without a second exchange?
  A final live attempt admitted an actual declaration-free interval and return
  to the original protection shapes, but its first native challenge occurred
  after the unchanged browser helper's two-minute document lifetime. That first
  exchange was locally refused; there had been no initialized native session
  to reuse. Separate aligned-helper cases now preserve the old examples while
  permitting only one unused initial exchange through the same foreground
  deadline. A delayed-start test and a real declaration-free interval/return
  both completed their late first exchange and played to the cap. This fixes
  the observed local lifetime mismatch, not a provider rejection. Initialized
  session reuse was unverified in that run: it loaded keys only on return.
  A later LIVE_RELATIVE prewarm run loaded native keys before Play, then kept
  playing across NO_DECLARATIONS and SAME_PROTECTION return with no second
  exchange. That bounded initialized-session transition check now passes on
  this Pixel 6; absence of manifest declarations does not prove clear samples
  or advertisement identity. Resource-pressure eviction/rotation/renewal
  remain fail-closed, not supported behavior.
  See the [separate comparison](native-access-experiments.md#additive-whole-document-transition-comparison--2026-10-07).
  See the [follow-up evidence](native-access-experiments.md#manifest-declared-native-media-paths-follow-up--2026-10-06)
  and [timing/refresh limit](native-access-experiments.md#native-news-timing-and-refresh-limit--2026-10-06).

- What stable public page identifies the currently active Grand Sumo channel or
  program?
- Can a provider-supported, non-circumventing region and network context first
  pass the same episode in Chrome and then distinguish default WebView, mobile
  Chrome-like, and desktop Chrome-like identity behavior without changing any
  other condition? VPN observations remain separate unsupported diagnostics.
- Does that page play in Android WebView on the target phone and Shield in the
  user's actual region?
- Is the active player a top-level media element, an iframe, or a closed shadow
  tree?
- Which DOM container can be focused without removing required controls or ads?
- Does live pause/resume retain delayed media, for how long, and across program
  or advertisement transitions?
- Can the web account flow use ABEMA's ID and one-time password on Android TV?
- Can ABEMA's provider-controlled email-code account-switching flow work in
  Tachiai's persistent browser profile when account-required content needs it?
  The [official account-sharing instructions](https://help.abema.tv/hc/ja/articles/28570588900377)
  document emailed verification codes for web, mobile and TV, as well as
  account ID/one-time-password sharing. This is an authentication option, not
  evidence of native playback entitlement or a transferable browser DRM session.
  Keep entry on provider pages; do not collect codes or inject on account pages.
- Do free channels and replay content use different DRM or player paths?
- Does the original ABEMA video remain playable after optional onboarding is
  declined across restarts, live programs, advertisements, and other WebView
  versions? The one successful answer remains an unsupported Android desktop-
  identity experiment.
- Is any public provider partnership or supported integration available beyond
  ABEMA's website and native applications? No public embed or playback SDK was
  found in the official material reviewed for the spike.

## Twitch

- Can the private access interface accept Tachiai's own public client,
  optionally with its validated user token? The anonymous computer-side live
  request returned HTTP 400 / CLIENT_REJECTED. New phone cases are separate
  from successful validation-only OAuth. A later explicitly approved provider-
  client experiment is separate from these own-only cases. Native control,
  Turbo entitlement and permission remain open.
  Two subsequent phone live attempts passed official validation (HTTP 200,
  experimental null/no-scopes), then private access returned HTTP 401. This
  is not an official token-validation failure; its private-interface cause
  remains unknown. Additive encrypted saved-token cases will enable reuse
  without repeated approval, but cannot by themselves resolve that rejection.
  The subsequent saved-token phone test passed encryption, reuse for live/replay
  and cold-process restart/revalidation without another approval. Both private
  access cases still returned HTTP 401 after official validation returned 200;
  request-contract/client eligibility remains unclassified. No token/key or
  raw response was inspected. Reboot, long-term expiry/revocation and Forget
  remain untested; see the [saved-token results](native-access-experiments.md).
  The additive error cases subsequently classified both private 401 responses
  as explicit token/authentication rejection after official validation 200.
  No raw text or credential was exposed. Why does the private service reject
  this officially validated own-client token? An official scope-free Helix
  control and isolated contract comparisons remain proposed, not tested;
  reauthorizing or borrowing client identities is not an established solution.
  An independently implemented blank Client-ID comparison then retained that
  token/query and still produced the same private 401 token/authentication
  rejection for both live and replay after validation 200. Empty-header
  configuration is tested; its TLS wire representation was not captured.
  The reference's separate provider-client login is not reproduced by that
  one-header experiment; see the [comparison results](native-access-experiments.md).

- What supported connection can take Tachiai's app OAuth to authenticated
  playback/Turbo? Its own public-client device flow completed a bounded Pixel 6
  test on 2026-10-05 with an empty-scope request, omitted grant scope and present
  null validation scopes under the documented experimental convention. The
  validation-only probe discards the returned token; this is not a WebView
  session, playback entitlement test or persistent account implementation.
- Why does the real Everything embed popup render its login form but reject
  submission as unsupported-browser on the tested Pixel 6/default WebView,
  while Chrome login succeeds? The separate canonical login page stays blank
  across default, wide, and wide-plus-overview native viewport modes; that
  rendering failure does not diagnose the popup rejection. Compare native
  popup settings without inspecting credentials or session data; see the
  [bounded authentication experiments](timing-capability-matrix.md). No exact
  cause, supported fix, authenticated embed state, or Turbo entitlement has
  been established. The installed multiple-window dispatch correction did not
  resolve the rejection. A subsequent bounded popup trace observed requests
  for all four pinned public-script categories without corresponding native
  failures, but does not establish downloads, execution or integrity-SDK
  readiness. The requested delayed private attempt still failed and added an
  unattributed HTTP 400. What further supported diagnostics or browser-hosting
  approach can resolve authentication while preserving two-feed mixing and
  alignment? Automatic-window permission remains disabled; no cookie transfer
  or integrity-check alteration is proposed.
  The subsequent mobile Chrome-like embed/popup comparison also produced the
  user-reported rejection. Standard Custom Tabs are not a drop-in replacement
  for the existing provider-page injection and composition; browser-backed
  alternatives must establish those controls as well as login compatibility.
- Why does the normal Twitch channel page become transiently blank and return
  squashed as a whole page? Native geometry stayed 2146 × 754 physical pixels
  during one reproduced short-page report, distinguishing that run from a
  collapsed WebView. Internal page layout, zoom, resource failures and custom
  hosting behavior remain possible causes. The corrected debug default-script-
  dialog comparison also remained squashed at the same native dimensions,
  without a cancelled/delegated dialog callback. That change did not fix the
  reproduced sizing failure; it does not inspect authentication or isolate
  every host policy.
- Why did the official embed report a live channel but never begin visible
  playback after browser-surface gestures on the Pixel 6?
- Does top-level mobile Twitch playback and exact-route focus mode behave
  consistently on other Android WebView versions and Android TV?
- Do the observed mobile-player class fragments remain stable, and does focus
  survive prerolls, midrolls, quality changes, and player replacement?
- Should Tachiai retain native generic media controls, expose Twitch's original
  controls, or provide both, and can every choice remain D-pad reachable?
- Does the live embed retain a paused position or resume at the live edge?
- How do Twitch prerolls and midrolls affect the user's intended alignment?
- Why did app-page SDK Sound requests leave both public replay copies silent
  until Twitch's original speaker controls were exercised? Direct player
  interaction made the delayed pair audible on the Pixel 6, but the cause and
  durability of that activation are not established.
- How should player visibility be maintained while timing controls and
  diagnostics are used? On the Pixel 6, SDK Play resumed the two replay copies
  when visible but left them paused while the large diagnostic header pushed
  them largely below the viewport. The compact toolbar passed a bounded
  landscape check; smaller viewports and diagnostic scrolling remain untested.
- Is video-only embed sufficient, or does the first useful version need chat?

## Alignment

- Which timing primitives are reliable for each provider/source kind? The
  [four-case timing experiment](timing-capability-matrix.md) matched paused
  bidirectional recording seeks and ABEMA live clocks inside the exposed
  window. ABEMA live still needs moving-footage and eviction checks. Twitch's
  official live SDK does not seek, and a 15-second hold did not retain the
  requested additional reported latency. Can supported player settings or
  another intact-player approach provide useful retained delay and recovery?
- What is the typical ABEMA-versus-`midnightsumo` latency difference?
- Does that difference drift materially during one match day?
- Which feed normally needs delaying?
- What adjustment granularity is useful and reliable?
  The same-VOD Pixel 6 test matched +250 ms, +1 s, and +5 s paused seeks on both
  copies to the displayed clock precision. During playback, seek/rebuffer
  latency changed the resulting pair offset instead of preserving the requested
  increment. Should a user nudge briefly hold both copies, or use measured
  post-seek correction, and how should the UI expose that interruption? A
  bounded paired-hold diagnostic matched held targets and resumed both videos
  in a Pixel 6 test, but running offsets still differed by roughly one second
  from requested targets. A first transaction also cancelled on a sampled
  clock discontinuity. Can restoration be confirmed before another nudge
  snapshots stale paused state, and how should cached clocks be classified?
- How should Tachiai communicate that an accumulated pause is not a measured
  offset?
- What user interaction establishes a common event anchor for replay/replay or
  live/replay viewing?
- When should an advertisement or rebuffer automatically invalidate alignment
  confidence?
- How should sampled clock health and last SDK events be presented separately?
  The diagnostic's last “seeking” event remained visible after playback resumed.
  The updated page labels it as the last SDK event separately from pause-state
  readback; this revised display still needs a device check.

## Android

- Minimum Android API level and target API level.
- Whether one APK should contain both mobile and TV launchers.
- Whether target devices can sustain two protected 720p or 1080p streams
  without thermal or memory problems. One Pixel 6 decoded ABEMA and Twitch and
  mixed both audio tracks briefly in a single WebContents, but sustained tests
  remain open.
- Whether the single-WebContents result survives ads, player replacement,
  background/foreground transitions, hardware media buttons, process restart,
  other WebView versions, and Android TV.
- Whether ABEMA permits Tachiai to add Twitch's official cross-origin child
  frame and whether Twitch accepts ABEMA ancestor names for this use over time.
  A successful render is technical evidence, not provider approval.
- How to expose independent Twitch controls from the native shell without a
  broad bridge or violating the cross-origin boundary. A debug-only app-owned
  child page now implements a strictly validated `postMessage` command exchange
  to Twitch's documented SDK. The extra ancestor rendered and mixed audio in a
  bounded Pixel 6 test, and the user confirmed independent volume balancing;
  native-only audio activation, isolated mute checks, and sustained retained
  levels/mixing remain to be verified. Provider-owned first-use controls
  were still exercised, and ABEMA required its visible unmute overlay.
- How remote focus reaches provider controls when full-site mode is necessary.
- Whether cookies, popup windows, and protected-media permissions behave
  consistently across phone and Shield WebView versions.

## Other platforms

- Browser extension, installed-browser controller, or embedded wrapper for
  Windows and macOS.
- Whether ABEMA's content-security policy permits a Twitch iframe inserted by
  an extension.
- Which Linux distribution and Google Chrome version constitute the first
  experimental target.
- Whether ABEMA plays in WKWebView and whether a Safari extension provides a
  better iPhone experience.
- How iOS builds would be shared if TestFlight review or 90-day expiration is
  unacceptable.

## Repository and delivery

- Whether Tachiai should be added to Tome's tracked repository registry.
- Whether the first manual Renovate run successfully updates all intended
  dependency sources and regenerates `mise.lock` in its restricted runner.
- Whether Mergify's injected branch protection and the aggregate `all` check
  behave as expected on the first explicitly enqueued pull request.
- Shared APK update and checksum distribution mechanism.
- Whether reproducible Android builds are practical with the selected WebView
  and adapter asset approach.
