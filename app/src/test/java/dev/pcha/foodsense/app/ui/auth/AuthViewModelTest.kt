package dev.pcha.foodsense.app.ui.auth

import android.content.Context
import dev.pcha.foodsense.app.data.auth.AuthRepository
import dev.pcha.foodsense.app.data.auth.User
import dev.pcha.foodsense.app.data.preferences.OnboardingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun signInWithEmail_success_clearsLoadingAndError() = runTest {
        val viewModel = AuthViewModel(FakeAuthRepository(), FakeOnboardingRepository())

        viewModel.signInWithEmail("a@b.com", "pw")

        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun signInWithEmail_failure_setsError() = runTest {
        val repo = FakeAuthRepository(result = Result.failure(RuntimeException("bad creds")))
        val viewModel = AuthViewModel(repo, FakeOnboardingRepository())

        viewModel.signInWithEmail("a@b.com", "pw")

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("bad creds", viewModel.uiState.value.error)
    }

    @Test
    fun clearError_resetsError() = runTest {
        val repo = FakeAuthRepository(result = Result.failure(RuntimeException("bad creds")))
        val viewModel = AuthViewModel(repo, FakeOnboardingRepository())
        viewModel.signInWithEmail("a@b.com", "pw")

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun completeOnboarding_marksOnboardingDone() = runTest {
        val onboarding = FakeOnboardingRepository()
        val viewModel = AuthViewModel(FakeAuthRepository(), onboarding)

        viewModel.completeOnboarding()

        assertTrue(onboarding.done.value)
    }
}

private class FakeAuthRepository(
    private val result: Result<User> = Result.success(User("uid", "Name", "a@b.com", null)),
) : AuthRepository {
    override val currentUser: Flow<User?> = MutableStateFlow(null)
    override suspend fun signInWithGoogle(context: Context): Result<User> = result
    override suspend fun signInWithEmail(email: String, password: String): Result<User> = result
    override suspend fun createAccountWithEmail(email: String, password: String): Result<User> = result
    override suspend fun signOut() {}
}

private class FakeOnboardingRepository : OnboardingRepository {
    val done = MutableStateFlow(false)
    override val onboardingDone: Flow<Boolean> = done.asStateFlow()
    override suspend fun setOnboardingDone() { done.value = true }
}
