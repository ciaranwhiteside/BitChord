/** Karaoke's vocal masks, off the page's thread: one model window in, its 8-bit mask out. */
import { vocalMask } from '@/playback/automix/analysis/models';

export interface MaskRequest {
  id: string;
  index: number;
  left: Float32Array;
  right: Float32Array;
}

export type MaskResponse =
  | { id: string; index: number; values: Uint8Array; frames: number }
  | { id: string; index: number; error: string };

self.onmessage = async (e: MessageEvent<MaskRequest>) => {
  const { id, index, left, right } = e.data;
  try {
    const mask = await vocalMask(left, right);
    if (!mask) {
      postMessage({ id, index, error: 'the vocal model could not run' } satisfies MaskResponse);
      return;
    }
    postMessage({ id, index, values: mask.values, frames: mask.frames } satisfies MaskResponse, {
      transfer: [mask.values.buffer as ArrayBuffer],
    });
  } catch (error) {
    postMessage({ id, index, error: String(error) } satisfies MaskResponse);
  }
};
