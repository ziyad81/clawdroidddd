package com.clawdroid.llm

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.clawdroid.agent.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLMClient
 *
 * Unified HTTP client for ALL LLM providers.
 * Supports text and vision (image) input.
 *
 * Providers:
 *  - Claude (Anthropic)        → api.anthropic.com
 *  - Gemini (Google)           → generativelanguage.googleapis.com
 *  - GPT (OpenAI)              → api.openai.com
 *  - Groq                      → api.groq.com
 *  - OpenRouter                → openrouter.ai
 *  - Ollama (local)            → localhost:11434
 *  - LM Studio (local)         → localhost:1234
 *  - Any OpenAI-compatible API → custom endpoint
 *
 * Vision support: Claude, Gemini, GPT-4o, OpenRouter (passes image as base64)
 */
class LLMClient(private val config: LLMConfig) {

    companion object {
        private const val TAG = "LLMClient"
        private const val TIMEOUT_CONNECT = 30_000
        private const val TIMEOUT_READ = 120_000
    }

    // ──────────────────────────────────────────────
    // Main call — text only
    // ──────────────────────────────────────────────

    suspend fun chat(
        messages: List<LLMMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096
    ): LLMResponse = withContext(Dispatchers.IO) {
        call(messages = messages, systemPrompt = systemPrompt, maxTokens = maxTokens, image = null)
    }

    // ──────────────────────────────────────────────
    // Vision call — text + image
    // ──────────────────────────────────────────────

    suspend fun chatWithImage(
        messages: List<LLMMessage>,
        image: Bitmap,
        systemPrompt: String? = null,
        maxTokens: Int = 4096
    ): LLMResponse = withContext(Dispatchers.IO) {
        val imageB64 = bitmapToBase64(image)
        call(messages = messages, systemPrompt = systemPrompt, maxTokens = maxTokens, image = imageB64)
    }

    // ──────────────────────────────────────────────
    // Core HTTP call
    // ──────────────────────────────────────────────

    private suspend fun call(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        image: String?
    ): LLMResponse = withContext(Dispatchers.IO) {
        try {
            val endpoint = config.endpoint ?: config.provider.defaultEndpoint()
            val requestBody = buildRequestBody(messages, systemPrompt, maxTokens, image)

            Log.d(TAG, "→ ${config.provider.name} [${config.model}] ${endpoint}")

            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = TIMEOUT_CONNECT
                readTimeout = TIMEOUT_READ
                doOutput = true

                // Auth headers per provider
                when (config.provider) {
                    LLMProvider.CLAUDE -> {
                        setRequestProperty("x-api-key", config.apiKey)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    LLMProvider.GEMINI -> {
                        // API key in URL param (added in defaultEndpoint)
                    }
                    else -> {
                        if (config.apiKey.isNotBlank()) {
                            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                        }
                    }
                }
                // Extra headers (for OpenRouter, etc.)
                config.extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            // Write body
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                it.write(requestBody)
            }

            val code = connection.responseCode
            if (code != 200) {
                val err = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                Log.e(TAG, "API error $code: $err")
                return@withContext LLMResponse.error("API error $code: $err")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseResponse(responseText)

        } catch (e: Exception) {
            Log.e(TAG, "LLM call failed", e)
            LLMResponse.error("Network error: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Request builders
    // ──────────────────────────────────────────────

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        image: String?
    ): String {
        return when (config.provider) {
            LLMProvider.CLAUDE -> buildClaudeBody(messages, systemPrompt, maxTokens, image)
            LLMProvider.GEMINI -> buildGeminiBody(messages, systemPrompt, maxTokens, image)
            else -> buildOpenAIBody(messages, systemPrompt, maxTokens, image)
        }
    }

    private fun buildClaudeBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        image: String?
    ): String {
        val obj = JSONObject()
        obj.put("model", config.model)
        obj.put("max_tokens", maxTokens)

        if (!systemPrompt.isNullOrBlank()) {
            obj.put("system", systemPrompt)
        }

        val msgsArray = JSONArray()
        messages.forEachIndexed { i, msg ->
            val m = JSONObject()
            m.put("role", msg.role)

            // Attach image to last user message
            if (image != null && msg.role == "user" && i == messages.indexOfLast { it.role == "user" }) {
                val contentArray = JSONArray()
                contentArray.put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", image)
                    })
                })
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", msg.content)
                })
                m.put("content", contentArray)
            } else {
                m.put("content", msg.content)
            }
            msgsArray.put(m)
        }
        obj.put("messages", msgsArray)

        if (config.thinking) {
            obj.put("thinking", JSONObject().apply {
                put("type", "enabled")
                put("budget_tokens", 5000)
            })
        }

        return obj.toString()
    }

    private fun buildOpenAIBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        image: String?
    ): String {
        val obj = JSONObject()
        obj.put("model", config.model)
        obj.put("max_tokens", maxTokens)

        val msgsArray = JSONArray()

        // System message
        if (!systemPrompt.isNullOrBlank()) {
            msgsArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        messages.forEachIndexed { i, msg ->
            val m = JSONObject()
            m.put("role", msg.role)

            if (image != null && msg.role == "user" && i == messages.indexOfLast { it.role == "user" }) {
                val contentArray = JSONArray()
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", msg.content)
                })
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$image")
                    })
                })
                m.put("content", contentArray)
            } else {
                m.put("content", msg.content)
            }
            msgsArray.put(m)
        }

        obj.put("messages", msgsArray)

        if (config.temperature != null) obj.put("temperature", config.temperature)
        if (config.stream) obj.put("stream", true)

        return obj.toString()
    }

    private fun buildGeminiBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        image: String?
    ): String {
        val obj = JSONObject()

        // System instruction
        if (!systemPrompt.isNullOrBlank()) {
            obj.put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
        }

        val contentsArray = JSONArray()
        messages.forEachIndexed { i, msg ->
            val role = if (msg.role == "assistant") "model" else "user"
            val parts = JSONArray()

            if (image != null && msg.role == "user" && i == messages.indexOfLast { it.role == "user" }) {
                parts.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", image)
                    })
                })
            }
            parts.put(JSONObject().apply { put("text", msg.content) })

            contentsArray.put(JSONObject().apply {
                put("role", role)
                put("parts", parts)
            })
        }
        obj.put("contents", contentsArray)

        obj.put("generationConfig", JSONObject().apply {
            put("maxOutputTokens", maxTokens)
            if (config.temperature != null) put("temperature", config.temperature)
        })

        return obj.toString()
    }

    // ──────────────────────────────────────────────
    // Response parser
    // ──────────────────────────────────────────────

    private fun parseResponse(json: String): LLMResponse {
        return try {
            val obj = JSONObject(json)
            val text = when (config.provider) {
                LLMProvider.CLAUDE -> {
                    // May have thinking blocks — collect only text blocks
                    val content = obj.getJSONArray("content")
                    (0 until content.length())
                        .map { content.getJSONObject(it) }
                        .filter { it.getString("type") == "text" }
                        .joinToString("") { it.getString("text") }
                }
                LLMProvider.GEMINI -> {
                    obj.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                }
                else -> {
                    obj.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }
            }

            // Usage stats
            val usage = try {
                when (config.provider) {
                    LLMProvider.CLAUDE -> {
                        val u = obj.getJSONObject("usage")
                        TokenUsage(u.optInt("input_tokens"), u.optInt("output_tokens"))
                    }
                    else -> {
                        val u = obj.optJSONObject("usage")
                        TokenUsage(u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0)
                    }
                }
            } catch (e: Exception) { null }

            LLMResponse.success(text.trim(), usage)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error for: ${json.take(200)}", e)
            LLMResponse.error("Failed to parse response: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        // Scale down if too large (max 1080p)
        val scaled = if (bitmap.width > 1080) {
            val ratio = 1080f / bitmap.width
            Bitmap.createScaledBitmap(bitmap, 1080, (bitmap.height * ratio).toInt(), true)
        } else bitmap
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun LLMProvider.defaultEndpoint(): String = when (this) {
        LLMProvider.CLAUDE -> "https://api.anthropic.com/v1/messages"
        LLMProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent?key=${config.apiKey}"
        LLMProvider.OPENAI -> "https://api.openai.com/v1/chat/completions"
        LLMProvider.GROQ -> "https://api.groq.com/openai/v1/chat/completions"
        LLMProvider.OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
        LLMProvider.OLLAMA -> "${config.ollamaHost}/api/chat"
        LLMProvider.LM_STUDIO -> "${config.lmStudioHost}/v1/chat/completions"
        LLMProvider.CUSTOM -> config.endpoint ?: "http://localhost:8080/v1/chat/completions"
    }
}

// ──────────────────────────────────────────────
// Config & data classes
// ──────────────────────────────────────────────

data class LLMConfig(
    val provider: LLMProvider,
    val apiKey: String = "",
    val model: String,
    val endpoint: String? = null,        // override endpoint (for CUSTOM provider)
    val ollamaHost: String = "http://localhost:11434",
    val lmStudioHost: String = "http://localhost:1234",
    val temperature: Double? = null,
    val thinking: Boolean = false,       // Claude extended thinking
    val stream: Boolean = false,
    val extraHeaders: Map<String, String> = emptyMap()
)

data class LLMMessage(val role: String, val content: String) {
    companion object {
        fun user(content: String) = LLMMessage("user", content)
        fun assistant(content: String) = LLMMessage("assistant", content)
        fun system(content: String) = LLMMessage("system", content)
    }
}

data class TokenUsage(val inputTokens: Int, val outputTokens: Int) {
    val total get() = inputTokens + outputTokens
}

data class LLMResponse(
    val ok: Boolean,
    val text: String,
    val error: String? = null,
    val usage: TokenUsage? = null
) {
    companion object {
        fun success(text: String, usage: TokenUsage? = null) = LLMResponse(true, text, usage = usage)
        fun error(msg: String) = LLMResponse(false, "", error = msg)
    }
}

// Extended provider enum
enum class LLMProvider {
    CLAUDE, GEMINI, OPENAI, GROQ, OPENROUTER, OLLAMA, LM_STUDIO, CUSTOM;

    fun displayName(): String = when (this) {
        CLAUDE -> "Claude (Anthropic)"
        GEMINI -> "Gemini (Google)"
        OPENAI -> "GPT (OpenAI)"
        GROQ -> "Groq"
        OPENROUTER -> "OpenRouter"
        OLLAMA -> "Ollama (Local)"
        LM_STUDIO -> "LM Studio (Local)"
        CUSTOM -> "Custom API"
    }

    fun defaultModel(): String = when (this) {
        CLAUDE -> "claude-3-5-haiku-20241022"
        GEMINI -> "gemini-2.0-flash"
        OPENAI -> "gpt-4o-mini"
        GROQ -> "llama-3.3-70b-versatile"
        OPENROUTER -> "mistralai/mistral-7b-instruct"
        OLLAMA -> "llama3.2"
        LM_STUDIO -> "local-model"
        CUSTOM -> "custom"
    }

    fun isFree(): Boolean = this == GEMINI || this == GROQ || this == OLLAMA || this == LM_STUDIO

    fun supportsVision(): Boolean = this == CLAUDE || this == GEMINI || this == OPENAI ||
            this == OPENROUTER || this == OLLAMA  // Ollama with llava models
}
