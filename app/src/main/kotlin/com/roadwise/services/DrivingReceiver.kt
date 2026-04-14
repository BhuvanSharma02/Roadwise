package com.roadwise.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * DrivingReceiver handles activity transition events (e.g., entering/exiting a vehicle).
 * Optimized to start monitoring even when the app is in the background.
 */
class DrivingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("DrivingReceiver", "Received event: ${intent.action}")
        
        if (intent.action == "com.roadwise.ACTION_ACTIVITY_TRANSITION") {
            if (ActivityTransitionResult.hasResult(intent)) {
                val result = ActivityTransitionResult.extractResult(intent)!!
                for (event in result.transitionEvents) {
                    val activityType = event.activityType
                    val transitionType = event.transitionType

                    Log.d("DrivingReceiver", "Activity: $activityType, Transition: $transitionType")

                    val prefs = context.getSharedPreferences("roadwise_prefs", Context.MODE_PRIVATE)
                    val autoStartEnabled = prefs.getBoolean("pref_auto_start", false)

                    if (!autoStartEnabled) {
                        Log.d("DrivingReceiver", "Auto-start is disabled in settings. Ignoring.")
                        return
                    }

                    if (activityType == DetectedActivity.IN_VEHICLE) {
                        if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                            Log.i("DrivingReceiver", "🚗 IN_VEHICLE ENTER detected. Starting DriveGuardService.")
                            try {
                                context.startForegroundService(Intent(context, DriveGuardService::class.java))
                            } catch (e: Exception) {
                                Log.e("DrivingReceiver", "Failed to start foreground service: ${e.message}")
                            }
                        } else if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                            Log.i("DrivingReceiver", "🚶 IN_VEHICLE EXIT detected. Stopping monitoring.")
                            context.stopService(Intent(context, DriveGuardService::class.java))
                        }
                    }
                }
            } else {
                Log.w("DrivingReceiver", "Received intent but no transition result found.")
            }
        }
    }
}
