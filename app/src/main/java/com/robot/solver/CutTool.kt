package com.robot.solver

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
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
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var big = false
    private var kind = 0
    private var append = false
    private var ctx = ""
    private val history = mutableListOf<Pair<String, String>>()

    private fun toast(s: String) = Toast.makeText(svc, s, Toast.LENGTH_LONG).show()

    fun start(k: Int, add: Boolean = false) {
        if (overlay != null) return
        kind = k
        append = add
        val cutView = CutView(svc)
        val root = FrameLayout(svc)
        root.addView(cutView, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(svc).apply { gravity = Gravity.CENTER }
        fun btn(t: String, onClick: () -> Unit) = Button(svc).apply {
            text = t
            textSize = 22f
            setOnClickListener { onClick() }
        }
        bar.addView(btn("✕") {
            closeSelection()
            if (kind == 2 && append) {
                append = false
                showTanya()
            }
        })
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
        closePanel()
        wm.addView(root, p)
        overlay = root
    }

    private fun closeSelection() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    fun closeAll() {
        closeSelection()
        closePanel()
    }

    fun destroy() {
        closeAll()
        scope.cancel()
    }

    private fun confirm(v: CutView) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val r = RectF(v.rect).apply { offset(loc[0].toFloat(), loc[1].toFloat()) }
        closeSelection()
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

        if (kind == 2) {
            if (!append) {
                history.clear()
                ctx = ""
            }
            if (raw.isBlank()) {
                toast("Nggak ada teks di area itu, kamu tetap bisa ngetik soalnya manual")
            } else {
                ctx = if (ctx.isNotBlank()) ctx + "\n---\n" + raw else raw
                ctx = ctx.take(6000)
            }
            append = false
            showTanya()
            return
        }

        if (raw.isBlank()) {
            toast("Nggak ada teks di area itu (mungkin soalnya gambar)")
            return
        }

        val keys = AiClient.keys(svc)
        val noKey = keys.values.all { it.isBlank() }

        if (kind == 1) {
            if (noKey) {
                toast("Isi API key dulu di app")
                return
            }
            showText("PAKAR", "Pakar lagi mengerjakan...")
            scope.launch {
                val res = AiClient.expert(raw, keys)
                showText("PAKAR", res ?: ("AI gagal menjawab: " + AiClient.lastError.ifBlank { "coba lagi" }))
            }
            return
        }

        if (noKey) {
            showBrowser(raw)
            return
        }
        toast("AI mendeteksi soal...")
        scope.launch {
            val c = AiClient.clean(raw, keys)?.trim()
            showBrowser(if (c.isNullOrBlank()) raw else c)
        }
    }

    private fun openWindow(title: String, content: View, frac: Float, onBack: (() -> Unit)?) {
        closePanel()
        big = false
        val pad = (10 * d).toInt()

        val root = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val bar = LinearLayout(svc).apply {
            setBackgroundColor(Color.parseColor("#1F2937"))
            gravity = Gravity.CENTER_VERTICAL
        }
        fun tv(t: String, size: Float, click: (() -> Unit)?) = TextView(svc).apply {
            text = t
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
            if (click != null) setOnClickListener { click() }
        }
        val titleView = tv("$title (tahan untuk geser)", 12f, null)
        if (onBack != null) bar.addView(tv("◀", 18f, onBack))
        bar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(tv("⛶", 18f) { toggleSize() })
        bar.addView(tv("✕", 18f) { closePanel() })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val p = WindowManager.LayoutParams(
            (sw * 0.94f).toInt(), (sh * frac).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw * 0.03f).toInt()
            y = (sh * 0.12f).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        titleView.setOnTouchListener { _, e ->
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
        panel = root
        panelParams = p
        wm.addView(root, p)
    }

    private fun toggleSize() {
        val p = panelParams ?: return
        val v = panel ?: return
        big = !big
        p.height = (sh * if (big) 0.88f else 0.55f).toInt()
        if (big) p.y = (sh * 0.06f).toInt()
        wm.updateViewLayout(v, p)
    }

    private fun showText(title: String, body: String) {
        val pad = (12 * d).toInt()
        val tv = TextView(svc).apply {
            text = body
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        openWindow(title, ScrollView(svc).apply { addView(tv) }, 0.55f, null)
    }

    private fun showBrowser(query: String) {
        val q = "Jawab soal ini dengan benar dan singkat: " + query.take(1500)
        val url = "https://www.google.com/search?udm=50&q=" + URLEncoder.encode(q, "UTF-8")
        val web = WebView(svc).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(url)
        }
        openWindow("AI SEARCH", web, 0.6f) { if (web.canGoBack()) web.goBack() }
    }

    private fun showTanya() {
        val pad = (10 * d).toInt()

        val ctxView = TextView(svc).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.parseColor("#F3F4F6"))
            setPadding(pad, pad, pad, pad)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            text = if (ctx.isBlank()) "Potongan: (kosong)" else "Potongan: " + ctx.replace("\n", " ")
        }

        val log = TextView(svc).apply {
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        val logScroll = ScrollView(svc).apply { addView(log) }

        var thinking = false
        fun render() {
            val sb = StringBuilder()
            if (history.isEmpty() && !thinking) {
                sb.append("Ketik pertanyaanmu di bawah, potongan di atas ikut dikirim ke AI.")
            }
            for ((role, text) in history) {
                sb.append(if (role == "user") "Kamu: " else "AI: ").append(text).append("\n\n")
            }
            if (thinking) sb.append("AI sedang mikir...")
            log.text = sb.toString()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }

        fun pill(label: String, color: String) = TextView(svc).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(Color.parseColor(color))
            }
        }

        val input = EditText(svc).apply {
            hint = "Tanya sesuatu..."
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            maxLines = 3
        }
        val addBtn = pill("＋", "#2563EB")
        val sendBtn = pill("KIRIM", "#16A34A")

        addBtn.setOnClickListener { start(2, true) }
        sendBtn.setOnClickListener {
            val q = input.text.toString().trim()
            if (q.isEmpty() || thinking) return@setOnClickListener
            val keys = AiClient.keys(svc)
            if (keys.values.all { it.isBlank() }) {
                toast("Isi API key dulu di app")
                return@setOnClickListener
            }
            input.setText("")
            history += "user" to q
            thinking = true
            render()
            scope.launch {
                val ans = AiClient.chat(ctx, history.toList(), keys)
                history += "ai" to (ans?.trim() ?: ("AI gagal menjawab: " + AiClient.lastError.ifBlank { "coba lagi" }))
                thinking = false
                render()
            }
        }

        val row = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        row.addView(addBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = pad / 2 })
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(sendBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = pad / 2 })

        val content = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ctxView, LinearLayout.LayoutParams(-1, -2))
        content.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(row, LinearLayout.LayoutParams(-1, -2))

        render()
        openWindow("TANYA", content, 0.6f, null)
    }
}
