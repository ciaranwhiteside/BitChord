/**
 * Karaoke while it is on: the cut in the front deck's graph, and its masks worked out a
 * window at a time just ahead of the playhead (KaraokeMasks.kt's job on the phone).
 *
 * The track's audio is fetched and decoded on its own, as Automix's analysis does, then
 * sliced into model windows for the worker. The page keeps the playing track's samples
 * and masks and, from its last 40 s, the next one's, so it starts already cut.
 */
import workletUrl from './karaoke.worklet.ts?worker&url';
import { fetchTrackAudio } from '@/playback/automix/fetchAudio';
import { karaokeRoute, usePlayer } from '@/state/player';
import { LATENCY } from './dsp';
import type { KaraokeMessage } from './karaoke.worklet';
import type { MaskRequest, MaskResponse } from './karaoke.worker';
import { MODEL_RATE, type MaskWindow, neededWindows, STEP_SEC, WINDOW_SAMPLES } from './masks';
import { useKaraoke } from './store';

const TICK_MS = 250;
const NEXT_FROM_END_SEC = 40;

interface TrackAudio {
  id: string;
  samples: Promise<{ left: Float32Array; right: Float32Array } | null>;
  windows: Map<number, MaskWindow>;
  asked: Set<number>;
  failed: boolean;
}

const loadedContexts = new WeakSet<AudioContext>();

/** Starts Karaoke on the front deck; returns what stops it. */
export function startKaraoke(): () => void {
  let stopped = false;
  let node: AudioWorkletNode | null = null;
  let deck: { setInsert(node: AudioNode | null): void } | null = null;
  let ctx: AudioContext | null = null;
  let deckGeneration = -1;
  const tracks = new Map<string, TrackAudio>();
  const aborts = new AbortController();
  const worker = new Worker(new URL('./karaoke.worker.ts', import.meta.url), { type: 'module' });
  let current = '';

  const post = (m: KaraokeMessage, transfer: Transferable[] = []) => node?.port.postMessage(m, transfer);
  const fail = () => useKaraoke.setState({ status: 'failed' });

  function load(id: string): TrackAudio {
    let track = tracks.get(id);
    if (track) return track;
    const created: TrackAudio = { id, windows: new Map(), asked: new Set(), failed: false, samples: Promise.resolve(null) };
    created.samples = fetchTrackAudio(id, aborts.signal)
      .then(({ bytes }) => new OfflineAudioContext(2, 1, MODEL_RATE).decodeAudioData(bytes))
      .then((buffer) => ({
        left: buffer.getChannelData(0),
        right: buffer.getChannelData(Math.min(1, buffer.numberOfChannels - 1)),
      }))
      .catch((error: unknown) => {
        if (!stopped) console.warn('[karaoke] could not read', id, error);
        created.failed = true;
        return null;
      });
    tracks.set(id, created);
    return created;
  }

  async function ask(track: TrackAudio, index: number) {
    if (track.asked.has(index)) return;
    track.asked.add(index);
    const samples = await track.samples;
    if (!samples || stopped) return;
    const from = Math.round(index * STEP_SEC * MODEL_RATE);
    if (from >= samples.left.length) return;
    const left = samples.left.slice(from, from + WINDOW_SAMPLES);
    const right = samples.right.slice(from, from + WINDOW_SAMPLES);
    worker.postMessage({ id: track.id, index, left, right } satisfies MaskRequest, [left.buffer, right.buffer]);
  }

  worker.onmessage = (e: MessageEvent<MaskResponse>) => {
    const m = e.data;
    const track = tracks.get(m.id);
    if (!track) return;
    if ('error' in m) {
      console.warn('[karaoke] mask failed', m.id, m.index, m.error);
      track.failed = true;
      return;
    }
    const window: MaskWindow = { start: m.index * STEP_SEC, values: m.values, frames: m.frames };
    track.windows.set(m.index, window);
    if (m.id === current) post({ type: 'window', id: m.id, index: m.index, ...window });
  };

  function anchor() {
    const audio = route?.audio;
    if (!audio || !ctx) return;
    post({ type: 'anchor', trackSec: audio.currentTime, ctxSec: ctx.currentTime });
  }

  let route: Awaited<ReturnType<typeof karaokeRoute>> = null;

  async function attach() {
    route = await karaokeRoute();
    if (stopped) return;
    if (!route) return fail();
    ctx = route.ctx;
    try {
      if (!loadedContexts.has(ctx)) {
        await ctx.audioWorklet.addModule(workletUrl);
        loadedContexts.add(ctx);
      }
    } catch (error) {
      console.warn('[karaoke] worklet failed to load', error);
      return fail();
    }
    if (stopped) return;
    node?.disconnect();
    deck?.setInsert(null);
    node = new AudioWorkletNode(ctx, 'karaoke', { outputChannelCount: [2] });
    deck = route.deck;
    deck.setInsert(node);
    deckGeneration = usePlayer.getState().deck;
    current = '';
    useKaraoke.setState({ latencySec: LATENCY / ctx.sampleRate });
    for (const event of ['play', 'seeked', 'ratechange'] as const) route.audio.addEventListener(event, anchor, { signal: aborts.signal });
  }

  function tick() {
    if (stopped || !node) return;
    // A crossfade or a mix moved the front deck (only possible if one was already running).
    if (usePlayer.getState().deck !== deckGeneration) {
      node = null;
      void attach();
      return;
    }
    const { nowPlaying, queue, index } = usePlayer.getState();
    const id = nowPlaying?.videoId ?? '';
    const { level, singable } = useKaraoke.getState();
    post({ type: 'level', value: id && id === singable ? level : 1 });
    anchor();
    if (!id) return;
    if (id !== current) {
      current = id;
      post({ type: 'track', id });
      for (const [k, w] of load(id).windows) post({ type: 'window', id, index: k, ...w });
      for (const held of tracks.keys()) if (held !== id && held !== queue[index + 1]?.track.videoId) tracks.delete(held);
    }
    const track = load(id);
    const sec = route?.audio.currentTime ?? 0;
    const [first, second] = neededWindows(sec);
    for (const k of track.windows.keys()) if (k < first) track.windows.delete(k);
    void ask(track, first).then(() => ask(track, second));

    const next = queue[index + 1]?.track.videoId;
    const duration = route?.audio.duration ?? NaN;
    if (next && next !== id && Number.isFinite(duration) && duration - sec < NEXT_FROM_END_SEC) {
      const upcoming = load(next);
      void ask(upcoming, 0).then(() => ask(upcoming, 1));
    }
    useKaraoke.setState({
      status: track.failed ? 'failed' : track.windows.has(first) ? 'singing' : 'preparing',
    });
  }

  useKaraoke.setState({ status: 'preparing' });
  void attach().then(() => tick());
  const timer = setInterval(tick, TICK_MS);

  return () => {
    stopped = true;
    clearInterval(timer);
    aborts.abort();
    worker.terminate();
    deck?.setInsert(null);
    node?.disconnect();
    useKaraoke.setState({ status: 'off', latencySec: 0 });
  };
}
