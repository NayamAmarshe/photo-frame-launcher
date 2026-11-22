package com.family.photoframe

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * A small "Back to Photo Frame" button that floats on top of other apps (a music app, Android
 * settings...). The tablet has no buttons of its own, so this is the way home.
 *
 * It appears when the frame opens another app, and disappears when the frame is back on screen.
 * You can drag it out of the way.
 */
class BackOverlayService : Service() {

    private var wm: WindowManager? = null
    private var button: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        show()
        return START_NOT_STICKY
    }

    private fun show() {
        if (button != null) return
        // Android makes the user switch on "Draw over other apps" once. Without it, do nothing.
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val dp = resources.displayMetrics.density

        val label = TextView(this).apply {
            text = "←  Back to Photo Frame"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#2A1A00"))
            setPadding((22 * dp).toInt(), (14 * dp).toInt(), (22 * dp).toInt(), (14 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2FFB454"))
                cornerRadius = 30 * dp
                setStroke((2 * dp).toInt(), Color.parseColor("#66000000"))
            }
            elevation = 8 * dp
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * dp).toInt()
            y = (140 * dp).toInt()
        }

        // Tap = go back. Drag = move the button.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        label.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (Math.abs(dx) > 12 * dp || Math.abs(dy) > 12 * dp) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try { manager.updateViewLayout(label, params) } catch (ex: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) goBackToFrame()
                    true
                }
                else -> false
            }
        }

        try {
            manager.addView(label, params)
            button = label
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun goBackToFrame() {
        val back = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(back)
        stopSelf()
    }

    override fun onDestroy() {
        button?.let { try { wm?.removeView(it) } catch (e: Exception) { } }
        button = null
        super.onDestroy()
    }
}
