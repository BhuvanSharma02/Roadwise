package com.roadwise.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.roadwise.utils.ActivityTransitionHelper

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("roadwise_prefs", Context.MODE_PRIVATE)
            val autoStartEnabled = prefs.getBoolean("pref_auto_start", false)

            if (autoStartEnabled) {
                ActivityTransitionHelper.requestTransitions(context)
            }
        }
    }
}
