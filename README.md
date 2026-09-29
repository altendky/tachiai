# Tachiai

Tachiai presents multiple live or replay streams together and lets the viewer
adjust their relative timing.

The first useful pairing is ABEMA's Grand Sumo coverage with commentary from
the `midnightsumo` Twitch channel. The application is intentionally modeled as
a generic multi-stream tool rather than an ABEMA-, Twitch-, or sumo-specific
client.

The name comes from the *tachi-ai*, the mutually timed start of a sumo bout. It
also describes the application's central job: bringing independent streams
into alignment.

## Status

The project is in the documentation and feasibility-spike stage. On one Pixel
6, a narrow Android scaffold rendered ABEMA and Twitch concurrently. Separate
WebViews could not mix audio because their Chromium audio-focus delegates
paused one another. A debug-only single-WebContents probe subsequently kept
ABEMA top-level, hosted Twitch's official player in a child frame, and produced
two advancing videos with user-confirmed mixed audio. Sustained playback,
independent control, login persistence, supported ABEMA operation, provider
approval, and Android TV behavior remain unverified.

Android phone/tablet and Android TV are the first targets. iPhone/iPad,
Windows, macOS, and Linux are desired later targets, subject to each platform's
embedded-browser and DRM behavior.

## Documentation

Start with the [project overview](docs/src/project/index.md).

## License

Tachiai is available under either the [Apache License 2.0](LICENSE-APACHE) or
the [MIT License](LICENSE-MIT), at your option.
