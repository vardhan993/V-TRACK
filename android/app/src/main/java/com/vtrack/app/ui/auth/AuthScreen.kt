package com.vtrack.app.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vtrack.app.core.error.userMessage
import com.vtrack.app.data.repository.AuthRepository
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.ui.components.PrimaryActionButton
import com.vtrack.app.ui.components.StatusChip
import com.vtrack.app.ui.theme.VTrackTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val mode: Mode = Mode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val showPassword: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    enum class Mode { SIGN_IN, REGISTER }

    val emailValid: Boolean
        get() = email.contains("@") && email.substringAfter("@").contains(".") && email.length >= 6

    val passwordValid: Boolean get() = password.length >= 8

    /** Concrete, checkable requirements rather than a vague "invalid input". */
    val passwordHint: String
        get() = when {
            password.isEmpty() -> "At least 8 characters"
            password.length < 8 -> "${8 - password.length} more character(s) needed"
            password.none { it.isDigit() } -> "Add a number for a stronger password (optional)"
            else -> "Password length looks good"
        }

    val canSubmit: Boolean
        get() = emailValid && passwordValid &&
            (mode == Mode.SIGN_IN || displayName.trim().length >= 2) && !loading
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    fun setEmail(value: String) = _ui.update { it.copy(email = value.trim(), error = null) }
    fun setPassword(value: String) = _ui.update { it.copy(password = value, error = null) }
    fun setDisplayName(value: String) = _ui.update { it.copy(displayName = value, error = null) }
    fun togglePasswordVisibility() = _ui.update { it.copy(showPassword = !it.showPassword) }
    fun toggleMode() = _ui.update {
        it.copy(
            mode = if (it.mode == AuthUiState.Mode.SIGN_IN) AuthUiState.Mode.REGISTER else AuthUiState.Mode.SIGN_IN,
            error = null,
        )
    }

    /** [onSuccess] receives true when the user still has to complete onboarding. */
    fun submit(onSuccess: (needsOnboarding: Boolean) -> Unit) {
        val state = _ui.value
        if (!state.canSubmit) return
        _ui.value = state.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = if (state.mode == AuthUiState.Mode.SIGN_IN) {
                authRepository.login(state.email, state.password)
            } else {
                authRepository.register(state.email, state.password, state.displayName)
            }
            result.fold(
                onSuccess = { profile ->
                    profileRepository.ensureLocalProfile()
                    _ui.value = _ui.value.copy(loading = false)
                    onSuccess(!profile.onboardingComplete)
                },
                onFailure = { throwable ->
                    _ui.value = _ui.value.copy(loading = false, error = throwable.userMessage())
                },
            )
        }
    }
}

@Composable
fun AuthScreen(
    onAuthenticated: (needsOnboarding: Boolean) -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            text = "V-Track",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Track real activity. Earn V-Coins. Train with a coach that explains itself.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        StatusChip(
            text = if (ui.mode == AuthUiState.Mode.SIGN_IN) "SIGN IN" else "CREATE ACCOUNT",
            color = MaterialTheme.colorScheme.primary,
        )

        AnimatedVisibility(visible = ui.mode == AuthUiState.Mode.REGISTER) {
            OutlinedTextField(
                value = ui.displayName,
                onValueChange = viewModel::setDisplayName,
                label = { Text("Name") },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = ui.email,
            onValueChange = viewModel::setEmail,
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            singleLine = true,
            isError = ui.email.isNotEmpty() && !ui.emailValid,
            supportingText = {
                if (ui.email.isNotEmpty() && !ui.emailValid) Text("Enter a valid email address")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = ui.password,
            onValueChange = viewModel::setPassword,
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = viewModel::togglePasswordVisibility) {
                    Icon(
                        imageVector = if (ui.showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (ui.showPassword) "Hide password" else "Show password",
                    )
                }
            },
            visualTransformation = if (ui.showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            singleLine = true,
            supportingText = { Text(ui.passwordHint) },
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(visible = ui.error != null) {
            Text(
                text = ui.error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(4.dp))
        PrimaryActionButton(
            text = if (ui.mode == AuthUiState.Mode.SIGN_IN) "Sign in" else "Create account",
            onClick = { viewModel.submit(onAuthenticated) },
            enabled = ui.canSubmit,
            loading = ui.loading,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = viewModel::toggleMode) {
                Text(
                    text = if (ui.mode == AuthUiState.Mode.SIGN_IN) {
                        "New here? Create an account"
                    } else {
                        "Already have an account? Sign in"
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Your account is hosted on V-Track's own Cloudflare Worker. Passwords are " +
                        "stored as PBKDF2-HMAC-SHA256 hashes; the app keeps only short-lived tokens in " +
                        "Android Keystore. No Firebase, no Supabase, no third-party trackers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Everything except sign-in and coin verification works offline.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = VTrackTheme.colors.success,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
