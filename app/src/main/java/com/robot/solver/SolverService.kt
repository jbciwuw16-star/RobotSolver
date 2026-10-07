package com.robot.solver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*

class SolverService : AccessibilityService() {

    private data class Item(val text: String, val rect: Rect, val node: AccessibilityNodeInfo)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var cut: CutTool
    private lateinit var box: LinearLayout
    private lateinit var solveBtn: Button
    private lateinit var cutBtn: Button
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var toggle: TextView
    private lateinit var tParams: WindowManager.LayoutParams
    private var isOn = true
    private var busy = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        cut = CutTool(this, wm)
        showOverlay()
        showToggle()
    }

    override fun onDestroy() {
        scope.cancel()
        if (::box.isInitialized) runCatching { wm.removeView(box) }
        if (::toggle.isInitialized) runCatching { wm.removeView(toggle) }
        if (::cut.isInitialized) cut.closeAll()
        super.onDestroy()
    }

    private fun showOverlay() {
        val d = resources.displayMetrics.density
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        solveBtn = Button(this).apply {
            text = "SOLVE"
            visibility = View.GONE
            setOnClickListener { solve() }
        }
        cutBtn = Button(this).apply {
            text = "CUT"
            visibility = View.GONE
            setOnClickListener {
                solveBtn.visibility = View.GONE
                visibility = View.GONE
                cut.start()
            }
        }
        val robot = TextView(this).apply {
            text = "🤖"
            textSize = 28f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC222222"))
            }
        }
        box.addView(robot, LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()))
        box.addView(solveBtn)
        box.addView(cutBtn)

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
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    if (moved) {
                        params.x = ox + dx
                        params.y = oy + dy
                        wm.updateViewLayout(box, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    val v = if (solveBtn.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    solveBtn.visibility = v
                    cutBtn.visibility = v
                }
            }
            true
        }
        wm.addView(box, params)
    }

    private fun showToggle() {
        val d = resources.displayMetrics.density
        toggle = TextView(this).apply {
            text = "⏻"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.6f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#99000000"))
            }
        }
        tParams = WindowManager.LayoutParams(
            (32 * d).toInt(), (32 * d).toInt(),
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
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
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
            solveBtn.visibility = View.GONE
            cutBtn.visibility = View.GONE
            cut.closeAll()
        }
        box.visibility = if (on) View.VISIBLE else View.GONE
        toggle.alpha = if (on) 0.6f else 0.3f
    }

    private fun collect(): List<Item> {
        val out = mutableListOf<Item>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty() && n.isVisibleToUser) {
                val r = Rect()
                n.getBoundsInScreen(r)
                out += Item(t.take(400), r, n)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return out
    }

    private fun solve() {
        if (busy) return
        val items = collect()
        if (items.isEmpty()) { toast("Soal nggak kebaca"); return }

        val prompt = buildString {
            append("Berikut semua teks di layar sebuah kuis, bernomor.\n")
            append("Cari soalnya, tentukan jawaban yang BENAR, lalu balas HANYA dengan ")
            append("satu angka: nomor elemen pilihan jawaban yang benar. Tanpa teks lain.\n\n")
            items.forEachIndexed { i, it -> append("$i: ${it.text}\n") }
        }
        val p = getSharedPreferences("k", MODE_PRIVATE)
        val keys = mapOf(
            "gemini" to (p.getString("gemini", "") ?: ""),
            "groq" to (p.getString("groq", "") ?: ""),
            "openrouter" to (p.getString("openrouter", "") ?: "")
        )
        if (keys.values.all { it.isBlank() }) { toast("Isi API key dulu"); return }

        busy = true
        toast("Mikir...")
        scope.launch {
            val idx = AiClient.vote(prompt, keys)
            busy = false
            if (idx == null || idx !in items.indices) {
                toast("AI gagal jawab")
                return@launch
            }
            toast("Jawaban: ${items[idx].text.take(40)}")
            delay(300)
            tap(items[idx])
        }
    }

    private fun tap(item: Item) {
        var n: AccessibilityNodeInfo? = item.node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return

        val path = Path().apply { moveTo(item.rect.exactCenterX(), item.rect.exactCenterY()) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null
        )
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
