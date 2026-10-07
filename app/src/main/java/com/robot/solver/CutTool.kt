package com.robot.solver

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import kotlinx.coroutines.*
import java.net.URLEncoder

class CutView(c: Context) : View(c) {
    val rect = RectF()
    private val d = resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3 * d
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN }
    private var mode = 0
    private var lx = 0f
    private var ly = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (rect.isEmpty) rect.set(w * 0.08f, h * 0.35f, w * 0.92f, h * 0.55f)
    }

    override fun onDraw(c: Canvas) {
        c.save()
        c.clipOutRect(rect)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        c.restore()
        c.drawRect(rect, line)
        for ((x, y) in corners()) c.drawCircle(x, y, 9 * d, dot)
    }

    private fun corners() = listOf(
        rect.left to rect.top, rect.right to rect.top,
        rect.left to rect.bottom, rect.right to rect.bottom
    )

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y; mode = 0
                corners().forEachIndexed { i, (x, y) ->
                    if (Math.hypot((e.x - x).toDouble(), (e.y - y).toDouble()) < 32 * d) mode = i + 2
                }
                if (mode == 0 && rect.contains(e.x, e.y)) mode = 1
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lx
                val dy = e.y - ly
                lx = e.x; ly = e.y
                val min = 60 * d
                when (mode) {
                    1 -> rect.offset(dx, dy)
                    2 -> {
                        rect.left = minOf(rect.left + dx, rect.right - min)
                        rect.top = minOf(rect.top + dy, rect.bottom - min)
                    }
                    3 -> {
                        rect.right = maxOf(rect.right + dx, rect.left + min)
                        rect.top = minOf(rect.top + dy, rect.bottom - min)
                    }
                    4 -> {
                        rect.left = minOf(rect.left + dx, rect.right - min)
                        rect.bottom = maxOf(rect.bottom + dy, rect.top + min)
                    }
                    5 -> {
                        rect.right = maxOf(rect.right + dx, rect.left + min)
                        rect.bottom = maxOf(rect.bottom + dy, rect.top + min)
                    }
                }
                invalidate()
            }
        }
        return true
    }
}

class CutTool(private val svc: AccessibilityService, private val wm: WindowManager) {
    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val d = svc.resources.displayMetrics.density
    private val sw = svc.resources.displayMetrics.widthPixels
    private val sh = svc.resources.displayMetrics.heightPixels
    private var overlay: FrameLayout? = null
    private var browser: View? = null
    private var browserParams: WindowManager.LayoutParams? = null
    private var big = false

    private fun toast(s: String) = Toast.makeText(svc, s, Toast.LENGTH_SHORT).show()

    fun start() {
        if (overlay != null) return
        val cutView = CutView(svc)
        val root = FrameLayout(svc)
        root.addView(cutView, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(svc).apply { gravity = Gravity.CENTER }
        fun btn(t: String, onClick: () -> Unit) = Button(svc).apply {
            text = t
            textSize = 22f
            setOnClickListener { onClick() }
        }
        bar.addView(btn("✕") { close() })
        bar.addView(btn("✓") { confirm(cutView) })
        root.addView(
            bar,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = (90 * d).toInt() }
        )

        val p = WindowManager.LayoutParams(
            -1, -1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(root, p)
        overlay = root
    }

    private fun close() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    fun closeAll() {
        close()
        browser?.let { runCatching { wm.removeView(it) } }
        browser = null
    }

    private fun confirm(v: CutView) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val r = RectF(v.rect).apply { offset(loc[0].toFloat(), loc[1].toFloat()) }
        close()
        ui.postDelayed({ readArea(r) }, 150)
    }

    private fun readArea(r: RectF) {
        val box = Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        val found = mutableListOf<Pair<Rect, String>>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty() && n.isVisibleToUser) {
                val b = Rect()
                n.getBoundsInScreen(b)
                if (Rect.intersects(b, box)) found += b to t
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(svc.rootInActiveWindow)

        val raw = found.sortedWith(compareBy({ it.first.top }, { it.first.left }))
            .joinToString("\n") { it.second }.take(3000)
        if (raw.isBlank()) {
            toast("Nggak ada teks di area itu (mungkin gambar)")
            return
        }

        val pref = svc.getSharedPreferences("k", Context.MODE_PRIVATE)
        val keys = mapOf(
            "gemini" to (pref.getString("gemini", "") ?: ""),
            "groq" to (pref.getString("groq", "") ?: ""),
            "openrouter" to (pref.getString("openrouter", "") ?: "")
        )
        if (keys.values.all { it.isBlank() }) {
            openBrowser(raw)
            return
        }

        toast("AI mendeteksi soal...")
        scope.launch {
            val prompt = "Berikut teks mentah dari area layar. Ekstrak soal lengkap beserta " +
                "pilihan jawabannya jika ada. Buang teks yang tidak relevan (tombol, timer, menu). " +
                "Balas HANYA dengan teks soalnya.\n\n$raw"
            val clean = AiClient.ask(prompt, keys)?.trim()
            openBrowser(if (clean.isNullOrBlank()) raw else clean)
        }
    }

    private fun openBrowser(query: String) {
        browser?.let { runCatching { wm.removeView(it) } }
        val q = "Jawab soal ini dengan benar dan singkat: " + query.take(1500)
        val url = "https://www.google.com/search?udm=50&q=" + URLEncoder.encode(q, "UTF-8")

        val web = WebView(svc).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(url)
        }
        val root = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val bar = LinearLayout(svc).apply {
            setBackgroundColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER_VERTICAL
        }
        fun tv(t: String, onClick: () -> Unit) = TextView(svc).apply {
            text = t
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            setOnClickListener { onClick() }
        }
        val title = TextView(svc).apply {
            text = "AI Search (tahan buat geser)"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        bar.addView(tv("◀") { if (web.canGoBack()) web.goBack() })
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(tv("⛶") { toggleSize() })
        bar.addView(tv("✕") {
            browser?.let { runCatching { wm.removeView(it) } }
            browser = null
        })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        val p = WindowManager.LayoutParams(
            (sw * 0.94f).toInt(), (sh * 0.6f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw * 0.03f).toInt()
            y = (sh * 0.3f).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        title.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y }
                MotionEvent.ACTION_MOVE -> {
                    p.x = ox + (e.rawX - sx).toInt()
                    p.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(root, p)
                }
            }
            true
        }
        browser = root
        browserParams = p
        big = false
        wm.addView(root, p)
    }

    private fun toggleSize() {
        val p = browserParams ?: return
        val b = browser ?: return
        big = !big
        p.height = (sh * if (big) 0.88f else 0.6f).toInt()
        if (big) p.y = (sh * 0.06f).toInt()
        wm.updateViewLayout(b, p)
    }
}
