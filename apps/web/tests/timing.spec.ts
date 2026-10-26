import { expect, test } from '@playwright/test';
import { milliseconds } from '../src/timing';

test('clock conversion retains signed stale positions and refuses unknown or inexact values', () => {
  expect(milliseconds(-2.125)).toBe(-2125);
  expect(milliseconds(1.2346)).toBe(1235);
  for (const value of [NaN, Infinity, -Infinity, Number.MAX_SAFE_INTEGER]) {
    expect(milliseconds(value)).toBeUndefined();
  }
});
