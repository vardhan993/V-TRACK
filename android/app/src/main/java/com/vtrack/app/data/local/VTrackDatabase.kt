package com.vtrack.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vtrack.app.data.local.dao.ActivityDao
import com.vtrack.app.data.local.dao.ProfileDao
import com.vtrack.app.data.local.dao.RewardsDao
import com.vtrack.app.data.local.dao.SyncDao
import com.vtrack.app.data.local.dao.WorkoutDao
import com.vtrack.app.data.local.entity.AchievementEntity
import com.vtrack.app.data.local.entity.ActivityDayEntity
import com.vtrack.app.data.local.entity.ChallengeEntity
import com.vtrack.app.data.local.entity.ChallengeProgressEntity
import com.vtrack.app.data.local.entity.CoachPlanEntity
import com.vtrack.app.data.local.entity.CoinTransactionEntity
import com.vtrack.app.data.local.entity.ExerciseEntity
import com.vtrack.app.data.local.entity.GoalEntity
import com.vtrack.app.data.local.entity.ImportedRecordEntity
import com.vtrack.app.data.local.entity.RedemptionEntity
import com.vtrack.app.data.local.entity.RewardEntity
import com.vtrack.app.data.local.entity.RoutineEntity
import com.vtrack.app.data.local.entity.RoutineItemEntity
import com.vtrack.app.data.local.entity.StepSampleEntity
import com.vtrack.app.data.local.entity.StrengthSetEntity
import com.vtrack.app.data.local.entity.SyncQueueEntity
import com.vtrack.app.data.local.entity.UserProfileEntity
import com.vtrack.app.data.local.entity.WorkoutPointEntity
import com.vtrack.app.data.local.entity.WorkoutSessionEntity

/**
 * Room is the single source of truth for the UI. Everything the app displays is read
 * from here, which is what makes the app fully usable with no network at all; the
 * Workers API is an enhancement that verifies and syncs, never a hard dependency.
 */
@Database(
    entities = [
        UserProfileEntity::class,
        GoalEntity::class,
        ImportedRecordEntity::class,
        StepSampleEntity::class,
        ActivityDayEntity::class,
        SyncQueueEntity::class,
        WorkoutSessionEntity::class,
        WorkoutPointEntity::class,
        StrengthSetEntity::class,
        ExerciseEntity::class,
        RoutineEntity::class,
        RoutineItemEntity::class,
        CoinTransactionEntity::class,
        ChallengeEntity::class,
        ChallengeProgressEntity::class,
        AchievementEntity::class,
        RewardEntity::class,
        RedemptionEntity::class,
        CoachPlanEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class VTrackDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun activityDao(): ActivityDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun rewardsDao(): RewardsDao
    abstract fun syncDao(): SyncDao

    companion object {
        const val NAME = "vtrack.db"
    }
}
