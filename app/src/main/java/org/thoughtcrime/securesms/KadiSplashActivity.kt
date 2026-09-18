/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * Cold-start intro. This is a normal activity (not the Android 12 splash API and not
 * MainActivity), so the logo and Arabic type can actually draw.
 */
class KadiSplashActivity : BaseActivity() {

  private val handler = Handler(Looper.getMainLooper())
  private var launched = false

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen().setOnExitAnimationListener { splashView -> splashView.remove() }
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) {
      continueToApp()
      return
    }

    setContentView(R.layout.activity_kadi_splash)
    playIntro()
    handler.postDelayed({ continueToApp() }, INTRO_MS)
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    super.onDestroy()
  }

  private fun playIntro() {
    val logo = findViewById<View>(R.id.kadi_splash_logo)
    val sparkle = findViewById<View>(R.id.kadi_splash_sparkle)
    val ripple = findViewById<View>(R.id.kadi_splash_ripple)
    val wordmark = findViewById<View>(R.id.kadi_splash_wordmark)
    val tagline = findViewById<View>(R.id.kadi_splash_tagline)

    logo.scaleX = 0.90f
    logo.scaleY = 0.90f
    sparkle.alpha = 0f
    sparkle.scaleX = 0.4f
    sparkle.scaleY = 0.4f
    ripple.alpha = 0f
    ripple.scaleX = 0.72f
    ripple.scaleY = 0.72f
    wordmark.translationY = 8f * resources.displayMetrics.density
    tagline.translationY = 8f * resources.displayMetrics.density

    val interpolator = AccelerateDecelerateInterpolator()

    logo.animate()
      .scaleX(1.05f)
      .scaleY(1.05f)
      .setDuration(280)
      .setInterpolator(interpolator)
      .withEndAction {
        logo.animate()
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(140)
          .setInterpolator(interpolator)
          .start()
      }
      .start()

    sparkle.animate()
      .alpha(1f)
      .scaleX(1.12f)
      .scaleY(1.12f)
      .setStartDelay(300)
      .setDuration(220)
      .setInterpolator(interpolator)
      .withEndAction {
        sparkle.animate()
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(100)
          .start()
      }
      .start()

    ripple.animate()
      .alpha(0.4f)
      .scaleX(1.28f)
      .scaleY(1.28f)
      .setStartDelay(300)
      .setDuration(280)
      .setInterpolator(interpolator)
      .withEndAction {
        ripple.animate()
          .alpha(0f)
          .setDuration(280)
          .start()
      }
      .start()

    wordmark.animate()
      .translationY(0f)
      .setStartDelay(200)
      .setDuration(320)
      .setInterpolator(DecelerateInterpolator())
      .start()

    tagline.animate()
      .translationY(0f)
      .setStartDelay(260)
      .setDuration(320)
      .setInterpolator(DecelerateInterpolator())
      .start()
  }

  private fun continueToApp() {
    if (launched || isFinishing) {
      return
    }
    launched = true

    val next = Intent(this, MainActivity::class.java).apply {
      data = intent.data
      intent.extras?.let { putExtras(it) }
    }
    startActivity(next)
    finish()
    @Suppress("DEPRECATION")
    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
  }

  companion object {
    private const val INTRO_MS = 1800L
  }
}
