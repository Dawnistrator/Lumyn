//! Folder whitelist/blacklist filtering with Windows path normalization.

use serde::{Deserialize, Serialize};

/// Mode governing folder inclusion rules.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
pub enum FolderFilterMode {
    /// Exclude folders matching any configured blacklist rule.
    #[default]
    Blacklist,
    /// Only include folders matching an explicit whitelist rule.
    Whitelist,
}

/// Default noisy system/messaging folders excluded out-of-the-box.
pub const DEFAULT_EXCLUDED_FOLDERS: [&str; 8] = [
    "Notifications",
    "Ringtones",
    "Alarms",
    "Recordings",
    "WhatsApp Audio",
    "WhatsApp Voice Notes",
    "Telegram Audio",
    "CallRecordings",
];

/// Configuration controlling which folders are surfaced in Lunaria's Library.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FolderFilterConfig {
    /// Active filtering mode.
    pub mode: FolderFilterMode,
    /// Allowed folder paths in [`FolderFilterMode::Whitelist`] mode.
    pub included_folders: Vec<String>,
    /// Excluded folder names or paths in [`FolderFilterMode::Blacklist`] mode.
    pub excluded_folders: Vec<String>,
}

impl Default for FolderFilterConfig {
    fn default() -> Self {
        Self {
            mode: FolderFilterMode::Blacklist,
            included_folders: Vec::new(),
            excluded_folders: DEFAULT_EXCLUDED_FOLDERS
                .iter()
                .map(|&name| name.to_owned())
                .collect(),
        }
    }
}

fn matches_prefix_ci(normalized: &str, rule: &str) -> bool {
    if normalized.eq_ignore_ascii_case(rule) {
        return true;
    }
    if normalized.len() <= rule.len() {
        return false;
    }
    normalized
        .get(..rule.len())
        .is_some_and(|head| head.eq_ignore_ascii_case(rule))
        && normalized.as_bytes()[rule.len()] == b'/'
}

impl FolderFilterConfig {
    /// Normalizes Windows `\\?\` extended-length prefixes and backslashes into forward-slash paths.
    #[must_use]
    pub fn normalize_folder_path(raw: &str) -> String {
        let stripped = raw.trim().strip_prefix(r"\\?\").unwrap_or(raw.trim());
        let replaced = stripped.replace('\\', "/");
        replaced
            .split('/')
            .filter(|seg| !seg.trim().is_empty())
            .collect::<Vec<_>>()
            .join("/")
    }

    /// Evaluates whether a directory walk should descend into `dir_path`.
    ///
    /// In [`FolderFilterMode::Whitelist`] mode, returns `true` for included directories, any
    /// ancestor directory leading to an included directory, or when no whitelist rules exist yet
    /// (preventing accidental library wipes).
    #[must_use]
    pub fn should_descend_directory(&self, dir_path: &str) -> bool {
        let normalized = Self::normalize_folder_path(dir_path);
        match self.mode {
            FolderFilterMode::Blacklist => self.is_folder_allowed(&normalized),
            FolderFilterMode::Whitelist => {
                if self.included_folders.is_empty() || self.is_folder_allowed(&normalized) {
                    return true;
                }
                if normalized.is_empty() {
                    return true;
                }
                self.included_folders.iter().any(|raw_rule| {
                    let rule = Self::normalize_folder_path(raw_rule);
                    !rule.is_empty() && matches_prefix_ci(&rule, &normalized)
                })
            }
        }
    }

    /// Evaluates whether a track's folder passes the current blacklist/whitelist configuration.
    #[must_use]
    pub fn is_folder_allowed(&self, folder_path: &str) -> bool {
        let normalized = Self::normalize_folder_path(folder_path);
        if normalized.is_empty() {
            return self.mode == FolderFilterMode::Blacklist;
        }
        let segments: Vec<&str> = normalized.split('/').collect();

        match self.mode {
            FolderFilterMode::Blacklist => !self.excluded_folders.iter().any(|raw_rule| {
                let rule = Self::normalize_folder_path(raw_rule);
                if rule.is_empty() {
                    false
                } else if rule.contains('/') {
                    matches_prefix_ci(&normalized, &rule)
                } else {
                    segments.iter().any(|seg| seg.eq_ignore_ascii_case(&rule))
                }
            }),
            FolderFilterMode::Whitelist => {
                if self.included_folders.is_empty() {
                    return true;
                }
                self.included_folders.iter().any(|raw_rule| {
                    let rule = Self::normalize_folder_path(raw_rule);
                    !rule.is_empty() && matches_prefix_ci(&normalized, &rule)
                })
            }
        }
    }
}
