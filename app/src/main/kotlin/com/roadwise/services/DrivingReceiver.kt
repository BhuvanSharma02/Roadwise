package com.roadwise.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * DrivingReceiver handles activity transition events (e.g., entering/exiting a vehicle).
 */
class DrivingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.roadwise.ACTION_ACTIVITY_TRANSITION") {
            if (ActivityTransitionResult.hasResult(intent)) {
                val result = ActivityTransitionResult.extractResult(intent)!!
                for (event in result.transitionEvents) {
                    val activityType = event.activityType
                    val transitionType = event.transitionType

                    Log.d("DrivingReceiver", "Transition: $activityType, Type: $transitionType")

                    val prefs = context.getSharedPreferences("roadwise_prefs", Context.MODE_PRIVATE)
                    val autoStartEnabled = prefs.getBoolean("pref_auto_start", false)

                    if (!autoStartEnabled) return

                    if (activityType == DetectedActivity.IN_VEHICLE) {
                        if (transitionType == 0) { // ENTER
                            Log.d("DrivingReceiver", "User entered vehicle. Starting service.")
                            context.startForegroundService(Intent(context, DriveGuardService::class.java))
                        } else { // EXIT
                            Log.d("DrivingReceiver", "User exited vehicle. Stopping service.")
                            context.stopService(Intent(context, DriveGuardService::class.java))
                        }
                    }
                }
            }
        }
    }
}
