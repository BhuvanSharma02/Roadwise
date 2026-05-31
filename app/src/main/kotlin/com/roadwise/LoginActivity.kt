package com.roadwise

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding.btnBack.setOnClickListener { 
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else false
        }

        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.btnSignUp.setOnClickListener { attemptSignUp() }
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
                    val firebaseUser = auth.currentUser
                    val userEmail = firebaseUser?.email ?: email

                    // 1. Check Firestore for Admin Status
                    SessionManager.checkAdminStatus(userEmail) { isFirestoreAdmin ->
                        
                        // 2. Also check Token Claims for secondary verification
                        firebaseUser?.getIdToken(false)?.addOnCompleteListener { tokenTask ->
                            setLoading(false)
                            
                            val isAdminClaim = tokenTask.result
                                ?.claims?.get("admin") as? Boolean ?: false
                            
                            val finalAdminStatus = isFirestoreAdmin || isAdminClaim

                            SessionManager.login(
                                context = this,
                                email = userEmail,
                                displayName = firebaseUser.displayName,
                                isAdmin = finalAdminStatus
                            )

                            // Return result to MainActivity
                            setResult(Activity.RESULT_OK, Intent().apply {
                                putExtra("is_admin", finalAdminStatus)
                            })
                            finish()
                        }
                    }
                } else {
                    setLoading(false)
                    val msg = task.exception?.message ?: "Authentication failed"
                    showError(msg)
                }
            }
    }

    private fun attemptSignUp() {
        val email    = binding.etEmail.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""

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

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val userEmail = auth.currentUser?.email ?: email
                    
                    // New users are standard users by default
                    SessionManager.login(
                        context = this,
                        email = userEmail,
                        displayName = null,
                        isAdmin = false
                    )
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    setLoading(false)
                    showError(task.exception?.message ?: "Registration failed")
                }
            }
    }

    private fun setLoading(loading: Boolean) {
        binding.loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled        = !loading
        binding.btnSignUp.isEnabled       = !loading
    }

    private fun showError(message: String) {
        binding.tvError.text       = message
        binding.tvError.visibility = View.VISIBLE
    }
}
