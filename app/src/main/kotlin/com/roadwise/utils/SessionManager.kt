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
    private const val KEY_TOTAL_DISTANCE_METERS = "total_distance_meters"
    private const val KEY_REWARD_POINTS = "reward_points"

    // ── Admin email list (hardcoded fallback for super-admins) ──────────────
    private val SUPER_ADMINS = setOf(
        "admin@roadwise.com",
        "admin2@gmail.com"
    )

    fun login(context: Context, email: String, displayName: String?, isAdmin: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOGGED_IN,  true)
            .putBoolean(KEY_IS_ADMIN,   isAdmin || SUPER_ADMINS.contains(email.lowercase().trim()))
            .putString(KEY_USER_EMAIL,  email)
            .putString(KEY_USER_NAME,   displayName ?: email.substringBefore("@"))
            .apply()
    }

    /**
     * Checks if the user is an admin by querying the 'admins' collection in Firestore.
     */
    fun checkAdminStatus(email: String, onComplete: (Boolean) -> Unit) {
        if (SUPER_ADMINS.contains(email.lowercase().trim())) {
            onComplete(true)
            return
        }

        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("admins")
            .document(email.lowercase().trim())
            .get()
            .addOnSuccessListener { document ->
                // If document exists, they are an admin
                onComplete(document.exists())
            }
            .addOnFailureListener {
                onComplete(false)
            }
    }

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOGGED_IN, false)
            .putBoolean(KEY_IS_ADMIN,  false)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_NAME)
            .commit()
        PotholeRepository.clearCache()
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

    fun addDistance(context: Context, distanceMeters: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentDistance = prefs.getFloat(KEY_TOTAL_DISTANCE_METERS, 0f)
        val newDistance = currentDistance + distanceMeters
        
        // Calculate points: 1 point per 1 km (1000 meters)
        val currentPoints = prefs.getInt(KEY_REWARD_POINTS, 0)
        // Only add points if we crossed a km boundary
        val oldKm = (currentDistance / 1000).toInt()
        val newKm = (newDistance / 1000).toInt()
        val pointsToAdd = newKm - oldKm
        
        prefs.edit()
            .putFloat(KEY_TOTAL_DISTANCE_METERS, newDistance)
            .putInt(KEY_REWARD_POINTS, currentPoints + pointsToAdd)
            .apply()
    }

    fun getTotalDistanceMeters(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_TOTAL_DISTANCE_METERS, 0f)

    fun getRewardPoints(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_REWARD_POINTS, 0)

    fun redeemPoints(context: Context, pointsToRedeem: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentPoints = prefs.getInt(KEY_REWARD_POINTS, 0)
        if (currentPoints >= pointsToRedeem) {
            prefs.edit().putInt(KEY_REWARD_POINTS, currentPoints - pointsToRedeem).apply()
            return true
        }
        return false
    }
}
