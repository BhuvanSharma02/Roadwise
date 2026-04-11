package com.roadwise

import android.content.Context
import android.content.Intent
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.roadwise.databinding.ActivitySettingsBinding
import com.roadwise.utils.PotholeRepository
import com.roadwise.services.DriveGuardService
import android.app.ActivityManager
import android.util.Log
import com.google.android.gms.location.*
import android.app.PendingIntent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("roadwise_prefs", Context.MODE_PRIVATE)

        // ─────────────────────────────────────────────
        // APPEARANCE — Theme
        // ─────────────────────────────────────────────
        val savedTheme = prefs.getString("pref_theme", "auto")
        when (savedTheme) {
            "light" -> binding.chipThemeLight.isChecked = true
            "dark"  -> binding.chipThemeDark.isChecked = true
            else    -> binding.chipThemeAuto.isChecked = true
        }
        binding.themeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when {
                checkedIds.contains(R.id.chipThemeLight) -> {
                    prefs.edit().putString("pref_theme", "light").apply()
                    AppCompatDelegate.MODE_NIGHT_NO
                }
                checkedIds.contains(R.id.chipThemeDark) -> {
                    prefs.edit().putString("pref_theme", "dark").apply()
                    AppCompatDelegate.MODE_NIGHT_YES
                }
                else -> {
                    prefs.edit().putString("pref_theme", "auto").apply()
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        // ─────────────────────────────────────────────
        // DETECTION ENGINE — Background + Sensitivity
        // ─────────────────────────────────────────────
        binding.switchBackground.isChecked = prefs.getBoolean("pref_background_detection", true)
        binding.switchBackground.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_background_detection", isChecked).apply()
        }

        binding.switchBackgroundService.isChecked = isServiceRunning(DriveGuardService::class.java)
        binding.switchBackgroundService.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_background_service", isChecked).apply()
            val intent = Intent(this, DriveGuardService::class.java)
            if (isChecked) {
                startForegroundService(intent)
            } else {
                stopService(intent)
            }
        }

        binding.switchAutoStart.isChecked = prefs.getBoolean("pref_auto_start", false)
        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_auto_start", isChecked).apply()
            if (isChecked) {
                requestActivityTransitions()
            } else {
                removeActivityTransitions()
            }
        }

        binding.sliderSensitivity.value = prefs.getFloat("pref_sensitivity_index", 1.0f)
        updateSensitivityLabel(prefs.getFloat("pref_sensitivity_index", 1.0f))
        binding.sliderSensitivity.addOnChangeListener { _, value, _ ->
            prefs.edit().putFloat("pref_sensitivity_index", value).apply()
            val threshold = when (value) {
                0.0f -> 6.0f   // Reactive — only big jolts
                1.0f -> 3.8f   // Balanced
                2.0f -> 2.2f   // Proactive — catches smaller bumps
                else -> 3.8f
            }
            prefs.edit().putFloat("pref_sensor_threshold", threshold).apply()
            updateSensitivityLabel(value)
        }

        // ─────────────────────────────────────────────
        // RESOURCE MANAGEMENT — Battery Saver
        // ─────────────────────────────────────────────
        binding.switchBattery.isChecked = prefs.getBoolean("pref_battery_saver", false)
        binding.switchBattery.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_battery_saver", isChecked).apply()
        }

        binding.switchVoiceAlerts.isChecked = prefs.getBoolean("pref_voice_alerts", true)
        binding.switchVoiceAlerts.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_voice_alerts", isChecked).apply()
        }

        // ─────────────────────────────────────────────
        // RESOURCE MANAGEMENT — Audio Alerts
        // ─────────────────────────────────────────────
        binding.sliderAudio.value = prefs.getFloat("pref_audio_alerts", 65.0f)
        updateAudioLabel(prefs.getFloat("pref_audio_alerts", 65.0f))
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (_: Exception) { }

        binding.sliderAudio.addOnChangeListener { _, value, fromUser ->
            prefs.edit().putFloat("pref_audio_alerts", value).apply()
            updateAudioLabel(value)
            if (fromUser && value > 0f) {
                // Play a short preview tone scaled to the slider volume
                val vol = (value / 100f * 100).toInt().coerceIn(1, 100)
                try {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                } catch (_: Exception) { }
            }
        }

        // ─────────────────────────────────────────────
        // DATA MANAGEMENT
        // ─────────────────────────────────────────────
        refreshDataStats()

        binding.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear All History?")
                .setMessage("This will permanently delete all ${PotholeRepository.getAllPotholes(this).size} detection records and their photos. This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    PotholeRepository.clearAll(this)
                    refreshDataStats()
                    android.widget.Toast.makeText(this, "History cleared.", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ─────────────────────────────────────────────
        // ABOUT
        // ─────────────────────────────────────────────
        binding.tvVersion.text = BuildConfig.VERSION_NAME

        binding.btnShareApp.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Check out RoadWise!")
                putExtra(Intent.EXTRA_TEXT, "I've been using RoadWise to detect potholes and map road conditions in real time. Check it out!")
            }
            startActivity(Intent.createChooser(shareIntent, "Share RoadWise via"))
        }

        setupNavigation()
    }

    private fun updateSensitivityLabel(value: Float) {
        val (threshold, label) = when (value) {
            0.0f -> Pair(6.0f, "Reactive")
            1.0f -> Pair(3.8f, "Balanced")
            2.0f -> Pair(2.2f, "Proactive")
            else -> Pair(3.8f, "Balanced")
        }
        binding.tvSensitivityLabel.text = "Threshold: ${threshold}g ($label)"
    }

    private fun updateAudioLabel(value: Float) {
        val pct = value.toInt()
        val label = when {
            pct == 0   -> "Muted"
            pct < 40   -> "$pct% — Quiet"
            pct < 75   -> "$pct% — Normal"
            else       -> "$pct% — Loud"
        }
        binding.tvAudioLabel.text = label
    }

    private fun refreshDataStats() {
        val count = PotholeRepository.getAllPotholes(this).size
        binding.tvRecordCount.text = count.toString()

        val bytes = PotholeRepository.getStorageSizeBytes(this)
        binding.tvStorageSize.text = when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun requestActivityTransitions() {
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
        val intent = Intent(this, com.roadwise.services.DrivingReceiver::class.java).apply {
            action = "com.roadwise.ACTION_ACTIVITY_TRANSITION"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        try {
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(request, pendingIntent)
        } catch (e: SecurityException) {
            Log.e("Settings", "Activity Recognition permission missing", e)
        }
    }

    private fun removeActivityTransitions() {
        val intent = Intent(this, com.roadwise.services.DrivingReceiver::class.java).apply {
            action = "com.roadwise.ACTION_ACTIVITY_TRANSITION"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        ActivityRecognition.getClient(this).removeActivityTransitionUpdates(pendingIntent)
    }

    private fun setupNavigation() {
        binding.navDrive.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        binding.navAlerts.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.navSettings.setOnClickListener { /* already here */ }
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator?.release()
        toneGenerator = null
    }
}
