//! Pure-Rust `.m3u` / `.m3u8` playlist parser, exporter, and library matcher.

use std::fmt::Write as _;

use crate::Track;

/// Single parsed entry from an `.m3u` or `.m3u8` file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct M3uEntry {
    /// Raw file path or URI line.
    pub path_or_uri: String,
    /// Optional `#EXTINF` display title.
    pub title: Option<String>,
    /// Optional `#EXTINF` duration in seconds.
    pub duration_seconds: Option<u32>,
}

fn parse_extinf(line: &str) -> Option<(Option<u32>, Option<String>)> {
    let rest = line.strip_prefix("#EXTINF:")?;
    let (dur_part, title_part) = rest.split_once(',')?;
    let duration = dur_part
        .trim()
        .parse::<i64>()
        .ok()
        .and_then(|v| u32::try_from(v).ok());
    let title_trimmed = title_part.trim();
    let title = (!title_trimmed.is_empty()).then(|| title_trimmed.to_owned());
    Some((duration, title))
}

/// Parses `#EXTM3U` / plain `.m3u` / `.m3u8` playlist text into entries.
#[must_use]
pub fn parse_m3u(content: &str) -> Vec<M3uEntry> {
    let cleaned = content.strip_prefix('\u{FEFF}').unwrap_or(content);
    let mut entries = Vec::new();
    let mut pending_duration: Option<u32> = None;
    let mut pending_title: Option<String> = None;

    for raw_line in cleaned.lines() {
        let line = raw_line.trim();
        if line.is_empty() {
            continue;
        }

        if line.starts_with('#') {
            if let Some((dur, title)) = parse_extinf(line) {
                pending_duration = dur;
                pending_title = title;
            }
            continue;
        }

        entries.push(M3uEntry {
            path_or_uri: line.to_owned(),
            title: pending_title.take(),
            duration_seconds: pending_duration.take(),
        });
    }

    entries
}

/// Exports tracks as an `#EXTM3U` UTF-8 playlist string.
#[must_use]
pub fn export_m3u(tracks: &[Track]) -> String {
    let mut out = String::from("#EXTM3U\n");
    for track in tracks {
        let seconds = track.duration_ms / 1_000;
        let _ = writeln!(out, "#EXTINF:{seconds},{} - {}", track.artist, track.title);
        let folder = track.folder_path.trim_end_matches(['/', '\\']);
        if !folder.is_empty() && !track.file_name.trim().is_empty() {
            let _ = writeln!(out, "{folder}/{}", track.file_name);
        } else if !track.file_name.trim().is_empty() {
            let _ = writeln!(out, "{}", track.file_name);
        } else {
            let _ = writeln!(out, "{}", track.file_path);
        }
    }
    out
}

fn ends_with_ignore_ascii_case(haystack: &str, suffix: &str) -> bool {
    if haystack.len() < suffix.len() {
        return false;
    }
    let start = haystack.len() - suffix.len();
    haystack.is_char_boundary(start) && haystack[start..].eq_ignore_ascii_case(suffix)
}

/// Matches imported M3U entries against an indexed local track slice.
#[must_use]
pub fn match_m3u_tracks(entries: &[M3uEntry], library: &[Track]) -> Vec<Track> {
    if entries.is_empty() || library.is_empty() {
        return Vec::new();
    }

    let mut matched = Vec::new();
    for entry in entries {
        let normalized_path = entry.path_or_uri.replace('\\', "/");
        let trimmed_path = normalized_path.trim_start_matches('/');
        let entry_file_name = trimmed_path
            .rsplit_once('/')
            .map_or(trimmed_path, |(_, file)| file);

        let found = library
            .iter()
            .find(|c| {
                c.file_path.eq_ignore_ascii_case(&entry.path_or_uri)
                    || c.file_path
                        .replace('\\', "/")
                        .eq_ignore_ascii_case(&normalized_path)
            })
            .or_else(|| {
                library.iter().find(|c| {
                    let folder = c.folder_path.replace('\\', "/");
                    let folder_trimmed = folder.trim_matches('/');
                    let rel = if folder_trimmed.is_empty() {
                        c.file_name.clone()
                    } else if c.file_name.is_empty() {
                        folder_trimmed.to_owned()
                    } else {
                        format!("{folder_trimmed}/{}", c.file_name)
                    };
                    !rel.is_empty() && ends_with_ignore_ascii_case(trimmed_path, &rel)
                })
            })
            .or_else(|| {
                library.iter().find(|c| {
                    !entry_file_name.is_empty() && c.file_name.eq_ignore_ascii_case(entry_file_name)
                })
            })
            .or_else(|| {
                entry.title.as_deref().and_then(|ext_title| {
                    library.iter().find(|c| {
                        let artist_title = format!("{} - {}", c.artist, c.title);
                        artist_title.eq_ignore_ascii_case(ext_title)
                            || c.title.eq_ignore_ascii_case(ext_title)
                    })
                })
            });

        if let Some(track) = found {
            matched.push(track.clone());
        }
    }

    matched
}
