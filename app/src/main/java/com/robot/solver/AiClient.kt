package com.robot.solver

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
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

    // readTimeout bawaan OkHttp cuma 10 detik -> model yang mikir lama langsung SocketTimeout
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()

    private const val GROQ = "https://api.groq.com/openai/v1/chat/completions"
    private const val OPENROUTER = "https://openrouter.ai/api/v1/chat/completions"
    private val GEMINI_MODELS = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-flash-latest")

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

    private fun gemini(key: String, prompt: String, image: String? = null): String? {
        for (m in GEMINI_MODELS) {
            val r = runCatching {
                val parts = JSONArray()
                if (image != null) {
                    parts.put(
                        JSONObject().put(
                            "inlineData",
                            JSONObject().put("mimeType", "image/jpeg").put("data", image)
                        )
                    )
                }
                parts.put(JSONObject().put("text", prompt))
                val body = JSONObject().put(
                    "contents", JSONArray().put(JSONObject().put("parts", parts))
                )
                val res = post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$key",
                    body, null
                ) ?: return@runCatching null
                val ps = JSONObject(res).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                buildString {
                    for (i in 0 until ps.length()) append(ps.getJSONObject(i).optString("text"))
                }.ifBlank { null }
            }.getOrElse {
                lastError = ("Gemini: ${it.message}").take(80)
                null
            }
            if (r != null) return r
            // timeout = model lagi lambat, jangan numpuk nunggu model lain satu-satu
            if (lastError.contains("Timeout", ignoreCase = true)) break
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

    // Semua provider jalan barengan, yang pertama berhasil langsung dipakai
    suspend fun ask(prompt: String, keys: Map<String, String>): String? {
        val racer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val pending = racer.fanOut(prompt, keys).toMutableList()
            while (pending.isNotEmpty()) {
                val (done, value) = select<Pair<Deferred<String?>, String?>> {
                    pending.forEach { d -> d.onAwait { d to it } }
                }
                pending.remove(done)
                if (!value.isNullOrBlank()) return value
            }
            return null
        } finally {
            racer.cancel()
        }
    }

    suspend fun clean(raw: String, keys: Map<String, String>): String? {
        lastError = ""
        val prompt = "Berikut teks mentah dari area layar. Ekstrak soal lengkap beserta " +
            "pilihan jawabannya jika ada. Buang teks yang tidak relevan (tombol, timer, menu). " +
            "Balas HANYA dengan teks soalnya.\n\n$raw"
        return ask(prompt, keys)
    }

    suspend fun chat(
        context: String,
        history: List<Pair<String, String>>,
        keys: Map<String, String>
    ): String? {
        lastError = ""
        val prompt = buildString {
            append("Kamu asisten belajar yang akurat dan jelas. Pengguna memotong teks dari layar ")
            append("HP sebagai konteks, lalu bertanya. Jawab pertanyaan terakhir pengguna dalam ")
            append("bahasa Indonesia, runtut, dan teliti (hitung ulang bila ada hitungan). ")
            append("Jangan pakai LaTeX, tulis teks biasa. Teks Arab tetap dalam huruf Arab.\n\n")
            if (context.isNotBlank()) append("POTONGAN TEKS DARI LAYAR:\n$context\n\n")
            append("PERCAKAPAN:\n")
            for ((role, text) in history) {
                append(if (role == "user") "Pengguna: " else "Asisten: ").append(text).append("\n")
            }
            append("Asisten:")
        }
        return ask(prompt, keys)
    }

    private fun askSeq(prompt: String, keys: Map<String, String>): String? =
        keys["gemini"]?.takeIf { it.isNotBlank() }?.let { gemini(it, prompt) }
            ?: keys["groq"]?.takeIf { it.isNotBlank() }?.let {
                openAiStyle(GROQ, it, "llama-3.3-70b-versatile", prompt)
            }
            ?: keys["openrouter"]?.takeIf { it.isNotBlank() }?.let {
                openAiStyle(OPENROUTER, it, "meta-llama/llama-3.3-70b-instruct:free", prompt)
            }

    private val NO_IMG = "TIDAK_BISA_LIHAT_GAMBAR"

    private fun finalOf(a: String): String? =
        Regex("JAWABAN\\s*:\\s*(.+)", RegexOption.IGNORE_CASE).findAll(a).lastOrNull()
            ?.groupValues?.get(1)?.lowercase()?.replace(Regex("[\\s\\p{Punct}*]+"), "")
            ?.ifBlank { null }

    /**
     * Pakar: semua AI jalan barengan. Jawaban pertama yang jadi langsung ditampilkan (onStatus),
     * lalu nunggu AI lain maksimal beberapa detik. Kalau jawaban akhirnya sama -> selesai tanpa hakim.
     * Kalau beda -> hakim. Kalau ada gambar, Gemini yang bisa melihatnya.
     */
    suspend fun expert(
        question: String,
        keys: Map<String, String>,
        image: String?,
        onStatus: (String) -> Unit
    ): String? {
        lastError = ""
        val racer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val base = "Kamu pakar matematika dan bahasa Arab (nahwu, sharaf, i'rab, tarjamah). " +
                "Temukan soalnya, kerjakan langkah demi langkah dengan teliti, sebutkan rumus atau " +
                "kaidah yang dipakai, periksa ulang, lalu tulis baris terakhir persis: " +
                "JAWABAN: <jawaban akhir>. Jangan pakai LaTeX, tulis teks biasa. " +
                "Gunakan bahasa Indonesia, teks Arab tetap dalam huruf Arab.\n\n"
            val visSys = "Potongan layar HP terlampir sebagai gambar. Baca soal dan semua gambarnya " +
                "(diagram, grafik, bangun, tabel, tulisan Arab). Teks hasil baca otomatis (bisa kosong " +
                "atau kurang lengkap) ada di bawah. " + base
            val textSys = (if (image != null)
                "Kamu tidak bisa melihat gambar soal. Jika soal bergantung pada gambar yang tidak ada " +
                    "di teks, balas persis: $NO_IMG. "
            else "Teks berikut diambil dari layar. ") + base

            val jobs = mutableListOf<Deferred<String?>>()
            keys["gemini"]?.takeIf { it.isNotBlank() }?.let { k ->
                jobs += racer.async { gemini(k, (if (image != null) visSys else textSys) + question, image) }
            }
            if (question.isNotBlank()) {
                keys["groq"]?.takeIf { it.isNotBlank() }?.let { k ->
                    jobs += racer.async { openAiStyle(GROQ, k, "llama-3.3-70b-versatile", textSys + question) }
                }
                keys["openrouter"]?.takeIf { it.isNotBlank() }?.let { k ->
                    jobs += racer.async {
                        openAiStyle(OPENROUTER, k, "meta-llama/llama-3.3-70b-instruct:free", textSys + question)
                    }
                }
            }
            if (jobs.isEmpty()) return null

            val answers = mutableListOf<String>()
            val pending = jobs.toMutableList()
            val t0 = System.currentTimeMillis()
            var tFirst = 0L
            while (pending.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val limit = if (answers.isEmpty()) 85_000L - (now - t0) else 7_000L - (now - tFirst)
                if (limit <= 0) break
                val got = withTimeoutOrNull(limit) {
                    select<Pair<Deferred<String?>, String?>> {
                        pending.forEach { d -> d.onAwait { d to it } }
                    }
                } ?: break
                pending.remove(got.first)
                val v = got.second?.trim()
                if (!v.isNullOrBlank() && !v.contains(NO_IMG)) {
                    answers += v
                    if (answers.size == 1) {
                        tFirst = System.currentTimeMillis()
                        if (pending.isNotEmpty()) onStatus(v + "\n\n⏳ Mengecek dengan AI lain...")
                    }
                }
            }
            if (answers.isEmpty()) return null
            if (answers.size == 1) return answers[0]

            val finals = answers.map { finalOf(it) }
            if (finals.all { it != null } && finals.distinct().size == 1) {
                return answers[0] + "\n\n✓ " + answers.size + " AI sepakat"
            }

            onStatus(answers[0] + "\n\n⏳ Jawaban AI beda, hakim lagi mengecek...")
            val judge = buildString {
                append("Ada satu soal dan beberapa solusi dari AI berbeda. Periksa semuanya dengan ")
                append("teliti, hitung ulang sendiri bila soal matematika, lalu tulis satu solusi ")
                append("final yang benar, runtut, dan jelas, diakhiri baris: ")
                append("JAWABAN: <jawaban akhir>. Tanpa LaTeX.\n\n")
                if (image != null) append("Gambar soal terlampir.\n")
                append("SOAL (teks hasil baca):\n$question\n")
                answers.forEachIndexed { i, a -> append("\nSOLUSI ${i + 1}:\n$a\n") }
            }
            val judgeJob = racer.async {
                keys["gemini"]?.takeIf { it.isNotBlank() }?.let { gemini(it, judge, image) }
                    ?: askSeq(judge, keys)
            }
            return withTimeoutOrNull(45_000L) { judgeJob.await() }?.trim()?.ifBlank { null }
                ?: answers[0]
        } finally {
            racer.cancel()
        }
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
