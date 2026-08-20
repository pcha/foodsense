package dev.pcha.foodsense.app.testdi

import android.content.Context
import dev.pcha.foodsense.app.data.auth.AuthRepository
import dev.pcha.foodsense.app.data.auth.User
import dev.pcha.foodsense.app.data.preferences.OnboardingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deja el flag de onboarding sin emitir hasta que el test lo decida.
 *
 * `AuthViewModel.onboardingDone` arranca en `null` ("todavía no cargó") y `MainNavigation` no
 * evalúa el gate de login hasta que deja de serlo. Con un [MutableSharedFlow] sin valor inicial,
 * la activity puede lanzarse y recién después el test elige el escenario.
 */
class FakeOnboardingRepository : OnboardingRepository {
    private val _onboardingDone = MutableSharedFlow<Boolean>(replay = 1)
    override val onboardingDone: Flow<Boolean> = _onboardingDone

    override suspend fun setOnboardingDone() {
        _onboardingDone.tryEmit(true)
    }

    fun emit(done: Boolean) {
        _onboardingDone.tryEmit(done)
    }
}

class FakeAuthRepository : AuthRepository {
    private val _currentUser = MutableStateFlow<User?>(null)
    override val currentUser: Flow<User?> = _currentUser.asStateFlow()

    fun signIn(user: User) {
        _currentUser.value = user
    }

    override suspend fun signInWithGoogle(context: Context): Result<User> = throw NotImplementedError()
    override suspend fun signInWithEmail(email: String, password: String): Result<User> = throw NotImplementedError()
    override suspend fun createAccountWithEmail(email: String, password: String): Result<User> = throw NotImplementedError()

    override suspend fun signOut() {
        _currentUser.value = null
    }
}
