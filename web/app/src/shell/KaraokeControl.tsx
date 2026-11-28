import { Loader2, MicVocal } from 'lucide-react';
import { setKaraokeLevel, setKaraokeOn, useKaraoke } from '@/playback/karaoke/store';
import { usePlayer } from '@/state/player';
import { rangeLook } from './VolumeControl';

/**
 * Karaoke in the lyrics panel, after Apple Music's Sing (SPEC.md): a microphone that turns
 * it on, and while it is on a slider for how much of the original vocal stays. Offered only
 * for lyrics timed word by word.
 */
export function KaraokeControl({ singable }: { singable: boolean }) {
  const on = useKaraoke((s) => s.on);
  const status = useKaraoke((s) => s.status);
  const level = useKaraoke((s) => s.level);
  if (!singable) return null;
  return (
    <div className="flex items-center gap-1.5">
      {on && (
        <input
          className={`block h-4 w-20 cursor-pointer appearance-none bg-transparent ${rangeLook}`}
          type="range"
          min={0}
          max={1}
          step={0.01}
          value={level}
          aria-label="Vocal level"
          aria-valuetext={`${Math.round(level * 100)}%`}
          style={{ '--fill': `${level * 100}%` } as React.CSSProperties}
          onChange={(e) => setKaraokeLevel(Number(e.currentTarget.value))}
        />
      )}
      <button
        className="grid size-7 place-items-center rounded-[8px] text-secondary hover:bg-quinary aria-pressed:text-key"
        aria-pressed={on}
        aria-label={on ? (status === 'preparing' ? 'Getting the vocals ready' : 'Stop singing along') : 'Sing along: turn the vocals down'}
        onClick={() => setKaraokeOn(!on)}
      >
        {on && status === 'preparing' ? <Loader2 size={15} className="animate-spin" /> : <MicVocal size={16} />}
      </button>
    </div>
  );
}

/** Under the lyrics' header while Karaoke is on: why it can't cut, or that the mix is paused. */
export function KaraokeNote({ singable }: { singable: boolean }) {
  const on = useKaraoke((s) => s.on);
  const status = useKaraoke((s) => s.status);
  const mixing = usePlayer((s) => s.automix || s.crossfadeSec > 0);
  if (!singable || !on) return null;
  const note =
    status === 'failed' ? 'Karaoke couldn’t read this song.' : mixing ? 'Automix and crossfade are paused while you sing.' : null;
  return note ? (
    <p className="px-6 pb-1 type-[400_12px/16px] text-secondary" role="status">
      {note}
    </p>
  ) : null;
}
