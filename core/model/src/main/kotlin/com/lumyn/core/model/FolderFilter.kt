package com.lumyn.core.model

enum class FolderFilterMode {
    BLACKLIST,
    WHITELIST,
}

val DefaultExcludedFolders: Set<String> = setOf(
    "Notifications",
    "Ringtones",
    "Alarms",
    "Recordings",
    "WhatsApp Audio",
    "WhatsApp Voice Notes",
    "Telegram Audio",
    "CallRecordings",
)

data class FolderFilterConfig(
    val mode: FolderFilterMode = FolderFilterMode.BLACKLIST,
    val includedFolders: Set<String> = emptySet(),
    val excludedFolders: Set<String> = DefaultExcludedFolders,
) {
    fun isFolderAllowed(folderPath: String): Boolean {
        val normalized = normalizeFolderPath(folderPath)
        if (normalized.isEmpty()) return mode == FolderFilterMode.BLACKLIST
        val segments = normalized.split('/')

        return when (mode) {
            FolderFilterMode.BLACKLIST -> excludedFolders.none { rawRule ->
                val rule = normalizeFolderPath(rawRule)
                if (rule.isEmpty()) {
                    false
                } else if ('/' in rule) {
                    normalized.equals(rule, ignoreCase = true) ||
                        normalized.startsWith("$rule/", ignoreCase = true)
                } else {
                    segments.any { it.equals(rule, ignoreCase = true) }
                }
            }

            FolderFilterMode.WHITELIST -> {
                if (includedFolders.isEmpty()) return false
                includedFolders.any { rawRule ->
                    val rule = normalizeFolderPath(rawRule)
                    rule.isNotEmpty() && (
                        normalized.equals(rule, ignoreCase = true) ||
                            normalized.startsWith("$rule/", ignoreCase = true)
                        )
                }
            }
        }
    }

    companion object {
        fun normalizeFolderPath(raw: String): String =
            raw.replace('\\', '/')
                .split('/')
                .filter { it.isNotBlank() }
                .joinToString("/")
    }
}
