package com.lumyn.core.database.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.lumyn.core.database.LumynDatabase
import com.lumyn.core.database.dao.PlaylistDao
import com.lumyn.core.database.dao.TrackDao
import com.lumyn.core.database.repository.DefaultLocalMusicRepository
import com.lumyn.core.database.repository.LocalMusicRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LumynDatabase =
        Room.databaseBuilder(context, LumynDatabase::class.java, LumynDatabase.NAME)
            .addMigrations(LumynDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideTrackDao(database: LumynDatabase): TrackDao = database.trackDao()

    @Provides
    fun providePlaylistDao(database: LumynDatabase): PlaylistDao = database.playlistDao()

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("lumyn_prefs") })
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindLocalMusicRepository(impl: DefaultLocalMusicRepository): LocalMusicRepository
}
