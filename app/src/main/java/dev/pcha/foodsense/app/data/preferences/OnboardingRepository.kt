package dev.pcha.foodsense.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

interface OnboardingRepository {
    val onboardingDone: Flow<Boolean>
    suspend fun setOnboardingDone()
}

private val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

class DataStoreOnboardingRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : OnboardingRepository {

    override val onboardingDone: Flow<Boolean> =
        context.onboardingDataStore.data.map { it[ONBOARDING_DONE] ?: false }

    override suspend fun setOnboardingDone() {
        context.onboardingDataStore.edit { it[ONBOARDING_DONE] = true }
    }

    private companion object {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }
}
