use tachiai_core::event_mark_offset_ms;

#[test]
fn equal_progress_has_zero_offset_despite_unrelated_clock_origins() {
    assert_eq!(
        event_mark_offset_ms(100, 150, 9_000_000, 9_000_050),
        Some(0)
    );
    assert_eq!(event_mark_offset_ms(0, 0, i64::MAX, i64::MAX), Some(0));
}

#[test]
fn positive_means_a_ahead_and_negative_means_b_ahead() {
    assert_eq!(event_mark_offset_ms(100, 175, 5_000, 5_050), Some(25));
    assert_eq!(event_mark_offset_ms(100, 150, 5_000, 5_075), Some(-25));
}

#[test]
fn observed_progress_includes_pauses_and_backward_or_forward_seeks() {
    assert_eq!(event_mark_offset_ms(100, 100, 5_000, 5_025), Some(-25));
    assert_eq!(event_mark_offset_ms(100, 75, 5_000, 5_000), Some(-25));
    assert_eq!(event_mark_offset_ms(100, 125, 5_000, 5_000), Some(25));
    assert_eq!(event_mark_offset_ms(100, 75, 5_000, 4_975), Some(0));
}

#[test]
fn every_input_must_be_a_nonnegative_clock() {
    for field in 0..4 {
        for invalid in [-1, i64::MIN] {
            let mut clocks = [0; 4];
            clocks[field] = invalid;
            assert_eq!(
                event_mark_offset_ms(clocks[0], clocks[1], clocks[2], clocks[3]),
                None
            );
        }
    }
}

#[test]
fn difference_overflow_is_unavailable_instead_of_saturated() {
    assert_eq!(event_mark_offset_ms(0, i64::MAX, 1, 0), None);
    assert_eq!(event_mark_offset_ms(i64::MAX, 0, 0, 2), None);
    assert_eq!(event_mark_offset_ms(0, i64::MAX, 0, 0), Some(i64::MAX));
    assert_eq!(event_mark_offset_ms(i64::MAX, 0, 0, 1), Some(i64::MIN));
}
