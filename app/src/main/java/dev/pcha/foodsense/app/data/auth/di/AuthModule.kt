package dev.pcha.foodsense.app.data.auth.di

import android.content.Context
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseAuth
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pcha.foodsense.app.R
import dev.pcha.foodsense.app.data.auth.AuthRepository
import dev.pcha.foodsense.app.data.auth.FirebaseAuthRepository
import dev.pcha.foodsense.app.data.auth.WebClientId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: FirebaseAuthRepository): AuthRepository

    companion object {
        @Provides
        @Singleton
        fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

        @Provides
        @Singleton
        fun provideCredentialManager(@ApplicationContext ctx: Context): CredentialManager =
            CredentialManager.create(ctx)

        @Provides
        @WebClientId
        fun provideWebClientId(@ApplicationContext ctx: Context): String =
            ctx.getString(R.string.default_web_client_id)
    }
}
