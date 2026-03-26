package com.clawdroid.agent

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.clawdroid.llm.LLMClient
import com.clawdroid.llm.LLMConfig
import com.clawdroid.llm.LLMMessage
import com.clawdroid.llm.LLMProvider
import com.clawdroid.memory.MemoryStore
import com.clawdroid.skills.SkillsEngine
import com.clawdroid.tools.FullToolRegistry
import com.clawdroid.vision.ScreenshotEngine
import com.clawdroid.voice.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * PowerAgentCore
 *
 * The full-power AI reasoning loop for ClawDroid.
 *
 * Features beyond the basic AgentCore:
 *  - Vision: can take and analyze screenshots
 *  - Memory: injects long-term facts into every system prompt
 *  - Skills: auto-detects and executes matching skills
 *  - Full tool registry: 40+ tools
 *  - Multi-provider: Claude, Gemini, GPT, Groq, OpenRouter, Ollama, LM Studio, Custom
 *  - Task history logging
 *  - Token usage tracking
 *  - Configurable thinking (Claude extended thinking)
 *
 * Loop:
 *  1. Load memories + check for matching skill
 *  2. Build rich system prompt
 *  3. User task → LLM
 *  4. LLM returns tool call → execute → back to LLM
 *  5. LLM returns final answer → log to history → done
 */
class PowerAgentCore(
    private val context: Context,
    private val llmConfig: LLMConfig,
    private val memoryStore: MemoryStore,
    private val skillsEngine: SkillsEngine,
    private val voiceEngine: VoiceEngine,
    private val onLog: (String) -> Unit = {},
    private val onScreenshotNeeded: (suspend (Bitmap) -> String)? = null  // vision callback
) {
    companion object {
        private const val TAG = "PowerAgent"
        private const val MAX_ITERATIONS = 30
    }

    private val llmClient = LLMClient(llmConfig)
    private val toolRegistry = FullToolRegistry(context, memoryStore, skillsEngine, voiceEngine)
    private val conversationHistory = mutableListOf<LLMMessage>()

    // Token tracking
    private var totalInputTokens = 0
    private var totalOutputTokens = 0

    // ──────────────────────────────────────────────
    // Run a task
    // ──────────────────────────────────────────────

    suspend fun runTask(userTask: String): String = withContext(Dispatchers.IO) {
        log("🚀 Task: $userTask")
        conversationHistory.clear()
        totalInputTokens = 0
        totalOutputTokens = 0

        // Check accessibility
        if (!com.clawdroid.accessibility.DeviceController.isReady()) {
            return@withContext "❌ Accessibility service not running. Go to Settings → Accessibility → Installed Services → ClawDroid Agent → Enable"
        }

        // Check for matching skill
        val matchingSkill = skillsEngine.findMatchingSkill(userTask)
        if (matchingSkill != null) {
            log("🛠️ Matched skill: ${matchingSkill.name}")
        }

        // Build initial message
        val taskWithSkill = if (matchingSkill != null) {
            "$userTask\n\n[Use this skill guide]\n${matchingSkill.content}"
        } else userTask

        conversationHistory.add(LLMMessage.user(taskWithSkill))

        var iteration = 0
        var finalResponse = ""
        var lastScreenshot: Bitmap? = null

        while (iteration < MAX_ITERATIONS) {
            iteration++
            log("🔄 Step $iteration/$MAX_ITERATIONS")

            // Build system prompt with fresh memory each iteration
            val systemPrompt = buildSystemPrompt()

            // Call LLM (with vision if screenshot available)
            val response = if (lastScreenshot != null && llmConfig.provider.supportsVision()) {
                log("👁️ Using vision (screenshot attached)")
                val r = llmClient.chatWithImage(
                    messages = conversationHistory,
                    image = lastScreenshot!!,
                    systemPrompt = systemPrompt
                )
                lastScreenshot = null  // consume
                r
            } else {
                llmClient.chat(
                    messages = conversationHistory,
                    systemPrompt = systemPrompt
                )
            }

            if (!response.ok) {
                log("❌ LLM error: ${response.error}")
                finalResponse = "Failed to get AI response: ${response.error}"
                break
            }

            // Track tokens
            response.usage?.let {
                totalInputTokens += it.inputTokens
                totalOutputTokens += it.outputTokens
                log("📊 Tokens: +${it.inputTokens}in +${it.outputTokens}out (total: ${totalInputTokens + totalOutputTokens})")
            }

            val llmText = response.text
            log("🤖 AI: ${llmText.take(200)}${if (llmText.length > 200) "..." else ""}")

            // Check if done (task_done tool or no tool call)
            val toolCall = parseToolCall(llmText)

            if (toolCall == null || toolCall.name == "task_done") {
                finalResponse = if (toolCall?.name == "task_done") {
                    toolCall.params.optString("message", llmText)
                } else {
                    llmText
                }
                log("✅ Task complete!")
                break
            }

            // Execute tool
            log("🔧 Tool: ${toolCall.name}(${toolCall.params})")
            val toolResult = toolRegistry.execute(toolCall.name, toolCall.params)
            log("📋 Result: ${toolResult.take(300)}")

            // Special handling for screenshot tool — capture actual bitmap for vision
            if (toolCall.name == "capture_screenshot" && ScreenshotEngine.isReady()) {
                val screenshotResult = ScreenshotEngine.capture()
                if (screenshotResult.ok) {
                    lastScreenshot = screenshotResult.bitmap
                    log("📸 Screenshot ready for vision")
                }
            }

            // Add to history
            conversationHistory.add(LLMMessage.assistant(llmText))
            conversationHistory.add(LLMMessage.user("Tool result for ${toolCall.name}:\n$toolResult"))

            // Safety: stop on repeated errors
            if (toolResult.startsWith("❌ ERROR") && iteration > 5) {
                // Give LLM a chance to recover, but count errors
                val recentErrors = conversationHistory.takeLast(6)
                    .count { it.content.startsWith("Tool result") && it.content.contains("❌ ERROR") }
                if (recentErrors >= 3) {
                    log("⚠️ Too many consecutive errors, stopping")
                    finalResponse = "Task failed after multiple attempts. Last error: $toolResult"
                    break
                }
            }

            // Keep history from growing too large
            if (conversationHistory.size > 40) {
                // Keep first (original task) + last 20 exchanges
                val first = conversationHistory.first()
                val recent = conversationHistory.takeLast(20)
                conversationHistory.clear()
                conversationHistory.add(first)
                conversationHistory.addAll(recent)
                log("📦 History trimmed to fit context window")
            }
        }

        if (iteration >= MAX_ITERATIONS) {
            finalResponse = "Reached maximum steps ($MAX_ITERATIONS). Last action completed."
        }

        // Log to history
        memoryStore.logTask(
            task = userTask,
            result = finalResponse,
            success = !finalResponse.startsWith("❌") && !finalResponse.contains("Failed")
        )

        log("📈 Total tokens used: ${totalInputTokens + totalOutputTokens} ($totalInputTokens in / $totalOutputTokens out)")
        finalResponse
    }

    // ──────────────────────────────────────────────
    // System prompt
    // ──────────────────────────────────────────────

    private suspend fun buildSystemPrompt(): String {
        val memorySummary = memoryStore.buildFactsSummary()
        val toolSection = toolRegistry.buildSystemPromptSection()

        return buildString {
            appendLine("""
You are ClawDroid 🦞 — a powerful AI agent that controls an Android phone on behalf of the user.
You can read the screen, tap, type, swipe, use apps, read contacts and SMS, manage calendar, 
access files, and remember things across sessions.

## CORE RULES
1. ALWAYS call read_screen first to see what's on screen before taking actions
2. Take ONE action at a time, then check the result
3. If something fails, try an alternative approach (different text, coordinates, or method)
4. Be persistent — real phones have delays, popups, and quirks
5. When fully done, call task_done with a clear summary
6. If you cannot complete a task, explain why clearly

## HOW TO USE TOOLS
<tool>tool_name</tool><params>{"key": "value"}</params>
For no-param tools: <tool>read_screen</tool><params>{}</params>

## MEMORY
When you learn something important about the user, save it with save_memory.
When the user asks you to remember something, use save_memory immediately.
            """.trimIndent())

            if (memorySummary.isNotBlank()) {
                appendLine()
                appendLine(memorySummary)
            }

            appendLine()
            appendLine(toolSection)
        }
    }

    // ──────────────────────────────────────────────
    // Parse tool call
    // ──────────────────────────────────────────────

    private fun parseToolCall(response: String): ToolCall? {
        return try {
            val toolRegex = Regex("<tool>(.*?)</tool>", RegexOption.DOT_MATCHES_ALL)
            val paramsRegex = Regex("<params>(.*?)</params>", RegexOption.DOT_MATCHES_ALL)

            val toolName = toolRegex.find(response)?.groupValues?.get(1)?.trim() ?: return null
            val paramsStr = paramsRegex.find(response)?.groupValues?.get(1)?.trim() ?: "{}"
            val params = try {
                JSONObject(paramsStr)
            } catch (e: Exception) {
                JSONObject()
            }

            ToolCall(toolName, params)
        } catch (e: Exception) {
            null
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog(msg)
    }
}

data class ToolCall(val name: String, val params: JSONObject)

// Keep original simple provider enum for backwards compat
enum class LLMProvider {
    CLAUDE, GEMINI, OPENAI, GROQ, OPENROUTER, OLLAMA;

    fun getApiEndpoint(ollamaHost: String = "http://localhost:11434"): String = when (this) {
        CLAUDE -> "https://api.anthropic.com/v1/messages"
        GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        OPENAI -> "https://api.openai.com/v1/chat/completions"
        GROQ -> "https://api.groq.com/openai/v1/chat/completions"
        OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
        OLLAMA -> "$ollamaHost/v1/chat/completions"
    }
}

data class AgentSettings(
    val provider: LLMProvider,
    val apiKey: String,
    val model: String,
    val ollamaHost: String = "http://localhost:11434"
) {
    fun getApiEndpoint() = provider.getApiEndpoint(ollamaHost)
}

data class Message(val role: String, val content: String)
