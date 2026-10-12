package com.vtrack.app.di

import com.vtrack.app.domain.coach.CoachEngine
import com.vtrack.app.domain.coach.RulesBasedCoachEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The one place that decides which coach implementation the app uses.
 *
 * Swapping in a model-backed engine later is a one-line change here (or a debug flag that
 * returns a decorated engine). Nothing else in the app knows or cares which engine is
 * active — screens read `plan.generatedBy` to label the source honestly.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds
    @Singleton
    abstract fun bindCoachEngine(impl: RulesBasedCoachEngine): CoachEngine
}
