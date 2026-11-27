package com.lunaria.core.audioengine

import com.lunaria.core.model.Track

/** Decoupled hook notified by [LunariaPlaybackService] whenever the active track or play state changes. */
interface PlaybackWidgetNotifier {
    fun onPlaybackStateChanged(track: Track?, isPlaying: Boolean)
}
