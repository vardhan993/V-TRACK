package com.vtrack.app.data.repository

import com.vtrack.app.data.local.Mappers.toDomain
import com.vtrack.app.data.local.Mappers.toEntity
import com.vtrack.app.data.local.dao.WorkoutDao
import com.vtrack.app.domain.model.Exercise
import com.vtrack.app.domain.model.ExerciseLibrary
import com.vtrack.app.domain.model.FitnessLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exercise catalogue access. The bundled library is seeded once on first launch and is
 * then a normal Room table, so the whole library (including technique notes) is available
 * offline and users can add their own exercises alongside it.
 */
@Singleton
class ExerciseRepository @Inject constructor(
    private val workoutDao: WorkoutDao,
) {
    suspend fun ensureSeeded() {
        val existing = workoutDao.exerciseCount()
        if (existing >= ExerciseLibrary.seed().size) return
        workoutDao.upsertExercises(ExerciseLibrary.seed().map { it.toEntity() })
    }

    fun observeAll(): Flow<List<Exercise>> =
        workoutDao.observeExercises().map { list -> list.map { it.toDomain() } }

    fun observeCategory(category: String): Flow<List<Exercise>> =
        workoutDao.observeExercisesByCategory(category).map { list -> list.map { it.toDomain() } }

    suspend fun get(id: String): Exercise? = workoutDao.getExercise(id)?.toDomain()

    suspend fun search(query: String): List<Exercise> =
        if (query.isBlank()) emptyList()
        else workoutDao.searchExercises(query.trim()).map { it.toDomain() }

    suspend fun addCustom(
        name: String,
        category: com.vtrack.app.domain.model.ActivityCategory,
        primaryMuscle: String,
        equipment: String,
        difficulty: FitnessLevel,
        instructions: List<String>,
        defaultSets: Int? = 3,
        defaultReps: Int? = 10,
        defaultSeconds: Int? = null,
    ): Exercise {
        val exercise = Exercise(
            id = "custom.${UUID.randomUUID()}",
            name = name.trim(),
            category = category,
            primaryMuscle = primaryMuscle,
            equipment = equipment,
            difficulty = difficulty,
            metValue = 4.5,
            instructions = instructions.filter { it.isNotBlank() },
            cues = emptyList(),
            commonMistakes = emptyList(),
            defaultSets = defaultSets,
            defaultReps = defaultReps,
            defaultSeconds = defaultSeconds,
            isCompound = false,
            isCustom = true,
        )
        workoutDao.upsertExercise(exercise.toEntity())
        return exercise
    }

    suspend fun deleteCustom(id: String) = workoutDao.deleteCustomExercise(id)
}
