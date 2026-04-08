package com.roadwise

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.roadwise.databinding.ActivityLoginBinding
import com.roadwise.utils.SessionManager

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else false
        }

        binding.btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val email    = binding.etEmail.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""

        // Basic validation
        if (email.isEmpty()) {
            binding.emailLayout.error = "Email is required"
            return
        }
        if (password.length < 6) {
            binding.passwordLayout.error = "Password must be ≥ 6 characters"
            return
        }
        binding.emailLayout.error    = null
        binding.passwordLayout.error = null
        binding.tvError.visibility   = View.GONE

        setLoading(true)

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Fetch ID token to read custom claims (admin flag)
                    auth.currentUser?.getIdToken(true)?.addOnCompleteListener { tokenTask ->
                        setLoading(false)
                        val isAdminClaim = tokenTask.result
                            ?.claims?.get("admin") as? Boolean ?: false

                        SessionManager.login(
                            context     = this,
                            email       = email,
                            displayName = auth.currentUser?.displayName,
                            isAdminClaim = isAdminClaim
                        )

                        // Return result to MainActivity so it can update the nav immediately
                        setResult(Activity.RESULT_OK, Intent().apply {
                            putExtra("is_admin", SessionManager.isAdmin(this@LoginActivity))
                        })
                        finish()
                    }
                } else {
                    setLoading(false)
                    val msg = task.exception?.message ?: "Authentication failed"
                    showError(msg)
                }
            }
    }

    private fun setLoading(loading: Boolean) {
        binding.loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled        = !loading
    }

    private fun showError(message: String) {
        binding.tvError.text       = message
        binding.tvError.visibility = View.VISIBLE
    }
}
