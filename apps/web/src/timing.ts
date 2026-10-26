// Refuse unknown, infinite or inexact clocks rather than fabricating core inputs.
export function milliseconds(seconds: number): number | undefined {
  const value = Math.round(seconds * 1000);
  return Number.isFinite(seconds) && Number.isSafeInteger(value) ? value : undefined;
}
