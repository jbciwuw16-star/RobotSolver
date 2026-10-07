package com.robot.solver

import android.app.Activity
import android.content.Intent
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
            text = "Aktifkan di Pengaturan Aksesibilitas"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }
}
