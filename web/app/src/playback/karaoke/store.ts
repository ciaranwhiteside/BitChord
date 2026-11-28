/**
 * Karaoke's state (SPEC.md): on for this visit only, the vocal level kept across visits,
 * what it is doing, and which track the lyrics found word-timed lyrics for. No imports
 * beyond zustand, so the player can read it without loading the rest of Karaoke.
 */
import { create } from 'zustand';

export type KaraokeStatus = 'off' | 'preparing' | 'singing' | 'failed';

interface KaraokeState {
  on: boolean;
  /** 1 the original vocal, 0 as much of it out as the mask finds. */
  level: number;
  status: KaraokeStatus;
  /** The track whose lyrics are timed word by word; only it is cut, any other plays as it is. */
  singable: string | null;
  /** How far the cut holds the sound back, for the playhead; 0 while it isn't in the graph. */
  latencySec: number;
}

const LEVEL_KEY = 'bitchord:karaoke-level';

function storedLevel(): number {
  const level = Number(localStorage.getItem(LEVEL_KEY));
  return localStorage.getItem(LEVEL_KEY) !== null && Number.isFinite(level) ? Math.min(1, Math.max(0, level)) : 0.2;
}

export const useKaraoke = create<KaraokeState>()(() => ({
  on: false,
  level: storedLevel(),
  status: 'off',
  singable: null,
  latencySec: 0,
}));

export const setKaraokeOn = (on: boolean) => useKaraoke.setState({ on });

export function setKaraokeLevel(level: number) {
  const clamped = Math.min(1, Math.max(0, level));
  useKaraoke.setState({ level: clamped });
  localStorage.setItem(LEVEL_KEY, String(clamped));
}

export const setKaraokeSingable = (videoId: string | null) => {
  if (useKaraoke.getState().singable !== videoId) useKaraoke.setState({ singable: videoId });
};
