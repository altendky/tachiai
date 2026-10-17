//! Platform-independent playback planning, without player or session ownership.
//!
//! All clock values are signed 64-bit **milliseconds**. Unknown values are
//! [`None`], never a guessed zero or a platform-specific sentinel. Results are
//! plans, not evidence that a player accepted or completed an operation.

/// Observed timing and capabilities of one playback window.
///
/// `position_ms` and `default_position_ms` are relative to that window;
/// `duration_ms` is its length. A sliding live window can leave an observed
/// position outside `[0, duration_ms]`. `window_start_ms`, when known, gives
/// that window's content-clock origin; it does not establish synchronization
/// between unrelated streams.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SeekContext {
    pub position_ms: Option<i64>,
    pub duration_ms: Option<i64>,
    pub window_start_ms: Option<i64>,
    pub default_position_ms: Option<i64>,
    pub live: bool,
    pub seekable: bool,
    pub can_seek_default: bool,
    pub playing_ad: bool,
}

/// A seek decision. Only target-bearing variants describe a window position.
///
/// A host may dispatch [`Self::Requested`] or [`Self::Clamped`];
/// [`Self::NoChange`] requires no seek. Default-position plans must use the
/// player's default-position command rather than an arbitrary-position command.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SeekPlan {
    Requested { target_ms: i64 },
    Clamped { target_ms: i64 },
    NoChange { target_ms: i64 },
    Unavailable,
    Unsupported,
    AdBlocked,
    OutsideWindow,
}

/// Derive a content clock only when its origin and checked sum are nonnegative.
///
/// Negative window-relative positions are allowed when the resulting clock is
/// nonnegative. This function does not require seek capability or a duration.
pub fn content_time_ms(context: &SeekContext) -> Option<i64> {
    let origin = context.window_start_ms?;
    if origin < 0 {
        return None;
    }
    origin
        .checked_add(context.position_ms?)
        .filter(|value| *value >= 0)
}

/// Plan an absolute window-relative seek, clamping to its inclusive bounds.
///
/// Missing position or nonpositive/unknown duration takes precedence over an
/// advertisement, which takes precedence over unsupported arbitrary seeking.
/// An unchanged bounded position is `NoChange`, even if the request was clamped.
pub fn plan_absolute_seek(context: Option<&SeekContext>, target_ms: i64) -> SeekPlan {
    let Some(context) = context else {
        return SeekPlan::Unavailable;
    };
    let (Some(position), Some(duration)) = (context.position_ms, context.duration_ms) else {
        return SeekPlan::Unavailable;
    };
    if duration <= 0 {
        return SeekPlan::Unavailable;
    }
    if context.playing_ad {
        return SeekPlan::AdBlocked;
    }
    if !context.seekable {
        return SeekPlan::Unsupported;
    }
    bounded_plan(Some(position), target_ms, duration)
}

/// Plan a relative seek without allowing clamping to reverse its direction.
///
/// Addition saturates on overflow before applying absolute-seek policy. A stale
/// position outside a sliding window cannot turn a backward request forward,
/// a forward request backward, or a zero movement into a different position.
pub fn plan_relative_seek(context: Option<&SeekContext>, delta_ms: i64) -> SeekPlan {
    let Some(position) = context.and_then(|value| value.position_ms) else {
        return SeekPlan::Unavailable;
    };
    let plan = plan_absolute_seek(context, position.saturating_add(delta_ms));
    let bounded = match plan {
        SeekPlan::Requested { target_ms }
        | SeekPlan::Clamped { target_ms }
        | SeekPlan::NoChange { target_ms } => target_ms,
        _ => return plan,
    };
    if (delta_ms < 0 && bounded > position)
        || (delta_ms > 0 && bounded < position)
        || (delta_ms == 0 && bounded != position)
    {
        return SeekPlan::OutsideWindow;
    }
    plan
}

/// Plan a live default-position seek using its separate player capability.
///
/// This does not require arbitrary seekability or a known current position.
/// Nonlive/unsupported capability takes precedence over missing clocks, which
/// take precedence over an advertisement. A default beyond the observed window
/// is clamped; the host still dispatches its default-position player command.
pub fn plan_live_default(context: Option<&SeekContext>) -> SeekPlan {
    let Some(context) = context else {
        return SeekPlan::Unavailable;
    };
    if !context.live || !context.can_seek_default {
        return SeekPlan::Unsupported;
    }
    let (Some(target), Some(duration)) = (context.default_position_ms, context.duration_ms) else {
        return SeekPlan::Unavailable;
    };
    if duration <= 0 {
        return SeekPlan::Unavailable;
    }
    if context.playing_ad {
        return SeekPlan::AdBlocked;
    }
    bounded_plan(context.position_ms, target, duration)
}

fn bounded_plan(position_ms: Option<i64>, target_ms: i64, duration_ms: i64) -> SeekPlan {
    let bounded = target_ms.clamp(0, duration_ms);
    if position_ms == Some(bounded) {
        SeekPlan::NoChange { target_ms: bounded }
    } else if bounded != target_ms {
        SeekPlan::Clamped { target_ms: bounded }
    } else {
        SeekPlan::Requested { target_ms: bounded }
    }
}
