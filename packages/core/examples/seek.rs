use tachiai_core::{SeekContext, SeekPlan, plan_relative_seek};

fn main() {
    // A separate application supplies its own observed player state.
    let context = SeekContext {
        position_ms: Some(20_000),
        duration_ms: Some(60_000),
        window_start_ms: None,
        default_position_ms: None,
        live: false,
        seekable: true,
        can_seek_default: false,
        playing_ad: false,
    };
    let decision = plan_relative_seek(Some(&context), 5_000);
    assert_eq!(decision, SeekPlan::Requested { target_ms: 25_000 });
    // That application owns dispatch, admission checks and completion tracking.
    println!("{decision:?}");
}
