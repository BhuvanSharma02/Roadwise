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
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener {
                    Log.d("TransitionHelper", "Successfully registered activity transitions")
                }
                .addOnFailureListener { e ->
                    Log.e("TransitionHelper", "Failed to register activity transitions", e)
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
        ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent)
    }
}
