package dev.pcha.foodsense.app.data.auth

/** Domain model for the signed-in user. Keeps Firebase SDK types out of the UI/ViewModel. */
data class User(
    val uid: String,
    val displayName: String?,
    val email: String?,
    val photoUrl: String?,
)
