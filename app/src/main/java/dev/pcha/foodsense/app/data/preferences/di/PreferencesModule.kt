package dev.pcha.foodsense.app.data.preferences.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pcha.foodsense.app.data.preferences.DataStoreOnboardingRepository
import dev.pcha.foodsense.app.data.preferences.OnboardingRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PreferencesModule {

    @Binds
    @Singleton
    abstract fun bindOnboardingRepository(impl: DataStoreOnboardingRepository): OnboardingRepository
}
