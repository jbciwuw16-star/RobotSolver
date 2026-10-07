package com.robot.solver

import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiClient {
    private val http = OkHttpClient.Builder()
        .callTimeout(40, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    private fun post(url: String, body: JSONObject, bearer: String?): String? {
        val rb = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
        if (bearer != null) rb.header("Authorization", "Bearer $bearer")
        http.newCall(rb.build()).execute().use { r ->
            return if (r.isSuccessful) r.body?.string() else null
        }
    }

    private fun gemini(key: String, prompt: String): String? = runCatching {
        val body = JSONObject().put("contents", JSONArray().put(
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        val res = post(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key",
            body, null) ?: return null
        JSONObject(res).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }.getOrNull()

    private fun openAiStyle(url: String, key: String, model: String, prompt: String): String? =
        runCatching {
            val body = JSONObject().put("model", model).put("temperature", 0).put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)))
            val res = post(url, body, key) ?: return null
            JSONObject(res).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }.getOrNull()

    private const val GROQ = "https://api.groq.com/openai/v1/chat/completions"
    private const val OPENROUTER = "https://openrouter.ai/api/v1/chat/completions"

    suspend fun vote(prompt: String, keys: Map<String, String>): Int? = coroutineScope {
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
        jobs.awaitAll().filterNotNull()
            .mapNotNull { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key
    }

    suspend fun ask(prompt: String, keys: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            val calls = listOf<() -> String?>(
                { keys["gemini"]?.takeIf { it.isNotBlank() }?.let { gemini(it, prompt) } },
                { keys["groq"]?.takeIf { it.isNotBlank() }?.let {
                    openAiStyle(GROQ, it, "llama-3.3-70b-versatile", prompt) } },
                { keys["openrouter"]?.takeIf { it.isNotBlank() }?.let {
                    openAiStyle(OPENROUTER, it, "meta-llama/llama-3.3-70b-instruct:free", prompt) } }
            )
            calls.firstNotNullOfOrNull { it() }
        }
}
