package com.roadwise

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.roadwise.databinding.ActivityAccountBinding
import com.roadwise.utils.SessionManager

class AccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setupUI()
    }

    private fun setupUI() {
        binding.tvUserName.text = SessionManager.getUserName(this)
        binding.tvUserEmail.text = SessionManager.getUserEmail(this)
        
        updateRewardsUI()

        binding.btnRedeem.setOnClickListener {
            val intent = android.content.Intent(this, RedeemActivity::class.java)
            startActivity(intent)
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        binding.btnBack.setOnClickListener {
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        binding.btnLogout.setOnClickListener {
            showLogoutDialog()
        }
    }

    private fun updateRewardsUI() {
        val totalMeters = SessionManager.getTotalDistanceMeters(this)
        val totalKm = totalMeters / 1000f
        val points = SessionManager.getRewardPoints(this)

        binding.tvDistanceValue.text = String.format("%.1f km", totalKm)
        binding.tvPointsValue.text = points.toString()
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to log out of RoadWise?")
            .setPositiveButton("Logout") { _, _ ->
                SessionManager.logout(this)
                finish()
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
