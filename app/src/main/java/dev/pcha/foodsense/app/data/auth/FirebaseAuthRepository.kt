package dev.pcha.foodsense.app.data.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject

class FirebaseAuthRepository @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val credentialManager: CredentialManager,
) : AuthRepository {

    override val currentUser: Flow<User?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser?.toUser()) }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    override suspend fun signInWithGoogle(context: Context): Result<User> {
        return try {
            val webClientId = getWebClientId(context)
            val nonce = generateNonce()

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setNonce(nonce)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credential = credentialManager.getCredential(context, request).credential
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
            val user = firebaseAuth.signInWithCredential(firebaseCredential).await().user!!
            Result.success(user.toUser())
        } catch (e: GetCredentialCancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<User> {
        return try {
            val user = firebaseAuth.signInWithEmailAndPassword(email, password).await().user!!
            Result.success(user.toUser())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createAccountWithEmail(email: String, password: String): Result<User> {
        return try {
            val user = firebaseAuth.createUserWithEmailAndPassword(email, password).await().user!!
            Result.success(user.toUser())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun signOut() {
        firebaseAuth.signOut()
    }

    private fun getWebClientId(context: Context): String {
        val json = context.assets.open("google-services.json").bufferedReader().use { it.readText() }
        val config = JSONObject(json)
        val clients = config.getJSONArray("client")

        for (i in 0 until clients.length()) {
            val client = clients.getJSONObject(i)
            val oauthClients = client.getJSONArray("oauth_client")

            for (j in 0 until oauthClients.length()) {
                val oauthClient = oauthClients.getJSONObject(j)
                if (oauthClient.getInt("client_type") == 3) {
                    return oauthClient.getString("client_id")
                }
            }
        }
        throw IllegalStateException("Web Client ID (type 3) not found in google-services.json")
    }

    private fun generateNonce(): String {
        val rawNonce = UUID.randomUUID().toString()
        val bytes = rawNonce.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }
}

private fun FirebaseUser.toUser() = User(
    uid = uid,
    displayName = displayName,
    email = email,
    photoUrl = photoUrl?.toString(),
)
