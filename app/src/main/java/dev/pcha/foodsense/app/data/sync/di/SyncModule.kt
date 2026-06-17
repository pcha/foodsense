package dev.pcha.foodsense.app.data.sync.di

import com.google.firebase.firestore.FirebaseFirestore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pcha.foodsense.app.data.sync.FirebaseFirestoreSyncRepository
import dev.pcha.foodsense.app.data.sync.FirestoreSyncRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: FirebaseFirestoreSyncRepository): FirestoreSyncRepository

    companion object {
        @Provides
        @Singleton
        fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()
    }
}
