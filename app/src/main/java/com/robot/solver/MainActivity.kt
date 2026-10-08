package com.robot.solver

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("k", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun field(hint: String, key: String) = EditText(this).apply {
            this.hint = hint
            setText(p.getString(key, ""))
            setSingleLine()
            layout.addView(this)
        }
        val g = field("Gemini API key", "gemini")
        val q = field("Groq API key", "groq")
        val o = field("OpenRouter API key", "openrouter")

        layout.addView(Button(this).apply {
            text = "Simpan key"
            setOnClickListener {
                p.edit()
                    .putString("gemini", g.text.toString().trim())
                    .putString("groq", q.text.toString().trim())
                    .putString("openrouter", o.text.toString().trim())
                    .apply()
                Toast.makeText(context, "Tersimpan", Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(Button(this).apply {
            text = "Tes API key"
            setOnClickListener {
                Toast.makeText(context, "Mengetes...", Toast.LENGTH_SHORT).show()
                Thread {
                    val r = AiClient.test(AiClient.keys(this@MainActivity))
                    runOnUiThread { Toast.makeText(context, r, Toast.LENGTH_LONG).show() }
                }.start()
            }
        })
        layout.addView(Button(this).apply {
            text = "Aktifkan di Pengaturan Aksesibilitas"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        layout.addView(Button(this).apply {
            text = "Matikan penghemat baterai"
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Buka Info Aplikasi"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }
}
