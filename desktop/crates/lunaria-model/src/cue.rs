//! Pure-Rust `.cue` sheet parser (75 fps CD frames) and deterministic virtual clipped tracks.

use crate::{Track, TrackId};

const FRAMES_PER_SECOND: u64 = 75;

/// Single audio track entry decoded from a `.cue` sheet.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CueTrackEntry {
    /// 1-based track number.
    pub track_number: u32,
    /// Track title.
    pub title: String,
    /// Performing artist for this track.
    pub performer: String,
    /// `INDEX 01` start offset in milliseconds.
    pub start_ms: u64,
    /// Optional end offset in milliseconds (chained from next track in same file).
    pub end_ms: Option<u64>,
    /// Referenced backing audio filename.
    pub file_name: String,
}

/// Parsed `.cue` sheet album metadata and track entries.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedCueSheet {
    /// Album title declared at sheet header.
    pub album_title: String,
    /// Album performer declared at sheet header.
    pub album_performer: String,
    /// Primary referenced backing audio filename.
    pub file_name: String,
    /// Ordered list of valid tracks with timestamps.
    pub tracks: Vec<CueTrackEntry>,
}

fn extract_cue_value(rest: &str) -> String {
    let trimmed = rest.trim();
    if let Some(first_quote) = trimmed.find('"')
        && let Some(rel_end) = trimmed[first_quote + 1..].find('"')
    {
        return trimmed[first_quote + 1..first_quote + 1 + rel_end]
            .trim()
            .to_owned();
    }
    trimmed
        .rsplit_once(' ')
        .map_or(trimmed, |(before, _)| before)
        .trim()
        .to_owned()
}

fn starts_with_ci<'a>(haystack: &'a str, prefix: &str) -> Option<&'a str> {
    if haystack.len() >= prefix.len()
        && haystack
            .get(..prefix.len())
            .is_some_and(|head| head.eq_ignore_ascii_case(prefix))
    {
        Some(&haystack[prefix.len()..])
    } else {
        None
    }
}

fn parse_track_number(line: &str) -> Option<u32> {
    let mut tokens = line.split_whitespace();
    let kw = tokens.next()?;
    let num_str = tokens.next()?;
    let kind = tokens.next()?;
    if kw.eq_ignore_ascii_case("TRACK") && kind.eq_ignore_ascii_case("AUDIO") {
        num_str.parse::<u32>().ok()
    } else {
        None
    }
}

fn parse_index_01_ms(line: &str) -> Option<u64> {
    let mut tokens = line.split_whitespace();
    let kw = tokens.next()?;
    let idx = tokens.next()?;
    let time_str = tokens.next()?;
    if !kw.eq_ignore_ascii_case("INDEX") || idx != "01" {
        return None;
    }
    let mut parts = time_str.split(':');
    let mm = parts.next()?.parse::<u64>().ok()?;
    let ss = parts.next()?.parse::<u64>().ok()?;
    let ff = parts.next()?.parse::<u64>().ok()?;
    if parts.next().is_some() {
        return None;
    }
    Some(
        (mm.saturating_mul(60).saturating_add(ss)).saturating_mul(1_000)
            + (ff * 1_000) / FRAMES_PER_SECOND,
    )
}

/// Parses `.cue` sheet contents into structured album and track entries.
#[must_use]
pub fn parse_cue_sheet(raw_text: &str) -> Option<ParsedCueSheet> {
    let cleaned = raw_text.strip_prefix('\u{FEFF}').unwrap_or(raw_text);
    if cleaned.trim().is_empty() {
        return None;
    }

    #[derive(Debug)]
    struct MutableCueTrack {
        track_number: u32,
        title: String,
        performer: String,
        start_ms: Option<u64>,
        file_name: String,
    }

    let mut album_title = String::new();
    let mut album_performer = String::new();
    let mut active_file_name = String::new();
    let mut first_file_name = String::new();
    let mut raw_tracks: Vec<MutableCueTrack> = Vec::new();
    let mut current_track: Option<MutableCueTrack> = None;

    for raw_line in cleaned.lines() {
        let line = raw_line.trim();
        if line.is_empty() {
            continue;
        }

        if let Some(rest) = starts_with_ci(line, "FILE ") {
            let file = extract_cue_value(rest);
            active_file_name.clone_from(&file);
            if first_file_name.is_empty() {
                first_file_name = file;
            }
        } else if let Some(num) = parse_track_number(line) {
            if let Some(prev) = current_track.take() {
                raw_tracks.push(prev);
            }
            current_track = Some(MutableCueTrack {
                track_number: num,
                title: String::new(),
                performer: String::new(),
                start_ms: None,
                file_name: active_file_name.clone(),
            });
        } else if let Some(rest) = starts_with_ci(line, "TITLE ") {
            let title = extract_cue_value(rest);
            if let Some(active) = current_track.as_mut() {
                active.title = title;
            } else {
                album_title = title;
            }
        } else if let Some(rest) = starts_with_ci(line, "PERFORMER ") {
            let performer = extract_cue_value(rest);
            if let Some(active) = current_track.as_mut() {
                active.performer = performer;
            } else {
                album_performer = performer;
            }
        } else if let Some(ms) = parse_index_01_ms(line)
            && let Some(active) = current_track.as_mut()
            && active.start_ms.is_none()
        {
            active.start_ms = Some(ms);
        }
    }

    if let Some(last) = current_track.take() {
        raw_tracks.push(last);
    }

    let valid_tracks: Vec<MutableCueTrack> = raw_tracks
        .into_iter()
        .filter(|t| t.start_ms.is_some())
        .collect();
    if valid_tracks.is_empty() {
        return None;
    }

    let fallback_album_performer = if album_performer.trim().is_empty() {
        "Unknown Artist".to_owned()
    } else {
        album_performer.clone()
    };

    let mut entries = Vec::with_capacity(valid_tracks.len());
    for (idx, track) in valid_tracks.iter().enumerate() {
        let start_ms = track.start_ms.unwrap_or(0);
        let end_ms = valid_tracks
            .get(idx + 1)
            .filter(|next| next.file_name == track.file_name)
            .and_then(|next| next.start_ms);

        let title = if track.title.trim().is_empty() {
            format!("Track {}", track.track_number)
        } else {
            track.title.clone()
        };
        let performer = if track.performer.trim().is_empty() {
            fallback_album_performer.clone()
        } else {
            track.performer.clone()
        };

        entries.push(CueTrackEntry {
            track_number: track.track_number,
            title,
            performer,
            start_ms,
            end_ms,
            file_name: track.file_name.clone(),
        });
    }

    Some(ParsedCueSheet {
        album_title: if album_title.trim().is_empty() {
            "Cue Sheet".to_owned()
        } else {
            album_title
        },
        album_performer: fallback_album_performer,
        file_name: first_file_name,
        tracks: entries,
    })
}

/// Computes the deterministic negative 64-bit FNV-1a ID matching Android `CueVirtualTracks`.
///
/// Always returns a non-zero negative `i64` in `[-2^62 - 1, -1]`.
#[must_use]
pub fn deterministic_negative_id(seed: &str) -> TrackId {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    const PRIME: u64 = 1_099_511_628_211;
    for ch in seed.encode_utf16() {
        hash ^= u64::from(ch);
        hash = hash.wrapping_mul(PRIME);
    }
    let magnitude = ((hash & 0x3FFF_FFFF_FFFF_FFFF) + 1) as i64;
    TrackId(-magnitude)
}

/// Converts a parsed `.cue` sheet referencing `backing_track` into virtual clipped [`Track`] items.
#[must_use]
pub fn build_virtual_tracks(
    cue: &ParsedCueSheet,
    backing_track: &Track,
    cue_source_key: &str,
) -> Vec<Track> {
    cue.tracks
        .iter()
        .map(|entry| {
            let effective_end = entry.end_ms.filter(|&end| end > entry.start_ms);
            let clip_duration_ms = if let Some(end) = effective_end {
                end - entry.start_ms
            } else if backing_track.duration_ms > entry.start_ms {
                backing_track.duration_ms - entry.start_ms
            } else {
                backing_track.duration_ms
            }
            .max(1_000);

            let seed = format!(
                "{cue_source_key}|{}|{}|{}",
                backing_track.id.0, entry.track_number, entry.start_ms
            );

            let artist = if entry.performer.trim().is_empty() {
                backing_track.artist.clone()
            } else {
                entry.performer.clone()
            };
            let album = if cue.album_title.trim().is_empty() {
                backing_track.album.clone()
            } else {
                cue.album_title.clone()
            };
            let album_artist = if cue.album_performer.trim().is_empty() {
                backing_track.album_artist.clone()
            } else {
                cue.album_performer.clone()
            };

            let mut clipped = backing_track.clone();
            clipped.id = deterministic_negative_id(&seed);
            clipped.title.clone_from(&entry.title);
            clipped.artist = artist;
            clipped.album = album;
            clipped.album_artist = album_artist;
            clipped.duration_ms = clip_duration_ms;
            clipped.track_number = entry.track_number.max(1);
            clipped.clip_start_ms = Some(entry.start_ms);
            clipped.clip_end_ms = effective_end;
            clipped.cue_source_path = Some(cue_source_key.to_owned());
            clipped.is_favorite = false;
            clipped.play_count = 0;
            clipped.last_played_at_ms = 0;
            clipped.total_play_duration_ms = 0;
            clipped
        })
        .collect()
}
