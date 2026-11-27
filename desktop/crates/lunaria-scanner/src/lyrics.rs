//! Local `.lrc` sidecar and embedded audio tag lyrics loader (`<= 256 KB`).

use std::fs::{self, File};
use std::io::Read;
use std::path::Path;

use lofty::file::TaggedFileExt;
use lofty::probe::Probe;
use lofty::tag::ItemKey;
use lunaria_model::{ParsedLyrics, parse_lyrics};

/// Maximum lyric file / tag payload accepted (`256 KiB`), matching Lunaria Android parity.
pub const MAX_LYRICS_BYTES: usize = 256 * 1024;

/// Loads local lyrics for `audio_path` by checking a sibling `.lrc` file first and falling back
/// to embedded `Lyrics` / `UnsyncLyrics` container tags.
#[must_use]
pub fn load_local_lyrics(audio_path: &Path) -> Option<ParsedLyrics> {
    if let Some(raw_lrc) = read_sibling_lrc(audio_path)
        && let Some(parsed) = parse_lyrics_non_empty(&raw_lrc, "Local .lrc file")
    {
        return Some(parsed);
    }

    if let Some(raw_embedded) = read_embedded_lyrics(audio_path)
        && let Some(parsed) = parse_lyrics_non_empty(&raw_embedded, "Embedded audio tag")
    {
        return Some(parsed);
    }

    None
}

fn parse_lyrics_non_empty(raw: &str, source_label: &str) -> Option<ParsedLyrics> {
    if raw.len() > MAX_LYRICS_BYTES || raw.trim().is_empty() {
        return None;
    }
    let doc = parse_lyrics(raw, source_label)?;
    if doc.lines.is_empty() {
        None
    } else {
        Some(doc)
    }
}

fn read_sibling_lrc(audio_path: &Path) -> Option<String> {
    let lrc_path = audio_path.with_extension("lrc");
    if !lrc_path.is_file() {
        return None;
    }
    let meta = fs::metadata(&lrc_path).ok()?;
    if meta.len() as usize > MAX_LYRICS_BYTES || meta.len() == 0 {
        return None;
    }
    let mut file = File::open(&lrc_path).ok()?;
    let mut buf = Vec::with_capacity(meta.len() as usize);
    let _read = file.read_to_end(&mut buf).ok()?;
    Some(String::from_utf8_lossy(&buf).into_owned())
}

fn read_embedded_lyrics(audio_path: &Path) -> Option<String> {
    let tagged = Probe::open(audio_path)
        .ok()?
        .guess_file_type()
        .ok()?
        .read()
        .ok()?;
    for tag in tagged.tags() {
        if let Some(val) = tag.get_string(ItemKey::Lyrics)
            && !val.trim().is_empty()
        {
            return Some(val.to_owned());
        }
        if let Some(val) = tag.get_string(ItemKey::UnsyncLyrics)
            && !val.trim().is_empty()
        {
            return Some(val.to_owned());
        }
    }
    None
}
