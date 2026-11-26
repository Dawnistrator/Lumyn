package com.lumyn.core.audioengine

import com.lumyn.core.model.Track

/** Decoupled hook notified by [LumynPlaybackService] whenever the active track or play state changes. */
interface PlaybackWidgetNotifier {
    fun onPlaybackStateChanged(track: Track?, isPlaying: Boolean)
}
