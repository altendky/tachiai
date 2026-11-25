# UX variation register

This register contains concrete design differences between implementations,
including existing differences discovered during review. Common UX guidance
lives in [AGENTS.md](../../../AGENTS.md#common-ux-across-implementations).
Technical mechanisms are recorded in the separate
[implementation difference ledger](implementation-differences.md).

## Existing differences requiring design review

The October 18 audit below records implemented phone/TV differences against
`d60ce4a3485305ae4681d8dca6b33cd8e48bb449`. Explicit user approval for these
individual deviations was not established by that audit. **Unconfirmed** is not
approval, a claim that the difference is required by Android, or authorization
to extend it. Merging this document does not approve its rows.

| ID | Area | Implemented deviation | Approval status/evidence | Implementation evidence |
| --- | --- | --- | --- | --- |
| UX-001 | Screen margins | TV adds 32 dp horizontal and 20 dp vertical padding around shared settings/picker content. Phone does not add those TV margins. | Unconfirmed; explicit design approval not recorded. | [Theme][theme] |
| UX-002 | Focus/navigation | TV adds contrasting focus outlines, initial focus, focus restoration and grouped discovery-tab navigation. Phone does not enable those TV-specific rules. | Unconfirmed; explicit design approval not recorded. | [Compose focus][focus], [viewer focus][viewer-focus], [tabs][tabs] |
| UX-003 | Revealing playback controls | TV consumes the first directional/Select press to reveal controls without activating an action. Phone background/primary-feed taps toggle the controls; a floating-feed tap retains its swap action. Since #559, Select on a focused, visible feed-pane Retry/Go live activates it on TV as a tap does on phone; that narrows, not extends, this row. | Unconfirmed; explicit design approval not recorded. The #559 narrowing was user-approved on 2026-11-19 ("ok, mark it agent labeled" for the proposed one-press pane action). | [Viewer input][viewer-input], [touch handling][viewer-touch] |
| UX-004 | Control-bar visibility | The October 18 audit found a TV-only focus exception to auto-hide. The current shared focus rule removes that difference. | Resolved 2026-11-07 through [common timing/overlay focus](prototype-ux.md#timing-and-overlay-focus); no platform exception was approved. | [Historical visibility][visibility] |
| UX-005 | Feed assignment | TV uses labeled Feed A/B columns and 72 dp outlined A/B assignment buttons. Phone uses 48 dp checkbox targets. | Unconfirmed; explicit design approval not recorded. | [Picker][picker] |
| UX-006 | Screen sharing entry | TV hides the picker menu's Screen sharing action; phone can show it. | Unconfirmed; explicit design approval not recorded. | [Picker menu][picker-menu] |
| UX-007 | Twitch activation | QR/code activation is shared, but TV hides the Open in Brave/Open in Chrome actions shown on phone. | Unconfirmed; explicit design approval not recorded. | [Connection screen][twitch] |
| UX-008 | Viewer orientation | TV always uses the viewer's landscape layout calculation and omits the manual orientation action available on phone. | Unconfirmed; explicit design approval not recorded. | [Menu][viewer-menu], [layout][viewer-layout] |
| UX-009 | Floating feed placement/size | TV adds floating-feed corner and Small/Medium/Large menus. Phone does not show those menus; common touch drag/swap handling remains implemented. | Unconfirmed; explicit design approval not recorded. | [Menu][viewer-menu], [touch handling][viewer-touch] |
| UX-010 | Foreground media buttons | TV explicitly routes foreground media Play/Pause/Toggle commands to the guarded two-feed pair. Phone has no equivalent explicitly installed pair media-session rule. This is a code observation, not proof of physical-button behavior on either device. | Unconfirmed; explicit design approval not recorded. | [Media commands][media-commands], [Activity integration][media-integration] |

## Approved exceptions

The initial audit found no approved exceptions among UX-001–UX-010. Unresolved
rows remain unconfirmed; UX-004 was removed by a shared rule rather than approved
as an exception. Later scoped approvals are recorded separately below.
A changed scope requires its own approval. Unapproved proposals, ordinary shared
behavior, accidental bugs and platform research do not belong in this register.

| ID | Area | Approved scope | Approval evidence | Implementation |
| --- | --- | --- | --- | --- |
| UX-011 | Web browser history after viewer retirement | Web Forward into a stopped viewer, or reload/BFCache return after retirement, shows Sources and requires a fresh Watch. Native has no Forward control. This scope retains the common Back order and does not restore retired players or authorize other navigation differences. | Explicit user answer “Approve this contract” on November 1, 2026 UTC; [exact contract and approval](https://github.com/altendky/tachiai/issues/368#issuecomment-6552324565). | Implemented in the [controlled browser fixture](web-pair-fixture.md#browser-history-and-retired-viewers); provider/full-client acceptance remains separate. |
| UX-012 | Replay and live skip input | Phone and TV show the same position row, skips and scrub bar on replays and on live feeds. Only the gesture for a ±10 s skip differs: on TV, Left/Right while the overlay is hidden skip and flash the position on a replay or live feed alike (they reveal only where nothing can skip, such as an unseekable live feed), and Select reveals on Play/Pause; on phone, a double tap on the left/right half skips, so a single tap toggles the overlay after the double-tap interval. Fast-forward/rewind keys skip on both. This does not approve UX-003 or UX-010, which stay unconfirmed. | User decisions 2–5 on 2026-11-23: [TV Left/Right, Select and phone double tap](https://github.com/altendky/tachiai/issues/656#issuecomment-7109787483); [skip sizes and fast-forward/rewind](https://github.com/altendky/tachiai/issues/655#issuecomment-7109787969). User decision 4 on 2026-11-25 changed #656 decision 2 so that, on live feeds, hidden Left/Right skip instead of only revealing: [live transport works like replays](https://github.com/altendky/tachiai/issues/660#issuecomment-7167711457). | Android [replay navigation](prototype-ux.md#replay-navigation--655-part-1) and [live skip and scrub](prototype-ux.md#live-skip-and-scrub--660); not in the web fixture. |

[theme]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/PrototypeTheme.kt#L34-L38
[focus]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/TelevisionPresentation.kt#L30-L62
[viewer-focus]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/NativePairViewer.kt#L208-L238
[tabs]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/connections/StreamManagementScreen.kt#L388-L398
[viewer-input]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/NativePairViewer.kt#L240-L255
[visibility]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/NativePairViewer.kt#L106-L109
[picker]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/PrototypeSourcePicker.kt#L170-L215
[picker-menu]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/PrototypeSourcePicker.kt#L114-L123
[twitch]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/connections/TwitchCatalogConnectionScreen.kt#L51-L62
[viewer-menu]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/NativePairViewer.kt#L483-L501
[viewer-layout]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/NativePairViewer.kt#L658
[viewer-touch]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/NativePairViewer.kt#L779-L818
[media-commands]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/ForegroundPairMediaSession.kt#L25-L42
[media-integration]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/PrototypeActivity.kt#L888-L900
