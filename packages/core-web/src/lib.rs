//! Thin typed browser interface. All planning is delegated to the portable core.

use js_sys::BigInt;
use wasm_bindgen::prelude::*;

fn checked_ms(value: &BigInt) -> Result<i64, JsValue> {
    let raw: &JsValue = value.as_ref();
    if !raw.is_bigint() {
        return Err(js_sys::TypeError::new("Millisecond values must be bigint").into());
    }
    let decimal = value.to_string(10).map_err(JsValue::from)?;
    String::from(decimal).parse().map_err(|_| {
        js_sys::RangeError::new("Millisecond value is outside the signed 64-bit range").into()
    })
}

fn optional_ms(value: Option<BigInt>) -> Result<Option<i64>, JsValue> {
    value.as_ref().map(checked_ms).transpose()
}

/// Immutable player snapshot. Millisecond integers use JavaScript `bigint`.
#[wasm_bindgen]
pub struct SeekContext {
    inner: tachiai_core::SeekContext,
}

#[wasm_bindgen]
impl SeekContext {
    #[wasm_bindgen(constructor)]
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        position_ms: Option<BigInt>,
        duration_ms: Option<BigInt>,
        window_start_ms: Option<BigInt>,
        default_position_ms: Option<BigInt>,
        live: bool,
        seekable: bool,
        can_seek_default: bool,
        playing_ad: bool,
    ) -> Result<Self, JsValue> {
        Ok(Self {
            inner: tachiai_core::SeekContext {
                position_ms: optional_ms(position_ms)?,
                duration_ms: optional_ms(duration_ms)?,
                window_start_ms: optional_ms(window_start_ms)?,
                default_position_ms: optional_ms(default_position_ms)?,
                live,
                seekable,
                can_seek_default,
                playing_ad,
            },
        })
    }

    #[wasm_bindgen(js_name = contentTimeMs)]
    pub fn content_time_ms(&self) -> Option<i64> {
        tachiai_core::content_time_ms(&self.inner)
    }

    #[wasm_bindgen(js_name = planAbsoluteSeek)]
    pub fn plan_absolute_seek(&self, target_ms: BigInt) -> Result<SeekPlan, JsValue> {
        Ok(tachiai_core::plan_absolute_seek(Some(&self.inner), checked_ms(&target_ms)?).into())
    }

    #[wasm_bindgen(js_name = planRelativeSeek)]
    pub fn plan_relative_seek(&self, delta_ms: BigInt) -> Result<SeekPlan, JsValue> {
        Ok(tachiai_core::plan_relative_seek(Some(&self.inner), checked_ms(&delta_ms)?).into())
    }

    #[wasm_bindgen(js_name = planLiveDefault)]
    pub fn plan_live_default(&self) -> SeekPlan {
        tachiai_core::plan_live_default(Some(&self.inner)).into()
    }
}

#[wasm_bindgen]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SeekPlanKind {
    Requested,
    Clamped,
    NoChange,
    Unavailable,
    Unsupported,
    AdBlocked,
    OutsideWindow,
}

#[wasm_bindgen]
pub struct SeekPlan {
    kind: SeekPlanKind,
    target_ms: Option<i64>,
}

#[wasm_bindgen]
impl SeekPlan {
    #[wasm_bindgen(getter)]
    pub fn kind(&self) -> SeekPlanKind {
        self.kind
    }

    #[wasm_bindgen(getter, js_name = targetMs)]
    pub fn target_ms(&self) -> Option<i64> {
        self.target_ms
    }
}

impl From<tachiai_core::SeekPlan> for SeekPlan {
    fn from(plan: tachiai_core::SeekPlan) -> Self {
        let (kind, target_ms) = match plan {
            tachiai_core::SeekPlan::Requested { target_ms } => {
                (SeekPlanKind::Requested, Some(target_ms))
            }
            tachiai_core::SeekPlan::Clamped { target_ms } => {
                (SeekPlanKind::Clamped, Some(target_ms))
            }
            tachiai_core::SeekPlan::NoChange { target_ms } => {
                (SeekPlanKind::NoChange, Some(target_ms))
            }
            tachiai_core::SeekPlan::Unavailable => (SeekPlanKind::Unavailable, None),
            tachiai_core::SeekPlan::Unsupported => (SeekPlanKind::Unsupported, None),
            tachiai_core::SeekPlan::AdBlocked => (SeekPlanKind::AdBlocked, None),
            tachiai_core::SeekPlan::OutsideWindow => (SeekPlanKind::OutsideWindow, None),
        };
        Self { kind, target_ms }
    }
}
