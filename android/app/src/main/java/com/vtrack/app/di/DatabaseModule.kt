package com.vtrack.app.di

import android.content.Context
import androidx.room.Room
import com.vtrack.app.data.local.VTrackDatabase
import com.vtrack.app.data.local.dao.ActivityDao
import com.vtrack.app.data.local.dao.ProfileDao
import com.vtrack.app.data.local.dao.RewardsDao
import com.vtrack.app.data.local.dao.SyncDao
import com.vtrack.app.data.local.dao.WorkoutDao
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
    fun provideDatabase(@ApplicationContext context: Context): VTrackDatabase =
        Room.databaseBuilder(context, VTrackDatabase::class.java, VTrackDatabase.NAME)
            // SQLite's default write-ahead logging plus a trimmed pool keeps long GPS
            // sessions from contending with UI reads.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides
    fun provideProfileDao(database: VTrackDatabase): ProfileDao = database.profileDao()

    @Provides
    fun provideActivityDao(database: VTrackDatabase): ActivityDao = database.activityDao()

    @Provides
    fun provideWorkoutDao(database: VTrackDatabase): WorkoutDao = database.workoutDao()

    @Provides
    fun provideRewardsDao(database: VTrackDatabase): RewardsDao = database.rewardsDao()

    @Provides
    fun provideSyncDao(database: VTrackDatabase): SyncDao = database.syncDao()
}
