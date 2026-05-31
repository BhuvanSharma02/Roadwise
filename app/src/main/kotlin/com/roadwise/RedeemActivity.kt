package com.roadwise

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.roadwise.databinding.ActivityRedeemBinding
import com.roadwise.utils.SessionManager

class RedeemActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRedeemBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRedeemBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setupUI()
    }

    private fun setupUI() {
        val points = SessionManager.getRewardPoints(this)
        binding.tvCurrentPoints.text = "$points pts"

        binding.btnBack.setOnClickListener {
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Add listeners for coming soon to show toast
        val comingSoonListener = android.view.View.OnClickListener {
            Toast.makeText(this, "This reward will be available soon!", Toast.LENGTH_SHORT).show()
        }

        binding.root.findViewById<android.view.View>(R.id.tvTitle1).setOnClickListener(comingSoonListener)
        binding.root.findViewById<android.view.View>(R.id.tvTitle2).setOnClickListener(comingSoonListener)
        binding.root.findViewById<android.view.View>(R.id.tvTitle3).setOnClickListener(comingSoonListener)
        binding.root.findViewById<android.view.View>(R.id.tvTitle4).setOnClickListener(comingSoonListener)
    }
}
