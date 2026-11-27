package com.lunaria.music.widget

import com.lunaria.core.audioengine.PlaybackWidgetNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetModule {
    @Binds
    @Singleton
    abstract fun bindPlaybackWidgetNotifier(impl: GlancePlaybackWidgetNotifier): PlaybackWidgetNotifier
}
