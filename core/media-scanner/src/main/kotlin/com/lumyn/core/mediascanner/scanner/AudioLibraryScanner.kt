package com.lumyn.core.mediascanner.scanner

import kotlinx.coroutines.flow.StateFlow

sealed interface ScanStatus {
    data object Idle : ScanStatus
    data class Scanning(val indexedCount: Int, val totalCount: Int) : ScanStatus
    data class Completed(val indexedCount: Int, val filteredOutCount: Int) : ScanStatus
    data object PermissionRequired : ScanStatus
    data class Error(val message: String) : ScanStatus
}

interface AudioLibraryScanner {
    val scanState: StateFlow<ScanStatus>
    val discoveredFolders: StateFlow<List<String>>
    suspend fun triggerScan()
}
