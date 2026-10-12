package com.vtrack.app.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.vtrack.app.BuildConfig
import com.vtrack.app.data.remote.AuthInterceptor
import com.vtrack.app.data.remote.TokenAuthenticator
import com.vtrack.app.data.remote.VTrackApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Networking wiring.
 *
 * The base URL is injected from `BuildConfig.API_BASE_URL`, which is generated from a
 * Gradle property / environment variable — never hard-coded, and no API keys live in
 * source. All production traffic is HTTPS; cleartext is limited to loopback development
 * hosts by `network_security_config.xml`.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @Named("apiBaseUrl")
    fun provideBaseUrl(): String = BuildConfig.API_BASE_URL

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
    }

    /** Client used for token refresh and nothing else — no interceptor, no authenticator. */
    @Provides
    @Singleton
    @Named("bareClient")
    fun provideBareClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor { chain ->
                // A stable client identifier helps the server apply per-client rate limits.
                chain.proceed(
                    chain.request().newBuilder()
                        .header("X-Client-Version", BuildConfig.VERSION_CODE.toString())
                        .build(),
                )
            }

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    // Never log bodies: they can contain credentials and health data.
                    level = HttpLoggingInterceptor.Level.BASIC
                },
            )
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        json: Json,
        @Named("apiBaseUrl") baseUrl: String,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): VTrackApi = retrofit.create(VTrackApi::class.java)
}
