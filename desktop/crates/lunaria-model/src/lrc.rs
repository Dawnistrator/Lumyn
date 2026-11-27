//! Pure-Rust `.lrc` synced and unsynced lyrics parser.

/// Single timestamped (or unsynced) lyric line.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LyricLine {
    /// Timestamp in milliseconds from track start.
    pub timestamp_ms: u64,
    /// Lyric text for this line.
    pub text: String,
}

/// Parsed lyrics document with sync status and provenance label.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedLyrics {
    /// Ordered lyric lines.
    pub lines: Vec<LyricLine>,
    /// `true` when timestamps were present in the source.
    pub is_synced: bool,
    /// Human-readable origin label (e.g. `"Local .lrc file"`).
    pub source_label: String,
}

fn is_metadata_tag_line(line: &str) -> bool {
    let Some(inner) = line.strip_prefix('[').and_then(|s| s.strip_suffix(']')) else {
        return false;
    };
    let Some((tag, _)) = inner.split_once(':') else {
        return false;
    };
    matches!(
        tag.trim().to_ascii_lowercase().as_str(),
        "ar" | "ti" | "al" | "au" | "by" | "re" | "ve" | "length"
    )
}

fn parse_offset_line(line: &str) -> Option<i64> {
    let inner = line.strip_prefix('[')?.strip_suffix(']')?;
    let (tag, value) = inner.split_once(':')?;
    if tag.trim().eq_ignore_ascii_case("offset") {
        value.trim().parse::<i64>().ok()
    } else {
        None
    }
}

fn parse_timestamp_token(token: &str) -> Option<u64> {
    let (min_str, rest) = token.split_once(':')?;
    if min_str.is_empty() || min_str.len() > 3 || !min_str.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }

    let (sec_str, frac_str) = match rest.find(['.', ':']) {
        Some(sep_idx) => (&rest[..sep_idx], &rest[sep_idx + 1..]),
        None => (rest, ""),
    };

    if !(1..=2).contains(&sec_str.len()) || !sec_str.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }

    if !frac_str.is_empty()
        && (!(1..=3).contains(&frac_str.len()) || !frac_str.bytes().all(|b| b.is_ascii_digit()))
    {
        return None;
    }

    let minutes = min_str.parse::<u64>().ok()?;
    let seconds = sec_str.parse::<u64>().ok()?;
    let millis = match frac_str.len() {
        0 => 0,
        1 => frac_str.parse::<u64>().ok()?.saturating_mul(100),
        2 => frac_str.parse::<u64>().ok()?.saturating_mul(10),
        _ => frac_str.get(..3)?.parse::<u64>().ok()?,
    };

    Some(
        minutes
            .saturating_mul(60_000)
            .saturating_add(seconds.saturating_mul(1_000))
            .saturating_add(millis),
    )
}

fn extract_timestamps_and_text(line: &str) -> (Vec<u64>, String) {
    let mut timestamps = Vec::new();
    let mut text = String::with_capacity(line.len());
    let bytes = line.as_bytes();
    let mut cursor = 0;

    while cursor < bytes.len() {
        if bytes[cursor] == b'['
            && let Some(rel_close) = line[cursor + 1..].find(']')
        {
            let inner = &line[cursor + 1..cursor + 1 + rel_close];
            if let Some(ts) = parse_timestamp_token(inner) {
                timestamps.push(ts);
                cursor = cursor + 1 + rel_close + 1;
                continue;
            }
        }

        if let Some(ch) = line[cursor..].chars().next() {
            text.push(ch);
            cursor += ch.len_utf8();
        } else {
            break;
        }
    }

    (timestamps, text.trim().to_owned())
}

/// Parses `.lrc` synced lyrics or plain multi-line unsynced text.
#[must_use]
pub fn parse_lyrics(raw_text: &str, source_label: &str) -> Option<ParsedLyrics> {
    let cleaned = raw_text.strip_prefix('\u{FEFF}').unwrap_or(raw_text);
    if cleaned.trim().is_empty() {
        return None;
    }

    let mut offset_ms: i64 = 0;
    let mut synced_entries = Vec::new();
    let mut unsynced_lines = Vec::new();

    for raw_line in cleaned.lines() {
        let trimmed = raw_line.trim();
        if trimmed.is_empty() {
            continue;
        }

        if let Some(parsed_offset) = parse_offset_line(trimmed) {
            offset_ms = parsed_offset;
            continue;
        }

        if is_metadata_tag_line(trimmed) {
            continue;
        }

        let (timestamps, lyric_text) = extract_timestamps_and_text(trimmed);
        if timestamps.is_empty() {
            unsynced_lines.push(trimmed.to_owned());
        } else if !lyric_text.is_empty() {
            for ts in timestamps {
                let shifted = if offset_ms >= 0 {
                    ts.saturating_add(offset_ms as u64)
                } else {
                    ts.saturating_sub(offset_ms.unsigned_abs())
                };
                synced_entries.push(LyricLine {
                    timestamp_ms: shifted,
                    text: lyric_text.clone(),
                });
            }
        }
    }

    if !synced_entries.is_empty() {
        synced_entries.sort_by_key(|line| line.timestamp_ms);
        return Some(ParsedLyrics {
            lines: synced_entries,
            is_synced: true,
            source_label: source_label.to_owned(),
        });
    }

    if unsynced_lines.is_empty() {
        return None;
    }

    Some(ParsedLyrics {
        lines: unsynced_lines
            .into_iter()
            .map(|text| LyricLine {
                timestamp_ms: 0,
                text,
            })
            .collect(),
        is_synced: false,
        source_label: source_label.to_owned(),
    })
}

/// Finds the active synced lyric index at `position_ms`, or `None` before the first line.
#[must_use]
pub fn active_line_index_at(lines: &[LyricLine], position_ms: u64) -> Option<usize> {
    if lines.is_empty() {
        return None;
    }
    let count_not_after = lines.partition_point(|line| line.timestamp_ms <= position_ms);
    count_not_after.checked_sub(1)
}
