package com.vtrack.app.data.remote

import com.vtrack.app.data.remote.dto.AchievementDto
import com.vtrack.app.data.remote.dto.ActivityDayDto
import com.vtrack.app.data.remote.dto.ActivitySyncRequest
import com.vtrack.app.data.remote.dto.ActivitySyncResponse
import com.vtrack.app.data.remote.dto.AuthResponse
import com.vtrack.app.data.remote.dto.BalanceDto
import com.vtrack.app.data.remote.dto.ChallengeDto
import com.vtrack.app.data.remote.dto.ChallengeListDto
import com.vtrack.app.data.remote.dto.ClaimStepsRequest
import com.vtrack.app.data.remote.dto.ClaimWorkoutRequest
import com.vtrack.app.data.remote.dto.CoachAnswerDto
import com.vtrack.app.data.remote.dto.CoachAskRequest
import com.vtrack.app.data.remote.dto.CoachPlanDto
import com.vtrack.app.data.remote.dto.CoachReviewDto
import com.vtrack.app.data.remote.dto.CoinClaimResponse
import com.vtrack.app.data.remote.dto.ExportDto
import com.vtrack.app.data.remote.dto.LoginRequest
import com.vtrack.app.data.remote.dto.RedemptionDto
import com.vtrack.app.data.remote.dto.RedemptionRequest
import com.vtrack.app.data.remote.dto.RefreshRequest
import com.vtrack.app.data.remote.dto.RegisterRequest
import com.vtrack.app.data.remote.dto.RewardDto
import com.vtrack.app.data.remote.dto.ServerConfigDto
import com.vtrack.app.data.remote.dto.TransactionPageDto
import com.vtrack.app.data.remote.dto.UpdateProfileRequest
import com.vtrack.app.data.remote.dto.UpdateWorkoutRequest
import com.vtrack.app.data.remote.dto.UserDto
import com.vtrack.app.data.remote.dto.WorkoutDto
import com.vtrack.app.data.remote.dto.WorkoutSyncResponse
import com.vtrack.app.data.remote.dto.WorkoutUpload
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * One interface for the whole REST surface. Endpoints under `v1/auth/` are exempt from
 * the bearer interceptor (see [AuthInterceptor]) so a logged-out client can still
 * register, log in and refresh.
 */
interface VTrackApi {

    /* ---------------- auth (no bearer required) ---------------- */

    @POST("v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthResponse

    @POST("v1/auth/logout")
    suspend fun logout(@Body body: RefreshRequest)

    /* ---------------- config ---------------- */

    @GET("v1/config")
    suspend fun config(): ServerConfigDto

    /* ---------------- profile ---------------- */

    @GET("v1/me")
    suspend fun me(): UserDto

    @PATCH("v1/me")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): UserDto

    @DELETE("v1/me")
    suspend fun deleteAccount()

    @GET("v1/me/export")
    suspend fun exportData(): ExportDto

    /* ---------------- activity ---------------- */

    @POST("v1/activity/sync")
    suspend fun syncActivity(@Body body: ActivitySyncRequest): ActivitySyncResponse

    @GET("v1/activity/days")
    suspend fun activityDays(
        @Query("from") from: String,
        @Query("to") to: String,
    ): List<ActivityDayDto>

    @POST("v1/activity/claim/steps")
    suspend fun claimSteps(@Body body: ClaimStepsRequest): CoinClaimResponse

    /* ---------------- workouts ---------------- */

    @POST("v1/workouts/sync")
    suspend fun syncWorkouts(@Body body: List<WorkoutUpload>): WorkoutSyncResponse

    @GET("v1/workouts")
    suspend fun workouts(
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null,
    ): List<WorkoutDto>

    @PATCH("v1/workouts/{clientId}")
    suspend fun updateWorkout(
        @Path("clientId") clientId: String,
        @Body body: UpdateWorkoutRequest,
    ): WorkoutDto

    @POST("v1/activity/claim/workout")
    suspend fun claimWorkout(@Body body: ClaimWorkoutRequest): CoinClaimResponse

    /* ---------------- coins, challenges, rewards ---------------- */

    @GET("v1/coins/balance")
    suspend fun balance(): BalanceDto

    @GET("v1/coins/transactions")
    suspend fun transactions(
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null,
    ): TransactionPageDto

    @GET("v1/challenges")
    suspend fun challenges(): ChallengeListDto

    @POST("v1/challenges/{id}/claim")
    suspend fun claimChallenge(@Path("id") id: String): CoinClaimResponse

    @GET("v1/achievements")
    suspend fun achievements(): List<AchievementDto>

    @GET("v1/rewards")
    suspend fun rewards(): List<RewardDto>

    @POST("v1/rewards/{id}/redeem")
    suspend fun redeem(@Path("id") id: String, @Body body: RedemptionRequest): RedemptionDto

    @GET("v1/redemptions")
    suspend fun redemptions(): List<RedemptionDto>

    /* ---------------- coach ---------------- */

    @GET("v1/coach/plan")
    suspend fun coachPlan(): CoachPlanDto

    @POST("v1/coach/ask")
    suspend fun coachAsk(@Body body: CoachAskRequest): CoachAnswerDto

    @GET("v1/coach/review")
    suspend fun coachReview(): CoachReviewDto
}
