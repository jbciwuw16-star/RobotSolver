package com.robot.solver

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiClient {
    @Volatile
    var lastError = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()

    private const val GROQ = "https://api.groq.com/openai/v1/chat/completions"
    private const val OPENROUTER = "https://openrouter.ai/api/v1/chat/completions"
    private val GEMINI_MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-2.5-flash")

    fun keys(c: Context): Map<String, String> {
        val p = c.getSharedPreferences("k", Context.MODE_PRIVATE)
        return mapOf(
            "gemini" to (p.getString("gemini", "") ?: ""),
            "groq" to (p.getString("groq", "") ?: ""),
            "openrouter" to (p.getString("openrouter", "") ?: "")
        )
    }

    private fun post(url: String, body: JSONObject, bearer: String?): String? {
        return try {
            val rb = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
            if (bearer != null) rb.header("Authorization", "Bearer $bearer")
            http.newCall(rb.build()).execute().use { r ->
                val s = r.body?.string()
                if (r.isSuccessful) {
                    s
                } else {
                    lastError = "HTTP ${r.code} " + url.substringAfter("//").substringBefore("/")
                    null
                }
            }
        } catch (e: Exception) {
            lastError = ("${e.javaClass.simpleName}: ${e.message}").take(80)
            null
        }
    }

    private fun gemini(key: String, prompt: String): String? {
        for (m in GEMINI_MODELS) {
            val r = runCatching {
                val body = JSONObject().put(
                    "contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    )
                )
                val res = post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$key",
                    body, null
                ) ?: return@runCatching null
                JSONObject(res).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            }.getOrElse {
                lastError = ("Gemini: ${it.message}").take(80)
                null
            }
            if (r != null) return r
        }
        return null
    }

    private fun openAiStyle(url: String, key: String, model: String, prompt: String): String? =
        runCatching {
            val body = JSONObject().put("model", model).put("temperature", 0).put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                )
            )
            val res = post(url, body, key) ?: return@runCatching null
            JSONObject(res).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }.getOrElse {
            lastError = ("${it.javaClass.simpleName}: ${it.message}").take(80)
            null
        }

    private fun CoroutineScope.fanOut(prompt: String, keys: Map<String, String>): List<Deferred<String?>> {
        val jobs = mutableListOf<Deferred<String?>>()
        keys["gemini"]?.takeIf { it.isNotBlank() }?.let { k ->
            jobs += async(Dispatchers.IO) { gemini(k, prompt) }
        }
        keys["groq"]?.takeIf { it.isNotBlank() }?.let { k ->
            jobs += async(Dispatchers.IO) {
                openAiStyle(GROQ, k, "llama-3.3-70b-versatile", prompt)
            }
        }
        keys["openrouter"]?.takeIf { it.isNotBlank() }?.let { k ->
            jobs += async(Dispatchers.IO) {
                openAiStyle(OPENROUTER, k, "meta-llama/llama-3.3-70b-instruct:free", prompt)
            }
        }
        return jobs
    }

    suspend fun ask(prompt: String, keys: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            val calls = listOf<() -> String?>(
                { keys["gemini"]?.takeIf { it.isNotBlank() }?.let { gemini(it, prompt) } },
                {
                    keys["groq"]?.takeIf { it.isNotBlank() }?.let {
                        openAiStyle(GROQ, it, "llama-3.3-70b-versatile", prompt)
                    }
                },
                {
                    keys["openrouter"]?.takeIf { it.isNotBlank() }?.let {
                        openAiStyle(OPENROUTER, it, "meta-llama/llama-3.3-70b-instruct:free", prompt)
                    }
                }
            )
            calls.firstNotNullOfOrNull { it() }
        }

    suspend fun clean(raw: String, keys: Map<String, String>): String? {
        lastError = ""
        val prompt = "Berikut teks mentah dari area layar. Ekstrak soal lengkap beserta " +
            "pilihan jawabannya jika ada. Buang teks yang tidak relevan (tombol, timer, menu). " +
            "Balas HANYA dengan teks soalnya.\n\n$raw"
        return ask(prompt, keys)
    }

    suspend fun expert(question: String, keys: Map<String, String>): String? = coroutineScope {
        lastError = ""
        val sys = "Kamu pakar matematika dan bahasa Arab (nahwu, sharaf, i'rab, tarjamah). " +
            "Teks berikut diambil dari layar. Temukan soalnya, kerjakan langkah demi langkah " +
            "dengan teliti, sebutkan rumus atau kaidah yang dipakai, periksa ulang, lalu tulis " +
            "baris terakhir persis: JAWABAN: <jawaban akhir>. Jangan pakai LaTeX, tulis teks " +
            "biasa. Gunakan bahasa Indonesia, teks Arab tetap dalam huruf Arab.\n\n"
        val answers = fanOut(sys + question, keys).awaitAll().filterNotNull()
        if (answers.isEmpty()) return@coroutineScope null
        if (answers.size == 1) return@coroutineScope answers[0]

        val judge = buildString {
            append("Ada satu soal dan beberapa solusi dari AI berbeda. Periksa semuanya dengan ")
            append("teliti, hitung ulang sendiri bila soal matematika, lalu tulis satu solusi ")
            append("final yang benar, runtut, dan jelas, diakhiri baris: ")
            append("JAWABAN: <jawaban akhir>. Tanpa LaTeX.\n\nSOAL:\n$question\n")
            answers.forEachIndexed { i, a -> append("\nSOLUSI ${i + 1}:\n$a\n") }
        }
        ask(judge, keys) ?: answers[0]
    }

    fun test(keys: Map<String, String>): String {
        val sb = StringBuilder()
        keys["gemini"]?.takeIf { it.isNotBlank() }?.let {
            lastError = ""
            val r = gemini(it, "Balas hanya: OK")
            sb.append("Gemini: ").append(if (r != null) "OK" else lastError.ifBlank { "gagal" }).append("\n")
        }
        keys["groq"]?.takeIf { it.isNotBlank() }?.let {
            lastError = ""
            val r = openAiStyle(GROQ, it, "llama-3.3-70b-versatile", "Balas hanya: OK")
            sb.append("Groq: ").append(if (r != null) "OK" else lastError.ifBlank { "gagal" }).append("\n")
        }
        keys["openrouter"]?.takeIf { it.isNotBlank() }?.let {
            lastError = ""
            val r = openAiStyle(OPENROUTER, it, "meta-llama/llama-3.3-70b-instruct:free", "Balas hanya: OK")
            sb.append("OpenRouter: ").append(if (r != null) "OK" else lastError.ifBlank { "gagal" }).append("\n")
        }
        return if (sb.isEmpty()) "Belum ada key yang diisi" else sb.toString().trim()
    }
}
