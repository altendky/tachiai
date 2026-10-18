//! Native-language records and functions; domain decisions live in `tachiai-core`.

uniffi::setup_scaffolding!();

#[derive(Clone, Debug, uniffi::Record)]
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

impl From<&SeekContext> for tachiai_core::SeekContext {
    fn from(context: &SeekContext) -> Self {
        Self {
            position_ms: context.position_ms,
            duration_ms: context.duration_ms,
            window_start_ms: context.window_start_ms,
            default_position_ms: context.default_position_ms,
            live: context.live,
            seekable: context.seekable,
            can_seek_default: context.can_seek_default,
            playing_ad: context.playing_ad,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SeekPlan {
    Requested { target_ms: i64 },
    Clamped { target_ms: i64 },
    NoChange { target_ms: i64 },
    Unavailable,
    Unsupported,
    AdBlocked,
    OutsideWindow,
}

impl From<tachiai_core::SeekPlan> for SeekPlan {
    fn from(plan: tachiai_core::SeekPlan) -> Self {
        match plan {
            tachiai_core::SeekPlan::Requested { target_ms } => Self::Requested { target_ms },
            tachiai_core::SeekPlan::Clamped { target_ms } => Self::Clamped { target_ms },
            tachiai_core::SeekPlan::NoChange { target_ms } => Self::NoChange { target_ms },
            tachiai_core::SeekPlan::Unavailable => Self::Unavailable,
            tachiai_core::SeekPlan::Unsupported => Self::Unsupported,
            tachiai_core::SeekPlan::AdBlocked => Self::AdBlocked,
            tachiai_core::SeekPlan::OutsideWindow => Self::OutsideWindow,
        }
    }
}

#[uniffi::export]
pub fn content_time_ms(context: SeekContext) -> Option<i64> {
    tachiai_core::content_time_ms(&(&context).into())
}

#[uniffi::export]
pub fn plan_absolute_seek(context: Option<SeekContext>, target_ms: i64) -> SeekPlan {
    let context = context.as_ref().map(Into::into);
    tachiai_core::plan_absolute_seek(context.as_ref(), target_ms).into()
}

#[uniffi::export]
pub fn plan_relative_seek(context: Option<SeekContext>, delta_ms: i64) -> SeekPlan {
    let context = context.as_ref().map(Into::into);
    tachiai_core::plan_relative_seek(context.as_ref(), delta_ms).into()
}

#[uniffi::export]
pub fn plan_live_default(context: Option<SeekContext>) -> SeekPlan {
    let context = context.as_ref().map(Into::into);
    tachiai_core::plan_live_default(context.as_ref()).into()
}
