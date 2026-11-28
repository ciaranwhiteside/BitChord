import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { HOP, LATENCY, type MaskAt, maskOf, VocalCut } from './dsp';

// The same cases as VocalCutTest.kt.
const RATE = 44100;
const signal = (frames: number) =>
  Float32Array.from({ length: frames * 2 }, (_, i) => {
    const t = Math.floor(i / 2);
    return 0.5 * Math.sin((2 * Math.PI * 220 * t) / RATE) + 0.3 * Math.sin((2 * Math.PI * 3000 * t) / RATE + (i % 2));
  });

function run(cut: VocalCut, input: Float32Array, maskAt: MaskAt, chunk = 4410): Float32Array {
  const out = new Float32Array(input.length);
  for (let at = 0; at < input.length / 2; at += chunk) {
    const n = Math.min(chunk, input.length / 2 - at);
    const block = new Float32Array(n * 2);
    cut.process(input.subarray(at * 2, (at + n) * 2), n, block, maskAt);
    out.set(block, at * 2);
  }
  return out;
}

const delayedError = (input: Float32Array, out: Float32Array) => {
  let worst = 0;
  for (let i = LATENCY * 2; i < out.length; i++) worst = Math.max(worst, Math.abs(out[i]! - input[i - LATENCY * 2]!));
  return worst;
};

const rms = (v: Float32Array, from: number) => {
  let s = 0;
  for (let i = from; i < v.length; i++) s += v[i]! * v[i]!;
  return Math.sqrt(s / (v.length - from));
};

const fill = (value: number): MaskAt => (_c, _ch, into) => (into.fill(value), true);
const lowBand = (low: number, high: number): MaskAt => (_c, _ch, into) => {
  for (let k = 0; k < into.length; k++) into[k] = k < 70 ? low : high;
  return true;
};

describe('VocalCut', () => {
  it('passes the input through exactly, a fixed latency later, with no mask', () => {
    const input = signal(LATENCY * 4);
    const cut = Object.assign(new VocalCut(), { level: 0 });
    expect(delayedError(input, run(cut, input, () => false))).toBe(0);
  });

  it('is exact at full level even with a mask', () => {
    const input = signal(LATENCY * 4);
    expect(delayedError(input, run(new VocalCut(), input, fill(1)))).toBe(0);
  });

  it('resynthesises an empty mask to under -80 dB, whatever the chunking', () => {
    const input = signal(LATENCY * 4);
    const cut = Object.assign(new VocalCut(), { level: 0 });
    expect(delayedError(input, run(cut, input, fill(0), 37))).toBeLessThan(1e-4);
  });

  it('is silence for a full mask at level zero once settled', () => {
    const input = signal(LATENCY * 6);
    const cut = Object.assign(new VocalCut(), { level: 0 });
    expect(rms(run(cut, input, fill(1)), LATENCY * 2 * 3)).toBeLessThan(1e-5);
  });

  it('glides the level and arrives within eight hops', () => {
    const input = signal(LATENCY * 6);
    const cut = new VocalCut();
    const block = new Float32Array(HOP * 2);
    const levels: number[] = [];
    for (let hop = 0; hop < input.length / 2 / HOP; hop++) {
      if (hop === 8) cut.level = 0;
      cut.process(input.subarray(hop * HOP * 2, (hop + 1) * HOP * 2), HOP, block, fill(1));
      levels.push(cut.applied);
    }
    const steps = levels.slice(1).map((l, i) => Math.abs(l - levels[i]!));
    expect(Math.max(...steps)).toBeLessThan(0.6);
    expect(levels[16]).toBe(0);
  });

  it('takes a low-band mask out and leaves the high tone', () => {
    const input = signal(LATENCY * 6);
    const out = run(Object.assign(new VocalCut(), { level: 0 }), input, lowBand(1, 0));
    const from = LATENCY * 2 * 3;
    const high = Float32Array.from({ length: out.length }, (_, i) =>
      0.3 * Math.sin((2 * Math.PI * 3000 * (Math.floor(i / 2) - LATENCY)) / RATE + (i % 2)));
    const residual = out.map((v, i) => v - high[i]!);
    expect(rms(residual, from)).toBeLessThan(0.01 * rms(high, from));
  });

  it('asks for each frame by the track sample it is centred on', () => {
    const centres: number[] = [];
    const cut = Object.assign(new VocalCut(), { level: 0 });
    cut.reset(44100);
    run(cut, signal(HOP * 3), (centre, channel) => (channel === 0 && centres.push(centre), false));
    expect(centres).toEqual([1, 2, 3].map((n) => 44100 + n * HOP - 2048));
  });

  it('clamps the mask to the estimate over the mixture', () => {
    expect([maskOf(1, 2), maskOf(3, 2), maskOf(1, 0)]).toEqual([0.5, 1, 0]);
  });

  it('matches the Kotlin on the shared vectors', () => {
    const vectors = JSON.parse(
      readFileSync(fileURLToPath(new URL('../../../../fixtures/karaoke-vectors.json', import.meta.url)), 'utf8'),
    ) as { level: number; frames: number; indices: number[]; values: number[] };
    const out = run(Object.assign(new VocalCut(), { level: vectors.level }), signal(vectors.frames), lowBand(0.8, 0.1));
    vectors.indices.forEach((i, n) => expect(out[i]).toBeCloseTo(vectors.values[n]!, 4));
  });
});
