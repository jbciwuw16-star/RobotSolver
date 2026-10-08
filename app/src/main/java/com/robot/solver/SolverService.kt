package com.robot.solver

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView

class SolverService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var cut: CutTool
    private lateinit var box: LinearLayout
    private lateinit var robot: RobotView
    private lateinit var cutBtn: TextView
    private lateinit var pakarBtn: TextView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var toggle: PowerView
    private lateinit var tParams: WindowManager.LayoutParams
    private var d = 1f
    private var isOn = true

    private val watchdog = object : Runnable {
        override fun run() {
            runCatching {
                if (::box.isInitialized && !box.isAttachedToWindow) wm.addView(box, params)
                if (::toggle.isInitialized && !toggle.isAttachedToWindow) wm.addView(toggle, tParams)
            }
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        d = resources.displayMetrics.density
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (::box.isInitialized) runCatching { wm.removeView(box) }
        if (::toggle.isInitialized) runCatching { wm.removeView(toggle) }
        if (::cut.isInitialized) cut.destroy()
        cut = CutTool(this, wm)
        isOn = true
        showOverlay()
        showToggle()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 10_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        if (::box.isInitialized) runCatching { wm.removeView(box) }
        if (::toggle.isInitialized) runCatching { wm.removeView(toggle) }
        if (::cut.isInitialized) cut.destroy()
        super.onDestroy()
    }

    private fun chip(label: String, color: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding((18 * d).toInt(), (9 * d).toInt(), (18 * d).toInt(), (9 * d).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 22 * d
            setColor(Color.parseColor(color))
        }
        visibility = View.GONE
        setOnClickListener { onClick() }
    }

    private fun menu(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        cutBtn.visibility = v
        pakarBtn.visibility = v
    }

    private fun showOverlay() {
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        robot = RobotView(this)
        cutBtn = chip("CUT", "#2563EB") {
            menu(false)
            cut.start(false)
        }
        pakarBtn = chip("PAKAR", "#7C3AED") {
            menu(false)
            cut.start(true)
        }
        box.addView(robot, LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()))
        for (b in listOf(cutBtn, pakarBtn)) {
            box.addView(b, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * d).toInt() })
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * d).toInt()
            y = (240 * d).toInt()
        }

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        robot.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = params.x; oy = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) > 10 * d || Math.abs(dy) > 10 * d) moved = true
                    if (moved) {
                        params.x = ox + dx
                        params.y = oy + dy
                        wm.updateViewLayout(box, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) menu(cutBtn.visibility != View.VISIBLE)
            }
            true
        }
        wm.addView(box, params)
    }

    private fun showToggle() {
        toggle = PowerView(this).apply { alpha = 0.75f }
        tParams = WindowManager.LayoutParams(
            (36 * d).toInt(), (36 * d).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (120 * d).toInt()
        }

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        toggle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = tParams.x; oy = tParams.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) > 10 * d || Math.abs(dy) > 10 * d) moved = true
                    if (moved) {
                        tParams.x = ox + dx
                        tParams.y = oy + dy
                        wm.updateViewLayout(toggle, tParams)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) setPower(!isOn)
            }
            true
        }
        wm.addView(toggle, tParams)
    }

    private fun setPower(on: Boolean) {
        isOn = on
        if (!on) {
            menu(false)
            cut.closeAll()
        }
        box.visibility = if (on) View.VISIBLE else View.GONE
        toggle.on = on
        toggle.alpha = if (on) 0.75f else 0.45f
    }
}
