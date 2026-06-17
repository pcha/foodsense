package dev.pcha.foodsense.app.data.auth

import android.content.Context
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val currentUser: Flow<FirebaseUser?>

    suspend fun signInWithGoogle(context: Context): Result<FirebaseUser>
    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser>
    suspend fun createAccountWithEmail(email: String, password: String): Result<FirebaseUser>
    suspend fun signOut()
}
