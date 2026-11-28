/**
 * TrackMasks.kt's lookup for the web: one track's vocal masks, a model window at a time.
 * Window k covers STEP_SEC * k for ~22.3 s, so each overlaps the next by ~2.3 s; a frame
 * reads the earlier window until EDGE_SEC into the overlap (a window's padded edges are its
 * least trustworthy part). No imports beyond dsp.ts, for the worklet.
 */
import { HOP } from './dsp';

export const STEP_SEC = 20;
export const EDGE_SEC = 1;
export const MODEL_RATE = 44100;
export const MODEL_FPS = MODEL_RATE / HOP;
/** Samples in one model window at MODEL_RATE: (960 - 1) hops. */
export const WINDOW_SAMPLES = 959 * HOP;
const MODEL_BINS = 2049;

/** 8 bits a cell, [channel][bin][frame] over the window's frames; frame 0 at `start` seconds. */
export interface MaskWindow {
  start: number;
  values: Uint8Array;
  frames: number;
}

/** The window under `sec` and the one after it. */
export function neededWindows(sec: number): [number, number] {
  let first = Math.floor(sec / STEP_SEC);
  if (first > 0 && sec - first * STEP_SEC < EDGE_SEC) first--;
  return [first, first + 1];
}

export class MaskStore {
  private readonly windows = new Map<number, MaskWindow>();

  add(index: number, window: MaskWindow) {
    this.windows.set(index, window);
  }

  has(index: number) {
    return this.windows.has(index);
  }

  clear() {
    this.windows.clear();
  }

  /** Lets go of the windows wholly behind `sec`. */
  dropBefore(sec: number) {
    const [first] = neededWindows(sec);
    for (const index of this.windows.keys()) if (index < first) this.windows.delete(index);
  }

  windowFor(sec: number): MaskWindow | null {
    const k = Math.floor(sec / STEP_SEC);
    const covers = (w: MaskWindow | undefined, margin: number) =>
      w && sec >= w.start && sec < w.start + w.frames / MODEL_FPS - margin ? w : undefined;
    const own = covers(this.windows.get(k), 0);
    const earlier = covers(this.windows.get(k - 1), EDGE_SEC);
    return own && (sec - own.start >= EDGE_SEC || !earlier) ? own : earlier ?? own ?? null;
  }

  /** Fills `into` for one channel of the frame centred on `trackSample` of a `sampleRate` stream. */
  maskAt(trackSample: number, sampleRate: number, channel: number, into: Float32Array): boolean {
    const sec = trackSample / sampleRate;
    const window = this.windowFor(sec);
    if (!window) return false;
    const frame = Math.round((sec - window.start) * MODEL_FPS);
    if (frame < 0 || frame >= window.frames) return false;
    const scale = sampleRate / MODEL_RATE;
    const base = channel * MODEL_BINS;
    for (let bin = 0; bin < into.length; bin++) {
      const b = Math.min(MODEL_BINS - 1, Math.floor(bin * scale + 0.5));
      into[bin] = window.values[(base + b) * window.frames + frame]! / 255;
    }
    return true;
  }
}
