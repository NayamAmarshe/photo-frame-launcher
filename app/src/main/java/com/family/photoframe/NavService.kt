package com.family.photoframe

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout

/**
 * Our own tiny Back / Home / Recent apps bar, floating over everything.
 * It is an accessibility service only so Android lets it press those three buttons for us.
 * It never reads the screen. Pressing the credits four times in the app turns it on or off.
 */
class NavService : AccessibilityService() {

    companion object {
        @Volatile var instance: NavService? = null
        private const val PREFS = "nav"

        /** Android turns accessibility services off when an app is force-stopped. This switches ours back on. */
        fun ensureEnabled(ctx: Context) {
            try {
                if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return
                val me = ctx.packageName + "/" + NavService::class.java.name
                val cr = ctx.contentResolver
                val cur = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                if (!cur.split(":").contains(me)) {
                    val next = if (cur.isEmpty()) me else "$cur:$me"
                    android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
                }
                android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            } catch (e: Exception) { }
        }

        fun isOn(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)

        /** Flips the bar on or off. Returns the new state. */
        fun toggle(ctx: Context): Boolean {
            val on = !isOn(ctx)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
            instance?.show(on)
            return on
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var bar: View? = null
    private val fade = Runnable { bar?.animate()?.alpha(0.35f)?.setDuration(400)?.start() }

    override fun onServiceConnected() {
        instance = this
        show(isOn(this))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        hide()
        instance = null
        return super.onUnbind(intent)
    }

    fun show(on: Boolean) = main.post { if (on) add() else hide() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun add() {
        if (bar != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(0xB3101216.toInt()) }
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val actions = listOf(
            Pair(Icon.BACK, GLOBAL_ACTION_BACK),
            Pair(Icon.HOME, GLOBAL_ACTION_HOME),
            Pair(Icon.RECENTS, GLOBAL_ACTION_RECENTS)
        )
        for ((kind, action) in actions) {
            row.addView(IconButton(this, kind) {
                performGlobalAction(action)
                wake()
            }, LinearLayout.LayoutParams(dp(60), dp(44)))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(6) }
        try {
            wm.addView(row, lp)
            bar = row
            wake()
        } catch (e: Exception) {
            android.util.Log.d("nav", "could not show bar: $e")
        }
    }

    private fun hide() {
        main.removeCallbacks(fade)
        bar?.let { try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) { } }
        bar = null
    }

    /** Full strength while in use, then it goes see-through so it stays out of the way of photos. */
    private fun wake() {
        bar?.animate()?.cancel()
        bar?.alpha = 1f
        main.removeCallbacks(fade)
        main.postDelayed(fade, 3000)
    }

    private enum class Icon { BACK, HOME, RECENTS }

    private class IconButton(ctx: Context, private val kind: Icon, private val onTap: () -> Unit) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.2f * resources.displayMetrics.density
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }

        init {
            setOnClickListener { onTap() }
            background = GradientDrawable().apply { cornerRadius = 100f; setColor(0x00FFFFFF) }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> (background as GradientDrawable).setColor(0x33FFFFFF)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> (background as GradientDrawable).setColor(0x00FFFFFF)
            }
            return super.onTouchEvent(e)
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = 7f * resources.displayMetrics.density
            when (kind) {
                Icon.BACK -> c.drawPath(Path().apply {
                    moveTo(cx + r * 0.8f, cy - r); lineTo(cx - r * 0.9f, cy); lineTo(cx + r * 0.8f, cy + r); close()
                }, paint)
                Icon.HOME -> c.drawCircle(cx, cy, r, paint)
                Icon.RECENTS -> c.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), r * 0.3f, r * 0.3f, paint)
            }
        }
    }
}
