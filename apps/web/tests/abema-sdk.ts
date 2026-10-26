// Vite resolves the same pinned SDK module used by the diagnostic under test.
export { default } from 'shaka-player/dist/shaka-player.compiled.debug';

export function declaredModel(uri: () => string, start = 0, end: number | null = null) {
  const stream = { encrypted: false, drmInfos: [], keyIds: new Set(), createSegmentIndex: async () => {}, segmentIndex: {
    getIteratorForTime: () => { let done = false; return { next: () => done ? { done: true } : (done = true, { done: false,
      value: { getUris: () => [uri()], getStartByte: () => start, getEndByte: () => end,
        getStartTime: () => 0, getEndTime: () => 2 } }) }; } } };
  return { stream, model: { variants: [{ video: stream }],
    presentationTimeline: { getSegmentAvailabilityStart: () => 0, getSegmentAvailabilityEnd: () => 2 } } };
}
