package com.lunaria.core.database.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.lunaria.core.database.LunariaDatabase
import com.lunaria.core.database.dao.PlaylistDao
import com.lunaria.core.database.dao.TrackDao
import com.lunaria.core.database.repository.DefaultLocalMusicRepository
import com.lunaria.core.database.repository.LocalMusicRepository
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
    fun provideDatabase(@ApplicationContext context: Context): LunariaDatabase =
        Room.databaseBuilder(context, LunariaDatabase::class.java, LunariaDatabase.NAME)
            .addMigrations(LunariaDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideTrackDao(database: LunariaDatabase): TrackDao = database.trackDao()

    @Provides
    fun providePlaylistDao(database: LunariaDatabase): PlaylistDao = database.playlistDao()

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("lunaria_prefs") })
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindLocalMusicRepository(impl: DefaultLocalMusicRepository): LocalMusicRepository
}
