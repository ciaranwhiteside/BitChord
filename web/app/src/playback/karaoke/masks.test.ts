import { describe, expect, it } from 'vitest';
import { BINS } from './dsp';
import { EDGE_SEC, MaskStore, type MaskWindow, neededWindows, STEP_SEC } from './masks';

// The same cases as TrackMasksTest.kt.
const FRAMES = 960;
const window = (index: number, value = index + 1, start = index * STEP_SEC): MaskWindow => ({
  start,
  values: new Uint8Array(2 * BINS * FRAMES).fill(value),
  frames: FRAMES,
});

function answer(store: MaskStore, sec: number, rate = 44100): number | null {
  const into = new Float32Array(BINS);
  return store.maskAt(Math.round(sec * rate), rate, 0, into) ? Math.round(into[0]! * 255) : null;
}

describe('masks', () => {
  it('needs the window under the playhead and the next, the earlier one just past a step', () => {
    expect(neededWindows(5)).toEqual([0, 1]);
    expect(neededWindows(25)).toEqual([1, 2]);
    expect(neededWindows(STEP_SEC + EDGE_SEC / 2)).toEqual([0, 1]);
  });

  it('reads a window and hands over a second into the overlap', () => {
    const store = new MaskStore();
    store.add(0, window(0));
    store.add(1, window(1));
    expect(answer(store, 5)).toBe(1);
    expect(answer(store, STEP_SEC + EDGE_SEC - 0.1)).toBe(1);
    expect(answer(store, STEP_SEC + EDGE_SEC + 0.1)).toBe(2);
    expect(answer(store, 50)).toBeNull();
  });

  it('lets go of windows behind the playhead', () => {
    const store = new MaskStore();
    [0, 1, 2].forEach((k) => store.add(k, window(k)));
    store.dropBefore(45);
    expect(store.has(0)).toBe(false);
    expect(store.has(1)).toBe(false);
    expect(answer(store, 45)).toBe(3);
  });

  it('follows a window start and maps bins across sample rates', () => {
    const values = new Uint8Array(2 * BINS * FRAMES);
    for (let b = 0; b < BINS; b++) values[b * FRAMES] = b % 200;
    const store = new MaskStore();
    store.add(0, { start: 0.5, values, frames: FRAMES });
    const into = new Float32Array(BINS);
    expect(store.maskAt(24000, 48000, 0, into)).toBe(true);
    // Bin 1000 at 48 kHz is model bin round(1000 * 48000 / 44100) = 1088.
    expect(into[1000]).toBeCloseTo((1088 % 200) / 255, 6);
  });
});
