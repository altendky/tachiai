# Persistent Android development device

The repository defines one local Linux x86-64 development emulator in
[`apps/android/dev-device.toml`](../../../apps/android/dev-device.toml).
It is a persistent device, not a disposable test runner: normal setup, startup,
shutdown and APK updates preserve app-private data, including saved Twitch
authorization, route profiles and cached ABEMA bundles. Provider expiry,
revocation and application retention policies still apply.

## Agent device selection

The persistent `tachiai-dev` emulator is the default for routine project
deployment, UI inspection, debugging and playback experiments. Agents preserve
its authorization, routes and app data and use the explicitly verified emulator
target. Tests that reset state belong on a separate clean test device.

An explicit request to install/deploy to the phone or debug something on the
phone overrides that default for the requested task. Verify the physical device
identity and availability; there is no need to ask again merely to choose the
already requested phone. Otherwise, emulator failure or a need for real-device
verification requires asking before using the phone, not silently switching to
an attached physical device. Existing authentication, capture and signing
boundaries apply on both devices.

## Local setup

Requirements are Linux x86-64, usable KVM, Java 17 or newer, the repository's
`mise`/`uv` tools, disk space for the SDK/image/userdata, and a desktop session
for the visible emulator. Android Studio is optional. The initial profile is a
Pixel 6-sized device, four virtual CPU cores, 4096 MiB RAM, Android 16/API 36
Google APIs x86-64, with dedicated emulator console port 5580.
Hardware keyboard input is explicitly enabled for desktop typing and virtual
navigation keys; disabling it is a device-definition mismatch.
The profile's non-secret `[preferences]` section requests dark mode and
three-button navigation instead of gestures. Start reapplies these preferences
after boot, including when the device is already running. To apply changes
without restarting, use `mise run android-emulator-configure`. Preferences can
change without recreating the device; hardware/SDK changes still require review.
API 36 is a development baseline, not parity with the Android 17/API 37 phone
or a change to the app's target/compile SDK. Google APIs is not a Play Store
image, and does not establish DRM/provider compatibility.

From the repository root:

```sh
mise run android-emulator-setup
mise run android-emulator-start
mise run android-emulator-status
mise run android-emulator-configure
mise run android-emulator-install
```

Setup downloads a checksum-pinned Google command-line tools bootstrap and the
specified SDK packages. Accept Google's SDK licenses when prompted. An explicit
`setup --seed-sdk /path/to/sdk` can instead reuse matching command-line tools
and already accepted licenses; it copies no AVD, app data, key or account state.
The emulator/SDK package IDs can be republished upstream. Expected installed
revisions are checked; an unexpected revision stops setup rather than silently
changing the device definition. A removed upstream revision needs a reviewed
tooling/profile update, not an automatic substitute.

The install command consumes the existing debug APK; it does not build it.
Continue using the documented Android build environment and shared debug
signing identity. It verifies the APK certificate with `apksigner` and checks the
application ID before updating
with `adb install -r` and launches Tachiai's normal entry point. A wrong signer
or failed update stops; there is no uninstall/reinstall fallback.

For a visible window, start from the desktop session. For agent-only operation:

```sh
uv run scripts/android-dev-device.py start --headless
mise run android-emulator-stop
```

Shutdown is a normal emulator shutdown; startup can reuse its Quick Boot state.
Stop waits for the device to disconnect while the emulator saves its snapshot.
After a reviewed hardware-setting change, use `start --cold-boot` to skip loading
the old snapshot. Cold boot preserves the writable device disks and app data;
it is not a factory reset.
The profile's `[runtime] gpu = "software"` is the provisional graphics default
after the host-rendering corruption described below. It retains KVM CPU
acceleration and is not a change to the app's playback or DRM logic. Runtime
preferences can change without recreating the AVD; they apply on the next launch,
not to an already running emulator. For a temporary host-rendering comparison,
stop the device, then run:

```sh
uv run scripts/android-dev-device.py start --gpu auto --cold-boot
```

This keeps userdata and KVM CPU acceleration but changes the graphics backend.
The ordinary start command uses the profile's software default. Explicit
renderer/cold-boot requests on an already running device are refused rather
than reported as applied.
No helper wipes data, clears app storage or recreates an existing device.
Running setup again validates and preserves the existing owned device. Definition
changes or unexpected hardware/image settings require manual review; the helper
does not migrate or overwrite them. There is intentionally no reset command.
An interrupted first-time creation can leave an unmarked device; that also
requires inspection and explicit recovery, not an automatic replacement.
If a factory reset is explicitly requested later, setup/start can recreate the
hardware and reapply these Android preferences from the repo. A reset does not
restore Twitch authorization, browser sessions or private route configuration;
those require user-controlled setup again. They are not part of the recipe.

## Local state and sensitive configuration

Default local state is `${XDG_DATA_HOME:-$HOME/.local/share}/tachiai/android/`:

- `sdk/`: downloaded public SDK tools and system image;
- `avd/`: device definition, writable disks and snapshots;
- `emulator.log`: emulator process diagnostics, not app/provider log collection.

Use the helper's `--state-dir /absolute/local/path` before the subcommand for a
different location. Device state inside the repository is rejected. All commands
must use the same state directory. Generated VM files, snapshots, authorization
and routes stay outside Git. Treat the device disks and screenshots as sensitive
when logged in; do not export snapshots or capture activation/login screens.
The helper sets a private creation umask; normal host filesystem security remains
necessary and this is not whole-disk encryption.

Authorize Twitch once through the existing provider-controlled device flow on
the emulator. Import a route through the existing Routes screen and assign it
through Providers. Do not copy the phone's app-private data, browser cookies or
encrypted authorization files. A dedicated Proton WireGuard configuration is
preferable: using one peer profile simultaneously on the phone and emulator can
make their peer endpoint roam between them.

## Device targeting and verification boundaries

Every mutating ADB command targets `emulator-5580` explicitly, after checking its
reported AVD name is `tachiai-dev` and its reported data directory matches the
owned local AVD. An offline emulator or a different AVD using that port causes
refusal, never a fallback to an attached phone. For manual
diagnostics also specify the serial explicitly:

```sh
adb -s emulator-5580 shell getprop ro.build.version.sdk
adb -s emulator-5580 shell getprop ro.product.cpu.abi
```

The app's native route bridge already packages x86-64 alongside ARM64. This
profile uses KVM acceleration, not ARM instruction emulation. Keep builds in the
existing disposable Android build environment; a local emulator SDK does not
replace it.

The lifecycle helpers do not execute instrumentation tests. Such tests can
modify settings, authorize fake fixtures or clear test state; Gradle connected
test tasks can also select attached physical devices. Use a separate clean test
device when adding automated device-test execution. CI continues compiling,
but not executing, instrumentation tests. Provider-free helper safety tests run
in the repository hooks/CI without starting an emulator.

Planned checks include rotation/layout, app lifecycle and local two-feed timing
fixtures. Actual ABEMA CDM/helper compatibility, provider playback, mixed audio,
hardware decode/performance and Android TV behavior require separate observations.
An emulator startup or successful installation does not prove those capabilities.
No additional provider authentication, media origins or licensing scope is
authorized by this tooling.

Official references:

- [Android emulator acceleration](https://developer.android.com/studio/run/emulator-acceleration)
- [Command-line emulator and persistent AVD storage](https://developer.android.com/studio/run/emulator-commandline)
- [Media3 device support and physical-device guidance](https://developer.android.com/media/media3/exoplayer/supported-devices)

## Initial host observation — 2026-10-08

On the Linux x86-64 development workstation, the selected SDK revisions installed
and `tachiai-dev` started in a visible window with KVM reported usable. A signed
debug APK from the existing build installed successfully; its public SHA-256
certificate matched the shared signing procedure, and its APK contains the
x86-64 route bridge. API/ABI readback was 36/x86-64. Chrome is installed in this
image. A normal shutdown and restart preserved the installed application and
a non-sensitive landscape rotation setting. Re-running setup preserved the
existing device without downloads or recreation.

These observations cover local tooling, install, screenshot access and basic VM
persistence only. Region/account state was the host's system connection with no
imported route or Twitch authorization. No native provider playback, DRM exchange,
mixed-audio or route activation check was performed. Account/route retention
after authorization remains to be checked separately. Startup exposed emulator
config spacing and newer `apksigner` output differences; the helper parses both
and its provider-free fixtures cover those cases.

The subsequent approved input correction changed only `hw.keyboard` and the
owned definition marker before a cold restart; writable device disks and the
installed app were retained. Active hardware readback now reports keyboard
enabled. Dark mode reports `yes`, and the three-button navigation overlay is
enabled while the gestural overlay is disabled. Desktop typing and toolbar Home
still require user confirmation; the earlier console-only Home experiment did
not establish that the toolbar issue was fixed. No route values or credentials
were read or copied during this correction.

The user subsequently reported native ABEMA video initially rendering normally,
then becoming green/cyan and flashing, briefly recovering and failing again.
The behavior occurred with both newly configured Windscribe and Proton
WireGuard routes on this same API 36/x86-64 emulator. Exact selected content,
account state, accepted exit and quality at each transition were not captured.
Sanitized codec diagnostics contained `c2.goldfish.h264.decoder` and 854×480/
1920×1080 configurations; that does not establish a transition/corruption
correlation. The auto renderer selected host graphics, with Intel OpenGL and
NVIDIA Vulkan reported. A route-specific failure is less likely, but graphics,
decoder/reconfiguration and provider behavior remain hypotheses. A separately
approved software-rendering comparison preserves the device and provider data;
its playback result must be recorded after observation, not inferred from boot.
The comparison cold-booted successfully with `-gpu software`: readback reported
SwANGLE/SwiftShader GLES and Lavapipe/llvmpipe Vulkan instead of host GPUs. KVM
remained usable and Tachiai remained installed. The emulator's AVC decoding
property remained `2`; changing graphics rendering does not by itself establish
a different decoder. The user then reported that ABEMA playback "seems fine"
under this software configuration. This is a bounded visual observation with no
recorded duration or new acoustic confirmation, not sustained-playback proof.
It supports investigating the host graphics path rather than treating the route
provider as the established cause. The user approved software graphics as the
provisional repository default;
[issue #62](https://github.com/altendky/tachiai/issues/62) retains the
host-rendering investigation as a diagnostic follow-up, not a settled root-cause
claim.
