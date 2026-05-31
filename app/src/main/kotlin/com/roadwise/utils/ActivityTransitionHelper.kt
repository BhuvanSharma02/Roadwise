package com.roadwise.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.location.*
import com.roadwise.services.DrivingReceiver

object ActivityTransitionHelper {

    private const val ACTION_ACTIVITY_TRANSITION = "com.roadwise.ACTION_ACTIVITY_TRANSITION"

    fun requestTransitions(context: Context) {
        val transitions = mutableListOf<ActivityTransition>()
        
        // 🚗 Driving detection
        transitions.add(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        )
        transitions.add(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )

        // 🚶 Still/Walking exit (to trigger checks)
        transitions.add(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )

        val request = ActivityTransitionRequest(transitions)
        val intent = Intent(context, DrivingReceiver::class.java).apply {
            action = ACTION_ACTIVITY_TRANSITION
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context, 
            0, 
            intent, 
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        try {
            val client = ActivityRecognition.getClient(context)
            
            // 1. Request Transition Updates (Event-based)
            client.requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener {
                    Log.d("TransitionHelper", "Successfully registered activity transitions")
                }
                .addOnFailureListener { e ->
                    Log.e("TransitionHelper", "Failed to register activity transitions", e)
                }
            
            // 2. Supplemental Activity Updates (Periodic fallback)
            // Request updates every 2 minutes when "auto-start" is on.
            // This ensures we don't miss transitions due to system deep-sleep.
            client.requestActivityUpdates(120000L, pendingIntent)
                .addOnSuccessListener {
                    Log.d("TransitionHelper", "Successfully registered periodic activity updates")
                }
        } catch (e: SecurityException) {
            Log.e("TransitionHelper", "Permission missing for Activity Recognition", e)
        }
    }

    fun removeTransitions(context: Context) {
        val intent = Intent(context, DrivingReceiver::class.java).apply {
            action = ACTION_ACTIVITY_TRANSITION
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val client = ActivityRecognition.getClient(context)
        client.removeActivityTransitionUpdates(pendingIntent)
        client.removeActivityUpdates(pendingIntent)
    }
}
