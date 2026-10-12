package com.vtrack.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vtrack.app.core.security.SessionEvent
import com.vtrack.app.core.security.SessionStore
import com.vtrack.app.core.util.NetworkMonitor
import com.vtrack.app.data.repository.AuthRepository
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.data.repository.SyncEngine
import com.vtrack.app.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppRootState(
    val ready: Boolean = false,
    val isSignedIn: Boolean = false,
    val onboardingComplete: Boolean = true,
    val sessionExpired: Boolean = false,
    val isOnline: Boolean = true,
    val pendingSyncCount: Int = 0,
)

/** Drives the root navigation decision: sign-in, onboarding, or the main tabs. */
@HiltViewModel
class AppRootViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val sessionStore: SessionStore,
    syncEngine: SyncEngine,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val sessionExpired = MutableStateFlow(false)

    val state: StateFlow<AppRootState> = combine(
        profileRepository.observeProfile(),
        networkMonitor.isOnline,
        syncEngine.observePendingCount(),
        sessionExpired,
    ) { profile, online, pending, expired ->
        AppRootState(
            ready = true,
            isSignedIn = sessionStore.isSignedIn(),
            onboardingComplete = profile.onboardingComplete && profile.id.isNotEmpty(),
            sessionExpired = expired,
            isOnline = online,
            pendingSyncCount = pending,
        )
    }.onStart {
        bootstrap()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppRootState(ready = false))

    private fun bootstrap() {
        viewModelScope.launch {
            sessionStore.events.collect { event ->
                when (event) {
                    SessionEvent.SessionExpired -> sessionExpired.value = true
                    SessionEvent.SignedIn -> sessionExpired.value = false
                    SessionEvent.SignedOut -> sessionExpired.value = false
                }
            }
        }
        viewModelScope.launch {
            if (sessionStore.isSignedIn()) {
                // Recreate the local profile row if the database was cleared.
                profileRepository.ensureLocalProfile()
                profileRepository.refreshFromServer()
            }
        }
    }

    fun dismissSessionExpired() {
        sessionExpired.value = false
    }

    val profile: StateFlow<UserProfile> = profileRepository.observeProfile()
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserProfile.empty())
}
