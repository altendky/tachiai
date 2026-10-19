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
| UX-003 | Revealing playback controls | TV consumes the first directional/Select press to reveal controls without activating an action. Phone background/primary-feed taps toggle the controls; a floating-feed tap retains its swap action. | Unconfirmed; explicit design approval not recorded. | [Viewer input][viewer-input], [touch handling][viewer-touch] |
| UX-004 | Control-bar visibility | TV suppresses the playback control-bar auto-hide while the dock has focus. Phone retains its playing/no-panel auto-hide rule without that focus exception. | Unconfirmed; explicit design approval not recorded. | [Visibility][visibility] |
| UX-005 | Feed assignment | TV uses labeled Feed A/B columns and 72 dp outlined A/B assignment buttons. Phone uses 48 dp checkbox targets. | Unconfirmed; explicit design approval not recorded. | [Picker][picker] |
| UX-006 | Screen sharing entry | TV hides the picker menu's Screen sharing action; phone can show it. | Unconfirmed; explicit design approval not recorded. | [Picker menu][picker-menu] |
| UX-007 | Twitch activation | QR/code activation is shared, but TV hides the Open in Brave/Open in Chrome actions shown on phone. | Unconfirmed; explicit design approval not recorded. | [Connection screen][twitch] |
| UX-008 | Viewer orientation | TV always uses the viewer's landscape layout calculation and omits the manual orientation action available on phone. | Unconfirmed; explicit design approval not recorded. | [Menu][viewer-menu], [layout][viewer-layout] |
| UX-009 | Floating feed placement/size | TV adds floating-feed corner and Small/Medium/Large menus. Phone does not show those menus; common touch drag/swap handling remains implemented. | Unconfirmed; explicit design approval not recorded. | [Menu][viewer-menu], [touch handling][viewer-touch] |
| UX-010 | Foreground media buttons | TV explicitly routes foreground media Play/Pause/Toggle commands to the guarded two-feed pair. Phone has no equivalent explicitly installed pair media-session rule. This is a code observation, not proof of physical-button behavior on either device. | Unconfirmed; explicit design approval not recorded. | [Media commands][media-commands], [Activity integration][media-integration] |

## Approved exceptions

No exception has an explicit approval reference recorded in this initial audit.
When the user approves a specific deviation, move its row here with the exact
scope, affected implementations, approval date and a durable reference to that
discussion. Mark approved designs that are not implemented yet as pending
implementation. A changed scope requires its own approval. Unapproved proposals,
ordinary shared behavior, accidental bugs and platform research do not belong
in this register.

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
