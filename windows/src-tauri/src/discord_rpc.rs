//! Native Discord Rich Presence over Discord Desktop's local Windows Named Pipe
//! (`\\.\pipe\discord-ipc-0` .. `9`).
//!
//! Unlike the browser extension — which has no IPC access and must read a user
//! token from a discord.com tab — the Windows shell speaks Discord's official
//! local Rich Presence protocol directly: no login, no user token, no extra
//! crates, and zero CPU while idle or when Discord Desktop isn't running.
//!
//! Off until the user turns it on (the tray's "Show on Discord"), as the
//! extension's presence is off until "Connect Discord": what someone listens to
//! is theirs to share. The choice is kept in the app's data folder.

use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::PathBuf;
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};
use tauri::{AppHandle, Manager, Runtime};

use crate::media_session::MediaState;

/// BitChord's official Discord application ID (checked against web/extension/lib/discord.ts).
pub(crate) const APPLICATION_ID: &str = "1543679166967193620";
const PROJECT_URL: &str = "https://github.com/kushagrasinghx/BitChord";

const OP_HANDSHAKE: u32 = 0;
const OP_FRAME: u32 = 1;
const OP_CLOSE: u32 = 2;

/// Guard against corrupt frames over the pipe.
const MAX_FRAME_LEN: usize = 64 * 1024;
/// Between attempts to reach Discord Desktop while there's a card to show and it isn't running.
const RECONNECT_COOLDOWN: Duration = Duration::from_secs(15);
/// Position drift threshold (seconds) above which a seek triggers a fresh SET_ACTIVITY.
const SEEK_DRIFT_SECS: f64 = 3.0;

pub struct Presence {
    tx: Sender<Command>,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Snapshot {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub artwork: Option<String>,
    pub playing: bool,
    pub duration: Option<f64>,
    pub position: Option<f64>,
    pub playback_rate: f64,
    pub captured_at: Instant,
    pub captured_unix_secs: f64,
}

enum Command {
    Update(Snapshot),
    Enable(bool),
    Shutdown,
}

impl Snapshot {
    fn from_media(state: &MediaState) -> Self {
        let now_unix = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs_f64())
            .unwrap_or(0.0);
        let rate = state
            .playback_rate
            .filter(|r| r.is_finite() && *r > 0.0)
            .unwrap_or(1.0);
        Self {
            title: state.title.clone(),
            artist: state.artist.clone(),
            album: state.album.clone(),
            artwork: state.artwork.clone(),
            playing: state.playback == "playing",
            duration: state.duration.filter(|d| d.is_finite() && *d > 0.0),
            position: state.position.filter(|p| p.is_finite() && *p >= 0.0),
            playback_rate: rate,
            captured_at: Instant::now(),
            captured_unix_secs: now_unix,
        }
    }

    fn is_active(&self) -> bool {
        self.playing && !self.title.trim().is_empty()
    }
}

/// Where the user's choice is kept: "on", or anything else (or nothing) for off.
fn setting<R: Runtime>(app: &AppHandle<R>) -> Option<PathBuf> {
    Some(app.path().app_local_data_dir().ok()?.join("discord-presence"))
}

pub fn is_enabled<R: Runtime>(app: &AppHandle<R>) -> bool {
    setting(app).and_then(|f| fs::read_to_string(f).ok()).is_some_and(|v| v.trim() == "on")
}

pub fn init<R: Runtime>(app: &AppHandle<R>) -> Presence {
    let (tx, rx) = mpsc::channel();
    let enabled = is_enabled(app);
    let _ = thread::Builder::new()
        .name("bitchord-discord-ipc".into())
        .spawn(move || worker(rx, enabled, IpcConn::connect, RECONNECT_COOLDOWN));
    Presence { tx }
}

pub fn update(presence: &Presence, state: &MediaState) {
    let _ = presence.tx.send(Command::Update(Snapshot::from_media(state)));
}

/// Turns presence on or off (the card goes at once) and remembers it.
pub fn set_enabled<R: Runtime>(app: &AppHandle<R>, on: bool) {
    if let Some(file) = setting(app) {
        let _ = file.parent().map(fs::create_dir_all);
        let _ = fs::write(file, if on { "on" } else { "off" });
    }
    if let Some(presence) = app.try_state::<Presence>() {
        let _ = presence.tx.send(Command::Enable(on));
    }
}

pub fn shutdown<R: Runtime>(app: &AppHandle<R>) {
    if let Some(presence) = app.try_state::<Presence>() {
        let _ = presence.tx.send(Command::Shutdown);
    }
}

// ---- Worker loop ---------------------------------------------------------------

/// Brings Discord's card in line with what it should show: the playing track while
/// presence is on, else nothing. It sleeps until a command arrives, except while a
/// card is waiting for Discord Desktop, when it tries again every RECONNECT_COOLDOWN
/// (so starting Discord mid-song shows the song without waiting for the next one).
fn worker<P: Read + Write>(
    rx: Receiver<Command>,
    mut enabled: bool,
    mut connect: impl FnMut() -> io::Result<IpcConn<P>>,
    cooldown: Duration,
) {
    let mut latest: Option<Snapshot> = None;
    // What Discord is showing; a closed pipe clears it, as Discord drops a gone client's card.
    let mut shown: Option<Snapshot> = None;
    let mut conn: Option<IpcConn<P>> = None;
    let mut last_attempt: Option<Instant> = None;
    let mut seq: u64 = 0;

    loop {
        let wanted = latest.as_ref().filter(|s| enabled && s.is_active());
        let stale = match (&shown, wanted) {
            (None, None) => false,
            (Some(prev), Some(next)) => should_publish(Some(prev), next),
            _ => true,
        };
        if stale {
            if conn.is_none() && last_attempt.is_none_or(|t| t.elapsed() >= cooldown) {
                last_attempt = Some(Instant::now());
                conn = connect().ok();
            }
            if let Some(pipe) = conn.as_mut() {
                seq += 1;
                if pipe.send_activity(wanted.and_then(build_activity), seq).is_ok() {
                    shown = wanted.cloned();
                } else {
                    conn = None;
                    shown = None;
                }
            }
        }
        // Off: let go of Discord once the card is gone.
        if !enabled
            && shown.is_none()
            && let Some(pipe) = conn.take()
        {
            let _ = pipe.close();
        }

        let waiting = conn.is_none() && wanted.is_some();
        let first = if waiting {
            match rx.recv_timeout(cooldown) {
                Ok(cmd) => cmd,
                Err(RecvTimeoutError::Timeout) => continue,
                Err(RecvTimeoutError::Disconnected) => return,
            }
        } else {
            match rx.recv() {
                Ok(cmd) => cmd,
                Err(_) => return,
            }
        };
        // Coalesce bursts (metadata, position and playback arrive back to back).
        for cmd in std::iter::once(first).chain(rx.try_iter()) {
            match cmd {
                Command::Update(snapshot) => latest = Some(snapshot),
                Command::Enable(on) => enabled = on,
                Command::Shutdown => {
                    if let Some(mut pipe) = conn.take() {
                        if shown.is_some() {
                            seq += 1;
                            let _ = pipe.send_activity(None, seq);
                        }
                        let _ = pipe.close();
                    }
                    return;
                }
            }
        }
    }
}

/// Returns true when [next] differs meaningfully from [prev] (track change, play/pause,
/// rate change, or a user seek beyond SEEK_DRIFT_SECS).
pub(crate) fn should_publish(prev: Option<&Snapshot>, next: &Snapshot) -> bool {
    let Some(prev) = prev else { return true };
    if prev.is_active() != next.is_active() {
        return true;
    }
    if !next.is_active() {
        return false;
    }
    if prev.title != next.title
        || prev.artist != next.artist
        || prev.album != next.album
        || prev.artwork != next.artwork
        || (prev.playback_rate - next.playback_rate).abs() > 0.01
    {
        return true;
    }
    match (prev.position, next.position, prev.duration, next.duration) {
        (Some(p0), Some(p1), Some(d0), Some(d1)) => {
            if (d0 - d1).abs() > 1.0 {
                return true;
            }
            let elapsed = next
                .captured_at
                .saturating_duration_since(prev.captured_at)
                .as_secs_f64();
            let expected = p0 + elapsed * prev.playback_rate;
            (p1 - expected).abs() > SEEK_DRIFT_SECS
        }
        (None, Some(_), _, Some(_)) => true,
        _ => false,
    }
}

// ---- Activity payload ----------------------------------------------------------

/// Clamps a Discord Rich Presence text field to Discord's 2..=128 char requirement.
pub(crate) fn clamp_field(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let count = trimmed.chars().count();
    if count == 1 {
        return Some(format!("{trimmed} "));
    }
    if count <= 128 {
        return Some(trimmed.to_string());
    }
    let mut out: String = trimmed.chars().take(127).collect();
    out.push('…');
    Some(out)
}

/// Builds the Discord SET_ACTIVITY `activity` object, or `None` to clear the card.
pub(crate) fn build_activity(snapshot: &Snapshot) -> Option<Value> {
    if !snapshot.is_active() {
        return None;
    }
    let title = if (snapshot.playback_rate - 1.0).abs() > 0.01 {
        format!("{} [{:.2}x]", snapshot.title.trim(), snapshot.playback_rate)
    } else {
        snapshot.title.trim().to_string()
    };
    let details = clamp_field(&title)?;

    let mut activity = serde_json::Map::new();
    // 2 = Listening ("Listening to BitChord").
    activity.insert("type".into(), json!(2));
    activity.insert("details".into(), json!(details));

    if let Some(artist) = clamp_field(&snapshot.artist) {
        activity.insert("state".into(), json!(artist));
    }

    if let (Some(duration), Some(position)) = (snapshot.duration, snapshot.position) {
        let rate = snapshot.playback_rate.max(0.01);
        let pos = position.clamp(0.0, duration);
        let start = (snapshot.captured_unix_secs - pos / rate).max(0.0).round() as u64;
        let end = (snapshot.captured_unix_secs + (duration - pos) / rate)
            .max(start as f64)
            .round() as u64;
        activity.insert("timestamps".into(), json!({ "start": start, "end": end }));
    }

    let mut assets = serde_json::Map::new();
    if let Some(url) = snapshot
        .artwork
        .as_deref()
        .filter(|u| u.starts_with("https://") || u.starts_with("http://"))
    {
        assets.insert("large_image".into(), json!(url));
    }
    if let Some(album) = clamp_field(&snapshot.album) {
        assets.insert("large_text".into(), json!(album));
    }
    if !assets.is_empty() {
        activity.insert("assets".into(), Value::Object(assets));
    }

    activity.insert(
        "buttons".into(),
        json!([{ "label": "Visit BitChord", "url": PROJECT_URL }]),
    );

    Some(Value::Object(activity))
}

// ---- Windows Named Pipe IPC framing --------------------------------------------

pub(crate) fn encode_frame(opcode: u32, payload: &Value) -> io::Result<Vec<u8>> {
    let body = serde_json::to_vec(payload)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    let len = u32::try_from(body.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "frame too large"))?;
    let mut buf = Vec::with_capacity(8 + body.len());
    buf.extend_from_slice(&opcode.to_le_bytes());
    buf.extend_from_slice(&len.to_le_bytes());
    buf.extend_from_slice(&body);
    Ok(buf)
}

pub(crate) fn read_frame<R: Read>(reader: &mut R) -> io::Result<(u32, Value)> {
    let mut header = [0u8; 8];
    reader.read_exact(&mut header)?;
    let opcode = u32::from_le_bytes([header[0], header[1], header[2], header[3]]);
    let len = u32::from_le_bytes([header[4], header[5], header[6], header[7]]) as usize;
    if len > MAX_FRAME_LEN {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Discord IPC frame exceeded limit",
        ));
    }
    let mut body = vec![0u8; len];
    reader.read_exact(&mut body)?;
    let payload = serde_json::from_slice(&body)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    Ok((opcode, payload))
}

fn pipe_path(slot: u8) -> String {
    format!(r"\\.\pipe\discord-ipc-{slot}")
}

struct IpcConn<P: Read + Write = File> {
    pipe: P,
}

impl IpcConn {
    /// The first of Discord Desktop's pipes that answers (a second Discord, PTB or
    /// Canary, takes the next number).
    fn connect() -> io::Result<Self> {
        let mut last_err = io::Error::new(io::ErrorKind::NotFound, "no Discord IPC pipe found");
        for slot in 0..10 {
            match OpenOptions::new().read(true).write(true).open(pipe_path(slot)) {
                Ok(pipe) => return IpcConn::handshake(pipe),
                Err(err) => last_err = err,
            }
        }
        Err(last_err)
    }
}

impl<P: Read + Write> IpcConn<P> {
    fn handshake(mut pipe: P) -> io::Result<Self> {
        pipe.write_all(&encode_frame(OP_HANDSHAKE, &json!({ "v": 1, "client_id": APPLICATION_ID }))?)?;
        pipe.flush()?;
        let (op, _) = read_frame(&mut pipe)?;
        if op == OP_CLOSE {
            return Err(io::Error::new(io::ErrorKind::ConnectionRefused, "Discord rejected handshake"));
        }
        Ok(Self { pipe })
    }

    fn send_activity(&mut self, activity: Option<Value>, seq: u64) -> io::Result<()> {
        let payload = json!({
            "cmd": "SET_ACTIVITY",
            "args": {
                "pid": std::process::id(),
                "activity": activity,
            },
            "nonce": format!("bitchord-{seq}"),
        });
        let frame = encode_frame(OP_FRAME, &payload)?;
        self.pipe.write_all(&frame)?;
        self.pipe.flush()?;
        let (op, _) = read_frame(&mut self.pipe)?;
        if op == OP_CLOSE {
            return Err(io::Error::new(
                io::ErrorKind::BrokenPipe,
                "Discord closed IPC",
            ));
        }
        Ok(())
    }

    fn close(mut self) -> io::Result<()> {
        let frame = encode_frame(OP_CLOSE, &json!({}))?;
        self.pipe.write_all(&frame)?;
        self.pipe.flush()
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;
    use std::time::{Duration, Instant};

    use serde_json::json;

    use std::collections::VecDeque;
    use std::io::{self, Read, Write};
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::mpsc;
    use std::sync::{Arc, Mutex};
    use std::thread;

    use serde_json::Value;

    use super::{
        Command, IpcConn, OP_CLOSE, OP_FRAME, OP_HANDSHAKE, Snapshot, build_activity, clamp_field, encode_frame,
        read_frame, should_publish, worker,
    };

    /// Discord's end of the pipe: records every frame and answers each one, as Discord does.
    struct FakeDiscord {
        received: Arc<Mutex<Vec<(u32, Value)>>>,
        written: Vec<u8>,
        replies: VecDeque<u8>,
    }

    impl Write for FakeDiscord {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            self.written.extend_from_slice(buf);
            while self.written.len() >= 8 {
                let len = u32::from_le_bytes(self.written[4..8].try_into().unwrap()) as usize;
                if self.written.len() < 8 + len {
                    break;
                }
                let frame: Vec<u8> = self.written.drain(..8 + len).collect();
                let (op, body) = read_frame(&mut Cursor::new(frame)).unwrap();
                self.received.lock().unwrap().push((op, body));
                if op != OP_CLOSE {
                    self.replies.extend(encode_frame(OP_FRAME, &json!({ "evt": null })).unwrap());
                }
            }
            Ok(buf.len())
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    impl Read for FakeDiscord {
        fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
            let n = buf.len().min(self.replies.len());
            for (slot, byte) in buf.iter_mut().zip(self.replies.drain(..n)) {
                *slot = byte;
            }
            Ok(n)
        }
    }

    struct Harness {
        tx: mpsc::Sender<Command>,
        received: Arc<Mutex<Vec<(u32, Value)>>>,
        attempts: Arc<AtomicUsize>,
        worker: thread::JoinHandle<()>,
    }

    /// A worker against a fake Discord that isn't running for the first [absent] attempts.
    fn harness(enabled: bool, absent: usize) -> Harness {
        let (tx, rx) = mpsc::channel();
        let received = Arc::new(Mutex::new(Vec::new()));
        let attempts = Arc::new(AtomicUsize::new(0));
        let (log, count) = (received.clone(), attempts.clone());
        let connect = move || {
            if count.fetch_add(1, Ordering::SeqCst) < absent {
                return Err(io::Error::from(io::ErrorKind::NotFound));
            }
            IpcConn::handshake(FakeDiscord { received: log.clone(), written: Vec::new(), replies: VecDeque::new() })
        };
        let worker = thread::spawn(move || worker(rx, enabled, connect, Duration::from_millis(50)));
        Harness { tx, received, attempts, worker }
    }

    impl Harness {
        /// The SET_ACTIVITY activities sent so far (null for a cleared card), and whether the pipe was closed.
        fn activities(&self) -> (Vec<Value>, bool) {
            Harness::summarise(&self.received.lock().unwrap())
        }

        fn summarise(frames: &[(u32, Value)]) -> (Vec<Value>, bool) {
            let sets = frames.iter().filter(|(op, _)| *op == OP_FRAME).map(|(_, b)| b["args"]["activity"].clone());
            (sets.collect(), frames.iter().any(|(op, _)| *op == OP_CLOSE))
        }

        fn wait_for(&self, what: &str, done: impl Fn(&(Vec<Value>, bool)) -> bool) {
            let deadline = Instant::now() + Duration::from_secs(5);
            while !done(&self.activities()) {
                assert!(Instant::now() < deadline, "timed out waiting for {what}: {:?}", self.activities());
                thread::sleep(Duration::from_millis(5));
            }
        }

        fn stop(self) -> (Vec<Value>, bool) {
            self.tx.send(Command::Shutdown).unwrap();
            let seen = self.received.clone();
            self.worker.join().unwrap();
            Harness::summarise(&seen.lock().unwrap())
        }
    }

    #[test]
    fn off_by_default_never_reaches_discord() {
        let h = harness(false, 0);
        h.tx.send(Command::Update(sample_snapshot())).unwrap();
        thread::sleep(Duration::from_millis(200));
        assert_eq!(h.attempts.load(Ordering::SeqCst), 0, "presence is off, so Discord isn't even opened");
        assert_eq!(h.stop(), (vec![], false));
    }

    #[test]
    fn turning_it_on_shows_the_card_and_off_clears_it_and_lets_go() {
        let h = harness(false, 0);
        h.tx.send(Command::Update(sample_snapshot())).unwrap();
        h.tx.send(Command::Enable(true)).unwrap();
        h.wait_for("the card", |(sets, _)| sets.len() == 1);
        assert_eq!(h.received.lock().unwrap()[0].0, OP_HANDSHAKE);
        assert_eq!(h.activities().0[0]["details"], "Midnight City");

        h.tx.send(Command::Enable(false)).unwrap();
        h.wait_for("the card cleared and the pipe closed", |(sets, closed)| sets.len() == 2 && *closed);
        assert!(h.activities().0[1].is_null());
        h.stop();
    }

    #[test]
    fn a_card_waiting_for_discord_shows_once_it_starts_with_nothing_new_played() {
        // Discord isn't running for the first two attempts; no further updates are sent.
        let h = harness(true, 2);
        h.tx.send(Command::Update(sample_snapshot())).unwrap();
        h.wait_for("the card", |(sets, _)| sets.len() == 1);
        assert_eq!(h.attempts.load(Ordering::SeqCst), 3);
        h.stop();
    }

    #[test]
    fn a_pause_clears_the_card_and_shutting_down_with_one_showing_clears_it() {
        let h = harness(true, 0);
        h.tx.send(Command::Update(sample_snapshot())).unwrap();
        h.wait_for("the card", |(sets, _)| sets.len() == 1);
        let mut paused = sample_snapshot();
        paused.playing = false;
        h.tx.send(Command::Update(paused)).unwrap();
        h.wait_for("the card cleared", |(sets, _)| sets.len() == 2);
        h.tx.send(Command::Update(sample_snapshot())).unwrap();
        h.wait_for("the card again", |(sets, _)| sets.len() == 3);

        let (sets, closed) = h.stop();
        assert!(sets[1].is_null() && sets[3].is_null(), "{sets:?}");
        assert!(closed);
    }

    /// Against a real pipe server (Discord Desktop, or a stand-in serving `discord-ipc-0`),
    /// so the framing is checked over Windows' named pipes rather than in memory.
    #[test]
    #[ignore = "needs Discord Desktop or a fake Discord pipe server"]
    fn real_pipe_handshake_and_activity() {
        let mut conn = IpcConn::connect().expect("no Discord pipe answered");
        conn.send_activity(build_activity(&sample_snapshot()), 1).expect("SET_ACTIVITY");
        conn.send_activity(None, 2).expect("clearing SET_ACTIVITY");
        conn.close().expect("close");
    }

    fn sample_snapshot() -> Snapshot {
        Snapshot {
            title: "Midnight City".into(),
            artist: "M83".into(),
            album: "Hurry Up, We're Dreaming".into(),
            artwork: Some("https://lh3.googleusercontent.com/cover.jpg".into()),
            playing: true,
            duration: Some(243.0),
            position: Some(30.0),
            playback_rate: 1.0,
            captured_at: Instant::now(),
            captured_unix_secs: 1_700_000_000.0,
        }
    }

    #[test]
    fn frame_round_trips_little_endian_header_and_json() {
        let payload = json!({ "cmd": "SET_ACTIVITY", "nonce": "1" });
        let bytes = encode_frame(OP_FRAME, &payload).unwrap();
        assert_eq!(&bytes[0..4], &OP_FRAME.to_le_bytes());
        let expected_len = (bytes.len() - 8) as u32;
        assert_eq!(&bytes[4..8], &expected_len.to_le_bytes());

        let (op, decoded) = read_frame(&mut Cursor::new(bytes)).unwrap();
        assert_eq!(op, OP_FRAME);
        assert_eq!(decoded, payload);
    }

    #[test]
    fn playing_track_builds_listening_activity_with_timestamps() {
        let snap = sample_snapshot();
        let activity = build_activity(&snap).unwrap();
        assert_eq!(activity["type"], 2);
        assert_eq!(activity["details"], "Midnight City");
        assert_eq!(activity["state"], "M83");
        assert_eq!(activity["timestamps"]["start"], 1_699_999_970);
        assert_eq!(activity["timestamps"]["end"], 1_700_000_213);
        assert_eq!(
            activity["assets"]["large_image"],
            "https://lh3.googleusercontent.com/cover.jpg"
        );
        assert_eq!(activity["assets"]["large_text"], "Hurry Up, We're Dreaming");
        assert_eq!(activity["buttons"][0]["label"], "Visit BitChord");
    }

    #[test]
    fn playback_rate_scales_timestamps_and_tags_title() {
        let mut snap = sample_snapshot();
        snap.playback_rate = 1.5;
        snap.position = Some(30.0);
        snap.duration = Some(120.0);
        let activity = build_activity(&snap).unwrap();
        assert_eq!(activity["details"], "Midnight City [1.50x]");
        // 30 / 1.5 = 20s elapsed, 90 / 1.5 = 60s remaining.
        assert_eq!(activity["timestamps"]["start"], 1_699_999_980);
        assert_eq!(activity["timestamps"]["end"], 1_700_000_060);
    }

    #[test]
    fn paused_or_blank_clears_activity() {
        let mut paused = sample_snapshot();
        paused.playing = false;
        assert!(build_activity(&paused).is_none());

        let mut blank = sample_snapshot();
        blank.title = "   ".into();
        assert!(build_activity(&blank).is_none());
    }

    #[test]
    fn clamp_field_respects_discord_bounds_and_utf8() {
        assert_eq!(clamp_field("   "), None);
        assert_eq!(clamp_field("X"), Some("X ".into()));
        let long = "♪".repeat(150);
        let clamped = clamp_field(&long).unwrap();
        assert_eq!(clamped.chars().count(), 128);
        assert!(clamped.ends_with('…'));
    }

    #[test]
    fn small_playback_tick_is_deduped_while_seek_triggers_update() {
        let base = Instant::now();
        let mut prev = sample_snapshot();
        prev.captured_at = base;
        prev.position = Some(10.0);

        let mut normal_tick = prev.clone();
        normal_tick.captured_at = base + Duration::from_secs(5);
        normal_tick.position = Some(15.4);
        assert!(!should_publish(Some(&prev), &normal_tick));

        let mut seeked = prev.clone();
        seeked.captured_at = base + Duration::from_secs(5);
        seeked.position = Some(90.0);
        assert!(should_publish(Some(&prev), &seeked));
    }
}
