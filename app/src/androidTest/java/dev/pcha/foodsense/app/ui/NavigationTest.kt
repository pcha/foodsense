package dev.pcha.foodsense.app.ui

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dev.pcha.foodsense.app.R
import dev.pcha.foodsense.app.data.auth.AuthRepository
import dev.pcha.foodsense.app.data.auth.di.AuthModule
import dev.pcha.foodsense.app.data.di.fakeProducts
import dev.pcha.foodsense.app.data.preferences.OnboardingRepository
import dev.pcha.foodsense.app.data.preferences.di.PreferencesModule
import dev.pcha.foodsense.app.testdi.FakeAuthRepository
import dev.pcha.foodsense.app.testdi.FakeOnboardingRepository
import dev.pcha.foodsense.app.ui.theme.MyApplicationTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Cubre el gate de navegación de [MainNavigation]: a dónde arranca la app según el flag de
 * onboarding y si hay sesión, y la ida y vuelta a Login desde la lista.
 *
 * `AuthModule` y `PreferencesModule` se desinstalan para no depender de Firebase ni de DataStore.
 * `DataModule` ya lo reemplaza `FakeDataModule`, así que la lista muestra [fakeProducts].
 *
 * El contenido se compone acá sobre [HiltTestActivity] en vez de lanzar `MainActivity`: cuando la
 * activity hace su propio `setContent`, el árbol no queda registrado en el test rule y toda
 * aserción falla con "No compose hierarchies found".
 */
@HiltAndroidTest
@UninstallModules(AuthModule::class, PreferencesModule::class)
class NavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val onboardingRepository: OnboardingRepository = FakeOnboardingRepository()

    @BindValue
    @JvmField
    val authRepository: AuthRepository = FakeAuthRepository()

    private val onboarding get() = onboardingRepository as FakeOnboardingRepository

    /** Vía recursos, no literales: renombrar un string ahora rompe la compilación, no el test. */
    private fun string(@StringRes id: Int) = composeTestRule.activity.getString(id)

    @Before
    fun setUp() {
        hiltRule.inject()
        composeTestRule.setContent { MyApplicationTheme { MainNavigation() } }
    }

    @Test
    fun onboardingDone_staysOnProductList() {
        onboarding.emit(true)

        composeTestRule.onNodeWithText(fakeProducts.first().name, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.login_continue_without_account)).assertDoesNotExist()
    }

    @Test
    fun onboardingPending_andSignedOut_opensLogin() {
        onboarding.emit(false)

        composeTestRule.onNodeWithText(string(R.string.login_continue_without_account)).assertIsDisplayed()
    }

    @Test
    fun accountAction_whenSignedOut_opensLoginAndBackReturnsToList() {
        onboarding.emit(true)

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_account)).performClick()
        composeTestRule.onNodeWithText(string(R.string.login_continue_without_account)).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_back)).performClick()

        composeTestRule.onNodeWithText(fakeProducts.first().name, substring = true).assertIsDisplayed()
    }
}
