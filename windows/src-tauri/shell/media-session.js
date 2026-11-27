// Mirrors the web player's Media Session to the shell (src/media_session.rs), which owns
// Windows' media controls so they show as BitChord rather than "Unknown app", and runs
// the player's own action handlers when a media key or the flyout's buttons are used.
// Added at document start by main.rs; upstream's player code is untouched.
(() => {
  const session = navigator.mediaSession;
  if (!session) return;
  const proto = Object.getPrototypeOf(session);
  const handlers = new Map();
  // The last setPositionState, stamped with when it was given and whether the player was playing then.
  let position = null;

  const largest = (artwork) =>
    [...(artwork ?? [])].sort((a, b) => size(b) - size(a))[0]?.src ?? null;
  const size = (image) => Math.max(0, ...String(image.sizes ?? '').split(/\s+/).map((s) => parseInt(s, 10) || 0));
  const httpUrl = (src) => {
    try {
      const url = new URL(src, location.href);
      return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null; // Windows can't load blob:/data:
    } catch {
      return null;
    }
  };

  // The player only sets its position on a seek or a new duration or rate, so a pause or a
  // resume in between reads it from there: where the track is now, not where it was last set.
  const current = () => {
    if (!position) return null;
    const ran = position.playing ? ((performance.now() - position.at) / 1000) * position.playbackRate : 0;
    return { ...position, position: Math.min(position.duration, position.position + ran) };
  };
  const restamp = (playing) => {
    const now = current();
    position = now && { ...now, at: performance.now(), playing };
  };

  let queued = false;
  const report = () => {
    if (queued) return;
    queued = true;
    queueMicrotask(() => {
      queued = false;
      const meta = session.metadata;
      const artwork = largest(meta?.artwork);
      const now = current();
      window.__TAURI_INTERNALS__
        ?.invoke('media_session', {
          state: {
            title: meta?.title ?? '',
            artist: meta?.artist ?? '',
            album: meta?.album ?? '',
            artwork: artwork && httpUrl(artwork),
            playback: session.playbackState,
            actions: [...handlers.keys()],
            duration: now?.duration ?? null,
            position: now?.position ?? null,
            playbackRate: now?.playbackRate ?? null,
          },
        })
        .catch(() => {});
    });
  };

  for (const key of ['metadata', 'playbackState']) {
    const native = Object.getOwnPropertyDescriptor(proto, key);
    Object.defineProperty(session, key, {
      configurable: true,
      get() {
        return native.get.call(this);
      },
      set(value) {
        // A new track's position is unknown until the player sets it; the last one's would be wrong.
        // The player sets metadata twice per track (a hint, then the resolved stream), so by title.
        if (key === 'playbackState') restamp(value === 'playing');
        else if (value?.title !== session.metadata?.title) position = null;
        native.set.call(this, value);
        report();
      },
    });
  }

  const setActionHandler = proto.setActionHandler;
  session.setActionHandler = function (action, handler) {
    if (handler) handlers.set(action, handler);
    else handlers.delete(action);
    report();
    return setActionHandler.call(this, action, handler);
  };

  const setPositionState = proto.setPositionState;
  session.setPositionState = function (state) {
    const result = setPositionState.call(this, state);
    position = Number.isFinite(state?.duration) && state.duration > 0
      ? {
          duration: state.duration,
          position: Number.isFinite(state.position) ? state.position : 0,
          playbackRate: Number.isFinite(state.playbackRate) && state.playbackRate > 0 ? state.playbackRate : 1,
          at: performance.now(),
          playing: session.playbackState === 'playing',
        }
      : null;
    report();
    return result;
  };

  // media_session.rs and tray.rs call this for Windows' buttons: play, pause, nexttrack,
  // previoustrack, stop, and seekto (with seekTime) from a timeline.
  window.__bitchordMediaAction = (action, details) => handlers.get(action)?.({ ...details, action });
})();
