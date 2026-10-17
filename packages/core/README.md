# Tachiai core

`tachiai-core` is a platform-independent Rust library for application rules. Its
first public API plans absolute, relative and live-default playback seeks and
derives a checked content clock. Android uses these same rules through a separate
binding; another Rust application can depend on this package directly. See
[`examples/seek.rs`](examples/seek.rs) for an independent consumer using only the
public API.

The library has no dependencies, I/O, player implementation, foreign-function
interface or session ownership. It accepts no provider credentials, resource
URLs or Android/Media3 types. Applications retain platform playback,
authentication, route, lifecycle, capability-observation and admission checks.
It does not claim iOS or browser playback compatibility.

## Contract

- Every numeric time is a signed 64-bit count of **milliseconds**. Unknown
  clocks are `None`; hosts normalize their platform's sentinel values first.
- Positions and default positions are relative to the observed playback window.
  A known window origin can derive a content clock; unrelated streams are not
  thereby synchronized.
- Decisions describe requests, never observed success. Hosts decide whether
  admission still permits dispatch and observe the result themselves.
- Live-default seeking uses its own player command and capability. The observed
  target records the current bounded plan, not an instruction to replace that
  command with arbitrary seeking.

This extraction preserves the existing Android behavior, including deliberately
tested edge cases: unavailable clocks precede ad refusal for absolute seeks;
nonlive/default-capability refusal precedes clock checks for live-default seeks;
`NoChange` wins over `Clamped`; relative overflow saturates; a stale point cannot
reverse direction when clamped; live-default recovery can proceed without a
known current position; and a negative position can contribute to a nonnegative
checked content clock. A fresh design could choose different validation or
failure priorities, but this extraction does not silently change them.

## Use and stability

This is an initial `0.1.0` Rust source API. There is no existing or promised
stable Rust binary ABI; build a consumer with its matching package version.
Platform bindings are separate adapters and own their transport/versioning
contracts. The crate is ready for reuse as a Rust library without requiring an
Android application or binding runtime.

From the repository root:

```sh
cargo test -p tachiai-core
cargo run -p tachiai-core --example seek
```

The repository pins its Rust toolchain. The minimum supported Rust version is
1.98.1 and the edition is 2024.
