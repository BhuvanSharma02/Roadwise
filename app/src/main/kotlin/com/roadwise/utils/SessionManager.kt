package com.roadwise.utils

import android.content.Context

/**
 * Manages authentication state and role persistence across sessions.
 * Admin role is determined by a hardcoded list of email addresses for easy testing,
 * falling back to Firebase Custom Claims when available.
 */
object SessionManager {

    private const val PREFS_NAME = "roadwise_session"
    private const val KEY_LOGGED_IN  = "is_logged_in"
    private const val KEY_IS_ADMIN   = "is_admin"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_NAME  = "user_name"

    // ── Admin email list (hardcoded fallback for easy testing) ──────────────
    // Add the Firebase email accounts that should have admin access here.
    private val ADMIN_EMAILS = setOf(
        "admin@roadwise.com",
        "roadwise.admin@gmail.com"
    )

    fun login(context: Context, email: String, displayName: String?, isAdminClaim: Boolean) {
        val isAdmin = isAdminClaim || ADMIN_EMAILS.contains(email.lowercase().trim())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOGGED_IN,  true)
            .putBoolean(KEY_IS_ADMIN,   isAdmin)
            .putString(KEY_USER_EMAIL,  email)
            .putString(KEY_USER_NAME,   displayName ?: email.substringBefore("@"))
            .apply()
    }

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOGGED_IN, false)
            .putBoolean(KEY_IS_ADMIN,  false)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_NAME)
            .apply()
        com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
    }

    fun isLoggedIn(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOGGED_IN, false)

    fun isAdmin(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_ADMIN, false)

    fun getUserEmail(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USER_EMAIL, "") ?: ""

    fun getUserName(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USER_NAME, "User") ?: "User"
}
