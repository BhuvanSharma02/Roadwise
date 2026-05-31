package com.roadwise.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * DrivingReceiver handles activity transition events (e.g., entering/exiting a vehicle).
 * Optimized to start monitoring even when the app is in the background.
 */
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * DrivingReceiver handles activity transition events (e.g., entering/exiting a vehicle).
 * Optimized to prompt the user to start monitoring when traveling is detected.
 */
class DrivingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("DrivingReceiver", "Received event: ${intent.action}")
        
        if (intent.action == "com.roadwise.ACTION_ACTIVITY_TRANSITION") {
            val prefs = context.getSharedPreferences("roadwise_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("pref_auto_start", false)) return

            // 1. Handle explicit Transition Events (High Precision)
            if (ActivityTransitionResult.hasResult(intent)) {
                val result = ActivityTransitionResult.extractResult(intent)!!
                for (event in result.transitionEvents) {
                    handleActivityEvent(context, event.activityType, event.transitionType)
                }
            } 
            
            // 2. Handle Periodic Activity Updates (Robust Fallback)
            if (ActivityRecognitionResult.hasResult(intent)) {
                val result = ActivityRecognitionResult.extractResult(intent)!!
                val mostProbable = result.mostProbableActivity
                Log.d("DrivingReceiver", "Periodic Update: ${mostProbable.type} Confidence: ${mostProbable.confidence}")
                
                if (mostProbable.type == DetectedActivity.IN_VEHICLE && mostProbable.confidence >= 75) {
                    Log.i("DrivingReceiver", "🚗 IN_VEHICLE detected via periodic update. Prompting.")
                    sendStartPromptNotification(context)
                }
            }
        }
    }

    private fun handleActivityEvent(context: Context, activityType: Int, transitionType: Int) {
        Log.d("DrivingReceiver", "Activity: $activityType, Transition: $transitionType")

        if (activityType == DetectedActivity.IN_VEHICLE) {
            if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                Log.i("DrivingReceiver", "🚗 IN_VEHICLE ENTER detected. Sending prompt notification.")
                sendStartPromptNotification(context)
            } else if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                Log.i("DrivingReceiver", "🚶 IN_VEHICLE EXIT detected. Stopping monitoring.")
                context.stopService(Intent(context, DriveGuardService::class.java))
            }
        } else if (activityType == DetectedActivity.STILL && transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
            // If we stop being still, we might be starting to move. 
            // We don't prompt yet, but we've successfully woken up the receiver.
            Log.d("DrivingReceiver", "App woke up from STILL. Monitoring for driving.")
        }
    }

    private fun sendStartPromptNotification(context: Context) {
        val channelId = "drive_detection_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Driving Detection",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Prompts to start monitoring when driving is detected"
                enableLights(true)
                lightColor = Color.BLUE
            }
            notificationManager.createNotificationChannel(channel)
        }

        val startIntent = Intent(context, DriveGuardService::class.java).apply {
            action = "START_SERVICE"
        }
        
        val startPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context, 1, startIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                context, 1, startIntent, PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Driving Detected")
            .setContentText("Would you like to start RoadWise monitoring?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(androidx.core.content.ContextCompat.getColor(context, com.roadwise.R.color.emerald_neon))
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(com.roadwise.R.drawable.ic_drive, "Start Monitoring", startPendingIntent)
            .build()

        notificationManager.notify(2001, notification)
    }
}
