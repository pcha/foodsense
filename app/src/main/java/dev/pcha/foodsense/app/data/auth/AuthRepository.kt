package dev.pcha.foodsense.app.data.auth

import android.content.Context
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val currentUser: Flow<User?>

    suspend fun signInWithGoogle(context: Context): Result<User>
    suspend fun signInWithEmail(email: String, password: String): Result<User>
    suspend fun createAccountWithEmail(email: String, password: String): Result<User>
    suspend fun signOut()
}
