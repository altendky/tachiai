use tachiai_core::{
    SeekContext, SeekPlan, content_time_ms, plan_absolute_seek, plan_live_default,
    plan_relative_seek,
};

fn sample() -> SeekContext {
    SeekContext {
        position_ms: Some(20_000),
        duration_ms: Some(60_000),
        window_start_ms: Some(1_000_000),
        default_position_ms: Some(50_000),
        live: true,
        seekable: true,
        can_seek_default: true,
        playing_ad: false,
    }
}

#[test]
fn relative_directions_mean_later_and_earlier_media() {
    let context = sample();
    assert_eq!(
        plan_relative_seek(Some(&context), 5_000),
        SeekPlan::Requested { target_ms: 25_000 }
    );
    assert_eq!(
        plan_relative_seek(Some(&context), -5_000),
        SeekPlan::Requested { target_ms: 15_000 }
    );
    assert_eq!(
        plan_relative_seek(Some(&context), 0),
        SeekPlan::NoChange { target_ms: 20_000 }
    );
}

#[test]
fn seeks_clamp_inclusively_to_the_observed_window() {
    let context = sample();
    for (requested, expected) in [
        (-1, SeekPlan::Clamped { target_ms: 0 }),
        (0, SeekPlan::Requested { target_ms: 0 }),
        (60_000, SeekPlan::Requested { target_ms: 60_000 }),
        (60_001, SeekPlan::Clamped { target_ms: 60_000 }),
    ] {
        assert_eq!(plan_absolute_seek(Some(&context), requested), expected);
    }
    assert_eq!(
        plan_relative_seek(Some(&context), -30_000),
        SeekPlan::Clamped { target_ms: 0 }
    );
    assert_eq!(
        plan_relative_seek(Some(&context), 100_000),
        SeekPlan::Clamped { target_ms: 60_000 }
    );
}

#[test]
fn no_change_takes_precedence_over_clamped() {
    for (position, request) in [(0, -1), (60_000, 60_001)] {
        let context = SeekContext {
            position_ms: Some(position),
            default_position_ms: Some(request),
            ..sample()
        };
        let expected = SeekPlan::NoChange {
            target_ms: position,
        };
        assert_eq!(plan_absolute_seek(Some(&context), request), expected);
        assert_eq!(plan_live_default(Some(&context)), expected);
    }
}

#[test]
fn unavailable_clocks_are_never_treated_as_zero() {
    assert_eq!(plan_absolute_seek(None, 5_000), SeekPlan::Unavailable);
    assert_eq!(plan_relative_seek(None, 5_000), SeekPlan::Unavailable);
    assert_eq!(plan_live_default(None), SeekPlan::Unavailable);
    for context in [
        SeekContext {
            position_ms: None,
            ..sample()
        },
        SeekContext {
            duration_ms: None,
            ..sample()
        },
        SeekContext {
            duration_ms: Some(0),
            ..sample()
        },
        SeekContext {
            duration_ms: Some(-1),
            ..sample()
        },
    ] {
        assert_eq!(
            plan_absolute_seek(Some(&context), 5_000),
            SeekPlan::Unavailable
        );
        assert_eq!(
            plan_relative_seek(Some(&context), 5_000),
            SeekPlan::Unavailable
        );
    }
}

#[test]
fn absolute_failure_precedence_preserves_unavailable_then_ad_then_capability() {
    let ad_without_capability = SeekContext {
        playing_ad: true,
        seekable: false,
        ..sample()
    };
    for context in [
        SeekContext {
            position_ms: None,
            ..ad_without_capability
        },
        SeekContext {
            duration_ms: None,
            ..ad_without_capability
        },
        SeekContext {
            duration_ms: Some(0),
            ..ad_without_capability
        },
    ] {
        assert_eq!(plan_absolute_seek(Some(&context), 0), SeekPlan::Unavailable);
        assert_eq!(plan_relative_seek(Some(&context), 1), SeekPlan::Unavailable);
    }
    assert_eq!(
        plan_absolute_seek(Some(&ad_without_capability), 0),
        SeekPlan::AdBlocked
    );
    assert_eq!(
        plan_relative_seek(Some(&ad_without_capability), 1),
        SeekPlan::AdBlocked
    );
    let unsupported = SeekContext {
        playing_ad: false,
        ..ad_without_capability
    };
    assert_eq!(
        plan_absolute_seek(Some(&unsupported), 0),
        SeekPlan::Unsupported
    );
    assert_eq!(
        plan_relative_seek(Some(&unsupported), 1),
        SeekPlan::Unsupported
    );
}

#[test]
fn relative_overflow_saturates_instead_of_wrapping_direction() {
    let context = sample();
    assert_eq!(
        plan_relative_seek(Some(&context), i64::MAX),
        SeekPlan::Clamped { target_ms: 60_000 }
    );
    assert_eq!(
        plan_relative_seek(Some(&context), i64::MIN),
        SeekPlan::Clamped { target_ms: 0 }
    );
    let minimum_position = SeekContext {
        position_ms: Some(i64::MIN),
        ..context
    };
    assert_eq!(
        plan_relative_seek(Some(&minimum_position), -1),
        SeekPlan::OutsideWindow
    );
    let maximum_position = SeekContext {
        position_ms: Some(i64::MAX),
        duration_ms: Some(i64::MAX),
        ..context
    };
    assert_eq!(
        plan_relative_seek(Some(&maximum_position), 1),
        SeekPlan::NoChange {
            target_ms: i64::MAX
        }
    );
}

#[test]
fn stale_points_never_reverse_relative_direction() {
    for (position, delta) in [(-5_000, -5_000), (65_000, 5_000), (-5_000, 0), (65_000, 0)] {
        let context = SeekContext {
            position_ms: Some(position),
            ..sample()
        };
        assert_eq!(
            plan_relative_seek(Some(&context), delta),
            SeekPlan::OutsideWindow
        );
    }
}

#[test]
fn moving_toward_the_window_from_a_stale_point_remains_possible() {
    let before = SeekContext {
        position_ms: Some(-5_000),
        ..sample()
    };
    assert_eq!(
        plan_relative_seek(Some(&before), 1),
        SeekPlan::Clamped { target_ms: 0 }
    );
    assert_eq!(
        plan_relative_seek(Some(&before), 5_000),
        SeekPlan::Requested { target_ms: 0 }
    );
    let after = SeekContext {
        position_ms: Some(65_000),
        ..sample()
    };
    assert_eq!(
        plan_relative_seek(Some(&after), -1),
        SeekPlan::Clamped { target_ms: 60_000 }
    );
    assert_eq!(
        plan_relative_seek(Some(&after), -5_000),
        SeekPlan::Requested { target_ms: 60_000 }
    );
}

#[test]
fn ad_refusal_precedes_stale_relative_direction_checks() {
    let context = SeekContext {
        position_ms: Some(-5_000),
        playing_ad: true,
        ..sample()
    };
    assert_eq!(plan_relative_seek(Some(&context), -1), SeekPlan::AdBlocked);
}

#[test]
fn live_default_has_a_separate_capability_and_can_recover_unknown_or_stale_positions() {
    for position in [None, Some(-5_000), Some(65_000)] {
        let context = SeekContext {
            position_ms: position,
            seekable: false,
            ..sample()
        };
        assert_eq!(
            plan_live_default(Some(&context)),
            SeekPlan::Requested { target_ms: 50_000 }
        );
    }
    let replay = SeekContext {
        live: false,
        ..sample()
    };
    assert_eq!(plan_live_default(Some(&replay)), SeekPlan::Unsupported);
    let unsupported = SeekContext {
        can_seek_default: false,
        ..sample()
    };
    assert_eq!(plan_live_default(Some(&unsupported)), SeekPlan::Unsupported);
}

#[test]
fn live_default_clamps_its_observed_target_without_inventing_a_window() {
    for (target, bounded) in [(i64::MIN, 0), (i64::MAX, 60_000)] {
        let context = SeekContext {
            default_position_ms: Some(target),
            ..sample()
        };
        assert_eq!(
            plan_live_default(Some(&context)),
            SeekPlan::Clamped { target_ms: bounded }
        );
    }
    for context in [
        SeekContext {
            default_position_ms: None,
            ..sample()
        },
        SeekContext {
            duration_ms: None,
            ..sample()
        },
        SeekContext {
            duration_ms: Some(0),
            ..sample()
        },
        SeekContext {
            duration_ms: Some(-1),
            ..sample()
        },
    ] {
        assert_eq!(plan_live_default(Some(&context)), SeekPlan::Unavailable);
    }
}

#[test]
fn live_default_failure_precedence_is_capability_then_clocks_then_ad() {
    let missing_ad_context = SeekContext {
        position_ms: None,
        default_position_ms: None,
        duration_ms: None,
        playing_ad: true,
        ..sample()
    };
    for context in [
        SeekContext {
            live: false,
            ..missing_ad_context
        },
        SeekContext {
            can_seek_default: false,
            ..missing_ad_context
        },
    ] {
        assert_eq!(plan_live_default(Some(&context)), SeekPlan::Unsupported);
    }
    assert_eq!(
        plan_live_default(Some(&missing_ad_context)),
        SeekPlan::Unavailable
    );
    let ad = SeekContext {
        playing_ad: true,
        ..sample()
    };
    assert_eq!(plan_live_default(Some(&ad)), SeekPlan::AdBlocked);
}

#[test]
fn stable_content_clock_requires_known_origin_and_checked_nonnegative_sum() {
    let context = sample();
    assert_eq!(content_time_ms(&context), Some(1_020_000));
    let slid = SeekContext {
        window_start_ms: Some(1_005_000),
        position_ms: Some(15_000),
        ..context
    };
    assert_eq!(content_time_ms(&slid), content_time_ms(&context));
    for invalid in [
        SeekContext {
            window_start_ms: None,
            ..context
        },
        SeekContext {
            position_ms: None,
            ..context
        },
        SeekContext {
            window_start_ms: Some(-1),
            ..context
        },
        SeekContext {
            window_start_ms: Some(i64::MAX),
            ..context
        },
        SeekContext {
            position_ms: Some(i64::MIN),
            ..context
        },
    ] {
        assert_eq!(content_time_ms(&invalid), None);
    }
}

#[test]
fn negative_window_position_can_still_have_a_valid_content_clock() {
    let context = SeekContext {
        position_ms: Some(-5_000),
        ..sample()
    };
    assert_eq!(content_time_ms(&context), Some(995_000));
    let zero = SeekContext {
        position_ms: Some(-1_000_000),
        ..context
    };
    assert_eq!(content_time_ms(&zero), Some(0));
    let negative = SeekContext {
        position_ms: Some(-1_000_001),
        ..context
    };
    assert_eq!(content_time_ms(&negative), None);
}

#[test]
fn content_clock_does_not_imply_seekability_live_state_or_admission() {
    let context = SeekContext {
        duration_ms: None,
        live: false,
        seekable: false,
        can_seek_default: false,
        playing_ad: true,
        ..sample()
    };
    assert_eq!(content_time_ms(&context), Some(1_020_000));
}

#[test]
fn content_clock_accepts_the_exact_signed_maximum() {
    let context = SeekContext {
        position_ms: Some(0),
        window_start_ms: Some(i64::MAX),
        ..sample()
    };
    assert_eq!(content_time_ms(&context), Some(i64::MAX));
    let overflow = SeekContext {
        position_ms: Some(1),
        ..context
    };
    assert_eq!(content_time_ms(&overflow), None);
}
