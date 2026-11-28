/**
 * Dev only: Karaoke's perf gate on the web (SPEC.md, Checkpoint A), the twin of the
 * phone's KaraokeInferenceBenchmark. `await __karaokeBench('<videoId>')` in the console
 * times the mask on that song's first window and renders 45 s of the cut across two
 * window seams (~21 s, ~41 s); open the returned URLs to listen.
 *
 * Runs on the main thread: single-threaded WASM times the same as in the worker.
 */
import { VOCAL_FRAMES, VOCAL_SR, vocalMask } from '@/playback/automix/analysis/models';
import { fetchTrackAudio } from '@/playback/automix/fetchAudio';
import { HOP, LATENCY, VocalCut } from './dsp';

const WINDOW = (VOCAL_FRAMES - 1) * HOP;
const STEP = Math.floor((20 * VOCAL_SR) / HOP) * HOP;

type Mask = { values: Uint8Array; frames: number };

export async function karaokeBench(videoId: string) {
  const { bytes } = await fetchTrackAudio(videoId);
  const decoded = await new OfflineAudioContext(2, 1, VOCAL_SR).decodeAudioData(bytes);
  const length = Math.min(decoded.length, 45 * VOCAL_SR);
  const left = decoded.getChannelData(0).slice(0, length);
  const right = decoded.getChannelData(Math.min(1, decoded.numberOfChannels - 1)).slice(0, length);

  await vocalMask(left.slice(0, WINDOW), right.slice(0, WINDOW)); // warm-up: session open
  const t0 = performance.now();
  if (!(await vocalMask(left.slice(0, WINDOW), right.slice(0, WINDOW)))) throw new Error('inference failed');
  const ms = performance.now() - t0;
  const realTime = WINDOW / VOCAL_SR / (ms / 1000);

  const masks: Mask[] = [];
  for (let start = 0; start < length; start += STEP) {
    masks.push((await vocalMask(left.slice(start, start + WINDOW), right.slice(start, start + WINDOW)))!);
  }
  const urls = [0.2, 0].map((level) => wav(render(left, right, masks, level)));
  const result = { msPerWindow: Math.round(ms), realTime: +realTime.toFixed(2), passes: realTime >= 1.5, urls };
  console.info('[karaoke] GATE', result);
  return result;
}

/** As the phone's: each frame reads its own window, moving on a second into the overlap. */
function render(left: Float32Array, right: Float32Array, masks: Mask[], level: number): Float32Array {
  const cut = new VocalCut();
  cut.level = level;
  cut.reset();
  const frames = left.length + LATENCY;
  const input = new Float32Array(frames * 2);
  for (let i = 0; i < left.length; i++) {
    input[i * 2] = left[i]!;
    input[i * 2 + 1] = right[i]!;
  }
  const output = new Float32Array(frames * 2);
  const bins = 2049;
  cut.process(input, frames, output, (centre, channel, into) => {
    if (centre < 0) return false;
    let k = Math.min(Math.floor(centre / STEP), masks.length - 1);
    if (k > 0 && centre - k * STEP < VOCAL_SR) k--;
    const mask = masks[k]!;
    const frame = Math.floor((centre - k * STEP) / HOP);
    if (frame >= mask.frames) return false;
    for (let b = 0; b < bins; b++) into[b] = mask.values[(channel * bins + b) * mask.frames + frame]! / 255;
    return true;
  });
  return output.subarray(LATENCY * 2);
}

function wav(interleaved: Float32Array): string {
  const view = new DataView(new ArrayBuffer(44 + interleaved.length * 2));
  const text = (at: number, s: string) => [...s].forEach((ch, i) => view.setUint8(at + i, ch.charCodeAt(0)));
  text(0, 'RIFF');
  view.setUint32(4, 36 + interleaved.length * 2, true);
  text(8, 'WAVEfmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 2, true);
  view.setUint32(24, VOCAL_SR, true);
  view.setUint32(28, VOCAL_SR * 4, true);
  view.setUint16(32, 4, true);
  view.setUint16(34, 16, true);
  text(36, 'data');
  view.setUint32(40, interleaved.length * 2, true);
  interleaved.forEach((s, i) => view.setInt16(44 + i * 2, Math.max(-1, Math.min(1, s)) * 32767, true));
  return URL.createObjectURL(new Blob([view], { type: 'audio/wav' }));
}
