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
