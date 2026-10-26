# Implementation difference ledger

This ledger records implemented system integration differences that support the
common UX. It does not approve user-visible exceptions; those belong in the
[UX variation register](ux-variations.md). A mechanism's visible product
consequences still need the explicit approval required by
[AGENTS.md](../../../AGENTS.md#common-ux-across-implementations).

## Android phone and TV

The initial October 18 audit uses
`d60ce4a3485305ae4681d8dca6b33cd8e48bb449`. Both targets use the same Android
application, Kotlin provider/player/storage code and Rust timing core.

| Area | Implemented difference | System purpose and boundary | Evidence |
| --- | --- | --- | --- |
| Foreground media integration | TV creates a foreground pair media session and sets the Activity's media controller; phone does not create that TV-specific session. Both use the existing guarded pair play/pause operations. | Connect Android media-key delivery to the foreground TV viewer, with no second audio-focus owner or new background playback service. This records wiring, not approval of remote button UX. | [Activity][media-session] |
| Launcher integration | The same APK declares ordinary Android and Leanback launcher categories, with Leanback and touchscreen support marked optional. | Make the app discoverable through the platform's applicable launcher without requiring a touchscreen. This records manifest integration, not approval of different in-app navigation or visual design. | [Manifest][manifest] |
| Device-mode detection | TV adaptations use Android's Configuration UI mode; ordinary phone mode uses the shared default path. | Select the applicable system integration inside one application. Detection is not authorization for every UX branch it selects. | [Configuration detection][detection] |

## Other implementations

No iOS, tvOS or web application implementation differences are established by
this initial ledger. Library choices, feasibility gaps and proposed adapters
remain in the [platform research](platforms.md) until implemented. Add factual
mechanism differences here as implementations arrive; do not turn research
assumptions into implemented behavior or approved UX exceptions.

### Controlled browser fixture

The provider-free browser fixture now reports bounded audio/video format
observations in its existing Playback status. Android uses Media3 input-format
callbacks; the browser adapter enables Shaka 5.3.2's quality observer and projects
`mediaqualitychanged` metadata associated with buffered content at its polled
playhead. The browser explicitly identifies this `last-sdk-buffered-playhead` basis,
keeps it separate from requested/selected tracks and omits unsupported or unknown
numeric properties. Neither the event nor selected tracks establish decoder or
acoustic output. This mechanism adds no Quality controls, saved preferences,
provider requests or approved UX exception. Its
[fixture scope and limits](web-pair-fixture.md#buffered-format-observations)
remain distinct from product/provider acceptance.

[media-session]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/PrototypeActivity.kt#L888-L900
[manifest]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/AndroidManifest.xml#L9-L33
[detection]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/TelevisionPresentation.kt#L23-L26
