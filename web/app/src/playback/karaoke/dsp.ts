/**
 * VocalCut.kt (shared/src/jvmSharedMain/.../playback/karaoke) for the web: turns the lead
 * vocal down in interleaved PCM by the open-unmix mask, each bin keeping
 * `1 - (1 - level) * mask` of itself. Plain TypeScript with no imports so the karaoke
 * AudioWorklet can bundle it. Held to the same numbers as the Kotlin
 * (web/fixtures/karaoke-vectors.json); change both together.
 *
 * Periodic Hann of FFT samples every HOP, frame f centred on sample f * HOP: the model's
 * own front end, so a mask frame lines up with its audio. Output is the input LATENCY
 * samples later. Wherever the last four frames were left untouched (no mask, level 1) the
 * output is that delayed input itself, so 100% is the original audio exactly.
 */
export const FFT = 4096;
export const HOP = 1024;
export const BINS = FFT / 2 + 1;
export const LATENCY = FFT;

const OVERLAP = 1.5; // four Hann² a quarter-frame apart
const GLIDE = 0.5;
const SETTLED = 0.01;

const WINDOW = Float32Array.from({ length: FFT }, (_, i) => 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / FFT));
const COS = Float32Array.from({ length: FFT / 2 }, (_, i) => Math.cos((2 * Math.PI * i) / FFT));
const SIN = Float32Array.from({ length: FFT / 2 }, (_, i) => Math.sin((2 * Math.PI * i) / FFT));
const REVERSED = (() => {
  const table = new Int32Array(FFT);
  for (let i = 1, j = 0; i < FFT; i++) {
    let bit = FFT >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    table[i] = j;
  }
  return table;
})();

/** Fills one channel's BINS mask values for the frame centred on a track sample; false when there is none yet. */
export type MaskAt = (centreSample: number, channel: number, into: Float32Array) => boolean;

/** The model's vocal estimate as a share of the mix in one bin. */
export const maskOf = (estimate: number, mixture: number) =>
  mixture > 1e-9 ? Math.min(1, Math.max(0, estimate / mixture)) : 0;

export class VocalCut {
  /** The slider: 1 leaves the vocal alone, 0 takes all of it the mask finds. */
  level = 1;
  /** The level in use, gliding toward `level` a frame at a time. */
  applied = 1;

  private position = 0;
  private filled = 0;
  private cutFrames = 0;
  private readonly history: Float32Array[];
  private readonly sum: Float32Array[];
  private readonly ready: Float32Array[];
  private readonly re = new Float32Array(FFT);
  private readonly im = new Float32Array(FFT);
  private readonly mask = new Float32Array(BINS);

  constructor(private readonly channels = 2) {
    this.history = Array.from({ length: channels }, () => new Float32Array(FFT));
    this.sum = Array.from({ length: channels }, () => new Float32Array(FFT));
    this.ready = Array.from({ length: channels }, () => new Float32Array(HOP));
  }

  /** Forgets everything; the next sample in is track sample `startSample` (after a seek). */
  reset(startSample = 0) {
    this.position = startSample;
    this.filled = 0;
    this.cutFrames = 0;
    this.applied = this.level;
    for (let c = 0; c < this.channels; c++) {
      this.history[c]!.fill(0);
      this.sum[c]!.fill(0);
      this.ready[c]!.fill(0);
    }
  }

  /** `frames` interleaved frames from `input` into `output`. */
  process(input: Float32Array, frames: number, output: Float32Array, maskAt: MaskAt) {
    const { channels } = this;
    for (let i = 0; i < frames; i++) {
      for (let c = 0; c < channels; c++) {
        this.history[c]![FFT - HOP + this.filled] = input[i * channels + c]!;
        output[i * channels + c] = this.ready[c]![this.filled]!;
      }
      if (++this.filled === HOP) {
        this.filled = 0;
        this.position += HOP;
        this.frame(maskAt);
      }
    }
  }

  private frame(maskAt: MaskAt) {
    const { re, im, mask } = this;
    const step = this.level - this.applied;
    this.applied = Math.fround(Math.abs(step) < SETTLED ? this.level : this.applied + step * GLIDE);
    const centre = this.position - FFT / 2;
    let cut = false;
    for (let c = 0; c < this.channels; c++) {
      const x = this.history[c]!;
      const acc = this.sum[c]!;
      if (this.applied < 1 && maskAt(centre, c, mask)) {
        cut = true;
        for (let t = 0; t < FFT; t++) {
          re[t] = x[t]! * WINDOW[t]!;
          im[t] = 0;
        }
        fft(re, im, false);
        for (let k = 0; k < BINS; k++) {
          const keep = 1 - (1 - this.applied) * mask[k]!;
          re[k]! *= keep;
          im[k]! *= keep;
        }
        for (let k = 1; k < FFT / 2; k++) {
          re[FFT - k] = re[k]!;
          im[FFT - k] = -im[k]!;
        }
        fft(re, im, true);
        for (let t = 0; t < FFT; t++) acc[t]! += (re[t]! * WINDOW[t]!) / OVERLAP;
      } else {
        for (let t = 0; t < FFT; t++) acc[t]! += (x[t]! * WINDOW[t]! * WINDOW[t]!) / OVERLAP;
      }
    }
    if (cut) this.cutFrames = FFT / HOP;
    for (let c = 0; c < this.channels; c++) {
      // The block leaving now is covered by this frame and the three before it.
      this.ready[c]!.set((this.cutFrames > 0 ? this.sum[c]! : this.history[c]!).subarray(0, HOP));
      this.sum[c]!.copyWithin(0, HOP);
      this.sum[c]!.fill(0, FFT - HOP);
      this.history[c]!.copyWithin(0, HOP);
    }
    if (this.cutFrames > 0) this.cutFrames--;
  }
}

/** In-place radix-2 FFT of FFT points; the inverse is scaled by 1/N. */
function fft(re: Float32Array, im: Float32Array, inverse: boolean) {
  for (let i = 0; i < FFT; i++) {
    const j = REVERSED[i]!;
    if (i < j) {
      [re[i], re[j]] = [re[j]!, re[i]!];
      [im[i], im[j]] = [im[j]!, im[i]!];
    }
  }
  const sign = inverse ? 1 : -1;
  for (let length = 2; length <= FFT; length <<= 1) {
    const half = length / 2;
    const stride = FFT / length;
    for (let start = 0; start < FFT; start += length) {
      for (let k = 0; k < half; k++) {
        const wr = COS[k * stride]!;
        const wi = sign * SIN[k * stride]!;
        const a = start + k;
        const b = a + half;
        const tr = re[b]! * wr - im[b]! * wi;
        const ti = re[b]! * wi + im[b]! * wr;
        re[b] = re[a]! - tr;
        im[b] = im[a]! - ti;
        re[a]! += tr;
        im[a]! += ti;
      }
    }
  }
  if (inverse) {
    for (let i = 0; i < FFT; i++) {
      re[i]! /= FFT;
      im[i]! /= FFT;
    }
  }
}
