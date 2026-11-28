/**
 * Karaoke's cut in the audio graph: the front deck's element runs through VocalCut here,
 * between its source and the EQ. The page sends the masks for the playing track, the
 * slider's level, and anchors tying the element's time to the context's, from which each
 * block's track time is worked out; a seek is just an anchor that jumps.
 */
import { VocalCut } from './dsp';
import { MaskStore } from './masks';

// AudioWorkletGlobalScope, which the DOM typings don't describe.
declare const sampleRate: number;
declare const currentTime: number;
declare class AudioWorkletProcessor {
  readonly port: MessagePort;
}
declare function registerProcessor(name: string, processor: new () => AudioWorkletProcessor): void;

export type KaraokeMessage =
  | { type: 'level'; value: number }
  | { type: 'track'; id: string }
  | { type: 'window'; id: string; index: number; start: number; values: Uint8Array; frames: number }
  | { type: 'anchor'; trackSec: number; ctxSec: number };

class KaraokeProcessor extends AudioWorkletProcessor {
  private readonly cut = new VocalCut();
  private readonly store = new MaskStore();
  private track = '';
  private anchor = { trackSec: 0, ctxSec: 0 };
  private fed = 0;
  private input = new Float32Array(256);
  private output = new Float32Array(256);

  constructor() {
    super();
    this.port.onmessage = (e: MessageEvent<KaraokeMessage>) => {
      const m = e.data;
      if (m.type === 'level') this.cut.level = m.value;
      else if (m.type === 'anchor') this.anchor = m;
      else if (m.type === 'track') {
        this.track = m.id;
        this.store.clear();
      } else if (m.id === this.track) this.store.add(m.index, m);
    };
  }

  process(inputs: Float32Array[][], outputs: Float32Array[][]): boolean {
    const source = inputs[0] ?? [];
    const sink = outputs[0] ?? [];
    const frames = sink[0]?.length ?? 0;
    if (source.length < 2) {
      // Nothing playing, or a mono stream: no centre to find, so it passes as it is.
      sink.forEach((channel, c) => (source[c] ? channel.set(source[c]) : channel.fill(0)));
      return true;
    }
    if (this.input.length < frames * 2) {
      this.input = new Float32Array(frames * 2);
      this.output = new Float32Array(frames * 2);
    }
    const left = source[0]!;
    const right = source[1]!;
    for (let i = 0; i < frames; i++) {
      this.input[i * 2] = left[i]!;
      this.input[i * 2 + 1] = right[i]!;
    }
    const trackSec = this.anchor.trackSec + (currentTime - this.anchor.ctxSec);
    const offset = Math.round(trackSec * sampleRate) - this.fed;
    const store = this.store;
    this.cut.process(this.input, frames, this.output, (centre, channel, into) => {
      const sample = centre + offset;
      return sample >= 0 && store.maskAt(sample, sampleRate, channel, into);
    });
    this.fed += frames;
    if (this.fed % (sampleRate | 0) < frames) store.dropBefore(trackSec - 2);
    for (let i = 0; i < frames; i++) {
      sink[0]![i] = this.output[i * 2]!;
      sink[1]![i] = this.output[i * 2 + 1]!;
    }
    return true;
  }
}

registerProcessor('karaoke', KaraokeProcessor);
