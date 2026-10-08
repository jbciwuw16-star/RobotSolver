package com.robot.solver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class RobotView(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    var busy = false
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h)
        val accent = Color.parseColor(if (busy) "#FBBF24" else "#22D3EE")

        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#E61F2937")
        cv.drawCircle(w / 2, h / 2, s / 2 - 1.5f * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.strokeCap = Paint.Cap.ROUND
        p.color = accent
        cv.drawCircle(w / 2, h / 2, s / 2 - 1.5f * d, p)

        cv.drawLine(w / 2, h * 0.30f, w / 2, h * 0.21f, p)
        p.style = Paint.Style.FILL
        cv.drawCircle(w / 2, h * 0.19f, 3 * d, p)
        cv.drawRoundRect(RectF(w * 0.17f, h * 0.46f, w * 0.25f, h * 0.60f), 2 * d, 2 * d, p)
        cv.drawRoundRect(RectF(w * 0.75f, h * 0.46f, w * 0.83f, h * 0.60f), 2 * d, 2 * d, p)

        p.color = Color.parseColor("#F3F4F6")
        cv.drawRoundRect(RectF(w * 0.26f, h * 0.30f, w * 0.74f, h * 0.74f), s * 0.12f, s * 0.12f, p)
        p.color = Color.parseColor("#111827")
        cv.drawRoundRect(RectF(w * 0.32f, h * 0.40f, w * 0.68f, h * 0.58f), s * 0.07f, s * 0.07f, p)

        p.color = accent
        cv.drawCircle(w * 0.42f, h * 0.49f, s * 0.045f, p)
        cv.drawCircle(w * 0.58f, h * 0.49f, s * 0.045f, p)

        p.color = Color.parseColor("#9CA3AF")
        cv.drawRoundRect(RectF(w * 0.40f, h * 0.64f, w * 0.60f, h * 0.67f), 2 * d, 2 * d, p)
    }
}

class PowerView(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    var on = true
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h)
        val col = Color.parseColor(if (on) "#22C55E" else "#9CA3AF")

        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#E6111827")
        cv.drawCircle(w / 2, h / 2, s / 2 - 1f * d, p)

        p.style = Paint.Style.STROKE
        p.color = col
        p.strokeWidth = 1.5f * d
        cv.drawCircle(w / 2, h / 2, s / 2 - 1.5f * d, p)

        p.strokeWidth = s * 0.09f
        p.strokeCap = Paint.Cap.ROUND
        val r = s * 0.22f
        val cx = w / 2
        val cy = h / 2 + s * 0.02f
        cv.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 300f, false, p)
        cv.drawLine(cx, cy - r * 1.25f, cx, cy - r * 0.05f, p)
    }
}
