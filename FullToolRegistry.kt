package com.clawdroid.tools

import android.content.Context
import com.clawdroid.accessibility.DeviceController
import com.clawdroid.memory.MemoryStore
import com.clawdroid.skills.SkillsEngine
import com.clawdroid.vision.ScreenshotEngine
import com.clawdroid.voice.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * FullToolRegistry
 *
 * Complete set of ALL tools available to the ClawDroid AI agent.
 *
 * Categories:
 *  1. Screen (read_screen, get_interactive_elements, capture_screenshot, describe_screen)
 *  2. Tap/Gesture (tap, tap_text, tap_id, swipe, scroll, long_press)
 *  3. Type (type_text, tap_and_type, clear_text)
 *  4. System (press_back, press_home, press_recents, open_notifications)
 *  5. Apps (open_app, list_apps, is_app_installed)
 *  6. Contacts (search_contacts, get_all_contacts)
 *  7. SMS (read_sms, send_sms)
 *  8. Calls (get_call_log, dial_number, make_call)
 *  9. Calendar (get_calendar_events, add_calendar_event)
 * 10. Files (list_files, read_file, write_file, delete_file)
 * 11. Memory (save_memory, get_memory, list_memories, delete_memory)
 * 12. Voice (speak, listen)
 * 13. Device (get_device_info, open_settings, share_text, open_url)
 * 14. Skills (list_skills, run_skill, create_skill)
 * 15. Utility (wait, run_task_loop)
 *
 * Total: 40+ tools
 */
class FullToolRegistry(
    private val context: Context,
    private val memoryStore: MemoryStore,
    private val skillsEngine: SkillsEngine,
    private val voiceEngine: VoiceEngine
) {

    // ──────────────────────────────────────────────
    // Tool definitions (sent to LLM in system prompt)
    // ──────────────────────────────────────────────

    val tools: List<ToolDefinition> = buildList {

        // ── SCREEN ──
        add(tool("read_screen", "Read all visible text, buttons, inputs and UI elements on screen with their positions. Always call this first to understand what's on screen.", emptyMap()))
        add(tool("get_interactive_elements", "Get compact list of only tappable/editable elements on screen.", emptyMap()))
        add(tool("capture_screenshot", "Take a screenshot and analyze it visually. Use when you need to 'see' the screen as an image, read text from images, or describe what's shown.", emptyMap()))

        // ── TAP / GESTURE ──
        add(tool("tap", "Tap at exact coordinates.", mapOf("x" to "number:X coordinate", "y" to "number:Y coordinate")))
        add(tool("tap_text", "Tap element by its text label.", mapOf("text" to "string:The text to find and tap")))
        add(tool("tap_id", "Tap element by resource ID.", mapOf("resource_id" to "string:Resource ID")))
        add(tool("long_press", "Long press at coordinates.", mapOf("x" to "number:X coordinate", "y" to "number:Y coordinate")))

        // ── TYPE ──
        add(tool("type_text", "Type text into the currently focused field.", mapOf("text" to "string:Text to type")))
        add(tool("tap_and_type", "Tap a field then type into it.", mapOf(
            "x" to "number:X coordinate", "y" to "number:Y coordinate", "text" to "string:Text to type"
        )))
        add(tool("clear_text", "Clear text in the currently focused field.", emptyMap()))

        // ── SWIPE / SCROLL ──
        add(tool("swipe", "Swipe from one point to another.", mapOf(
            "start_x" to "number", "start_y" to "number",
            "end_x" to "number", "end_y" to "number",
            "duration_ms" to "number:default 300"
        )))
        add(tool("scroll_down", "Scroll down.", emptyMap()))
        add(tool("scroll_up", "Scroll up.", emptyMap()))

        // ── SYSTEM BUTTONS ──
        add(tool("press_back", "Press system Back button.", emptyMap()))
        add(tool("press_home", "Press Home button.", emptyMap()))
        add(tool("press_recents", "Open recent apps.", emptyMap()))
        add(tool("open_notifications", "Pull down notification shade.", emptyMap()))

        // ── APPS ──
        add(tool("open_app", "Open an installed app by package name.", mapOf("package_name" to "string:e.g. com.whatsapp")))
        add(tool("list_apps", "List all installed user apps with their package names.", emptyMap()))
        add(tool("is_app_installed", "Check if an app is installed.", mapOf("package_name" to "string:Package name to check")))
        add(tool("open_url", "Open a URL in the browser.", mapOf("url" to "string:Full URL including https://")))
        add(tool("open_settings", "Open a specific settings screen.", mapOf("type" to "string:wifi/bluetooth/display/sound/apps/battery/airplane/location/developer")))

        // ── CONTACTS ──
        add(tool("search_contacts", "Search contacts by name.", mapOf("query" to "string:Name to search")))
        add(tool("get_all_contacts", "List all contacts.", mapOf("limit" to "number:Max results (default 50)")))

        // ── SMS ──
        add(tool("read_sms", "Read SMS messages.", mapOf(
            "limit" to "number:Number of messages (default 20)",
            "contact" to "string:Filter by phone number (optional)"
        )))
        add(tool("send_sms", "Send an SMS message.", mapOf(
            "phone" to "string:Phone number",
            "message" to "string:Message content"
        )))

        // ── CALLS ──
        add(tool("get_call_log", "Get recent call history.", mapOf("limit" to "number:default 20")))
        add(tool("dial_number", "Open dialer with a number (user still taps call).", mapOf("phone" to "string:Phone number")))
        add(tool("make_call", "Directly call a number (requires CALL_PHONE permission).", mapOf("phone" to "string:Phone number")))

        // ── CALENDAR ──
        add(tool("get_calendar_events", "Read upcoming calendar events.", mapOf("days_ahead" to "number:How many days to look ahead (default 7)")))
        add(tool("add_calendar_event", "Add a calendar event.", mapOf(
            "title" to "string:Event title",
            "start_ms" to "number:Start time in milliseconds since epoch",
            "end_ms" to "number:End time in milliseconds since epoch",
            "description" to "string:Optional description",
            "location" to "string:Optional location"
        )))

        // ── FILES ──
        add(tool("list_files", "List files in a directory.", mapOf("path" to "string:Directory path (default /sdcard/)")))
        add(tool("read_file", "Read a text file.", mapOf("path" to "string:File path", "max_chars" to "number:Max chars to read (default 5000)")))
        add(tool("write_file", "Write content to a file.", mapOf("path" to "string:File path", "content" to "string:Content to write")))
        add(tool("delete_file", "Delete a file.", mapOf("path" to "string:File path")))

        // ── MEMORY ──
        add(tool("save_memory", "Save a fact to long-term memory. Persists across sessions.", mapOf(
            "key" to "string:A descriptive key (e.g. 'user_name', 'preferred_alarm_time')",
            "value" to "string:The value to remember"
        )))
        add(tool("get_memory", "Retrieve a stored memory by key.", mapOf("key" to "string:The key to look up")))
        add(tool("list_memories", "List all stored memories.", emptyMap()))
        add(tool("delete_memory", "Delete a stored memory.", mapOf("key" to "string:Key to delete")))
        add(tool("write_workspace_file", "Write a file to the agent workspace.", mapOf(
            "filename" to "string:Filename", "content" to "string:File content"
        )))
        add(tool("read_workspace_file", "Read a file from the agent workspace.", mapOf("filename" to "string:Filename")))

        // ── VOICE ──
        add(tool("speak", "Say something aloud using text-to-speech.", mapOf("text" to "string:Text to speak")))
        add(tool("listen", "Listen for voice input from the user (STT).", emptyMap()))

        // ── DEVICE INFO ──
        add(tool("get_device_info", "Get device info: battery, storage, RAM, Android version.", emptyMap()))
        add(tool("share_text", "Open the share sheet to share text to any app.", mapOf("text" to "string:Text to share")))

        // ── SKILLS ──
        add(tool("list_skills", "List available agent skills.", emptyMap()))
        add(tool("create_skill", "Create a new reusable skill.", mapOf(
            "name" to "string:Skill name (no spaces)",
            "description" to "string:What this skill does",
            "triggers" to "string:Comma-separated trigger phrases",
            "instructions" to "string:Step-by-step instructions for the skill"
        )))

        // ── UTILITY ──
        add(tool("wait", "Wait N milliseconds.", mapOf("ms" to "number:Milliseconds to wait")))
        add(tool("task_done", "Signal that the task is complete with a final message.", mapOf("message" to "string:Summary of what was done")))
    }

    private fun tool(name: String, description: String, params: Map<String, String>): ToolDefinition =
        ToolDefinition(name, description, params.mapValues { (_, v) ->
            val parts = v.split(":")
            ParamDef(parts[0], parts.getOrNull(1) ?: "")
        })

    // ──────────────────────────────────────────────
    // Build system prompt section
    // ──────────────────────────────────────────────

    suspend fun buildSystemPromptSection(): String {
        val skillsSection = skillsEngine.buildSystemPromptSection()
        return buildString {
            appendLine("## AVAILABLE TOOLS")
            appendLine("Use XML format: <tool>tool_name</tool><params>{\"key\": \"value\"}</params>")
            appendLine("For tools with no params: <tool>tool_name</tool><params>{}</params>")
            appendLine()
            tools.groupBy { getCategory(it.name) }.forEach { (category, categoryTools) ->
                appendLine("### $category")
                categoryTools.forEach { t ->
                    append("• **${t.name}**: ${t.description}")
                    if (t.parameters.isNotEmpty()) {
                        val paramStr = t.parameters.entries.joinToString(", ") { (k, v) -> "$k(${v.type})" }
                        append(" | params: $paramStr")
                    }
                    appendLine()
                }
                appendLine()
            }
            if (skillsSection.isNotBlank()) {
                appendLine(skillsSection)
            }
        }
    }

    private fun getCategory(name: String): String = when {
        name in listOf("read_screen", "get_interactive_elements", "capture_screenshot") -> "📱 Screen"
        name in listOf("tap", "tap_text", "tap_id", "long_press", "swipe", "scroll_down", "scroll_up") -> "👆 Gestures"
        name in listOf("type_text", "tap_and_type", "clear_text") -> "⌨️ Typing"
        name in listOf("press_back", "press_home", "press_recents", "open_notifications") -> "🔘 System"
        name in listOf("open_app", "list_apps", "is_app_installed", "open_url", "open_settings") -> "📲 Apps"
        name in listOf("search_contacts", "get_all_contacts") -> "👥 Contacts"
        name in listOf("read_sms", "send_sms") -> "💬 SMS"
        name in listOf("get_call_log", "dial_number", "make_call") -> "📞 Calls"
        name in listOf("get_calendar_events", "add_calendar_event") -> "📅 Calendar"
        name in listOf("list_files", "read_file", "write_file", "delete_file") -> "📁 Files"
        name in listOf("save_memory", "get_memory", "list_memories", "delete_memory", "write_workspace_file", "read_workspace_file") -> "🧠 Memory"
        name in listOf("speak", "listen") -> "🎙️ Voice"
        name in listOf("get_device_info", "share_text") -> "📊 Device"
        name in listOf("list_skills", "create_skill") -> "🛠️ Skills"
        else -> "⚙️ Utility"
    }

    // ──────────────────────────────────────────────
    // Execute tool
    // ──────────────────────────────────────────────

    suspend fun execute(toolName: String, params: JSONObject): String = withContext(Dispatchers.Main) {
        try {
            when (toolName) {

                // Screen
                "read_screen" -> DeviceController.readScreen().toString()
                "get_interactive_elements" -> DeviceController.getInteractiveElements().toString()
                "capture_screenshot" -> executeScreenshot()

                // Tap
                "tap" -> DeviceController.tap(params.getDouble("x").toFloat(), params.getDouble("y").toFloat()).toString()
                "tap_text" -> DeviceController.tapText(params.getString("text")).toString()
                "tap_id" -> DeviceController.tapId(params.getString("resource_id")).toString()
                "long_press" -> DeviceController.longPress(params.getDouble("x").toFloat(), params.getDouble("y").toFloat()).toString()

                // Type
                "type_text" -> DeviceController.type(params.getString("text")).toString()
                "tap_and_type" -> DeviceController.tapAndType(
                    params.getDouble("x").toFloat(), params.getDouble("y").toFloat(), params.getString("text")
                ).toString()
                "clear_text" -> DeviceController.type("").toString()

                // Swipe / Scroll
                "swipe" -> DeviceController.swipe(
                    params.getDouble("start_x").toFloat(), params.getDouble("start_y").toFloat(),
                    params.getDouble("end_x").toFloat(), params.getDouble("end_y").toFloat(),
                    params.optLong("duration_ms", 300)
                ).toString()
                "scroll_down" -> DeviceController.scrollDown().toString()
                "scroll_up" -> DeviceController.scrollUp().toString()

                // System
                "press_back" -> DeviceController.pressBack().toString()
                "press_home" -> DeviceController.pressHome().toString()
                "press_recents" -> DeviceController.pressRecents().toString()
                "open_notifications" -> DeviceController.openNotifications().toString()

                // Apps
                "open_app" -> DeviceToolsExtended.openApp(context, params.getString("package_name"))
                "list_apps" -> DeviceToolsExtended.listInstalledApps(context)
                "is_app_installed" -> DeviceToolsExtended.isAppInstalled(context, params.getString("package_name"))
                "open_url" -> DeviceToolsExtended.openUrl(context, params.getString("url"))
                "open_settings" -> DeviceToolsExtended.openSettings(context, params.getString("type"))

                // Contacts
                "search_contacts" -> DeviceToolsExtended.searchContacts(context, params.getString("query"))
                "get_all_contacts" -> DeviceToolsExtended.getAllContacts(context, params.optInt("limit", 50))

                // SMS
                "read_sms" -> DeviceToolsExtended.readSms(
                    context, params.optInt("limit", 20), params.optString("contact", null)
                )
                "send_sms" -> DeviceToolsExtended.sendSms(context, params.getString("phone"), params.getString("message"))

                // Calls
                "get_call_log" -> DeviceToolsExtended.getCallLog(context, params.optInt("limit", 20))
                "dial_number" -> DeviceToolsExtended.dialNumber(context, params.getString("phone"))
                "make_call" -> DeviceToolsExtended.makeCall(context, params.getString("phone"))

                // Calendar
                "get_calendar_events" -> DeviceToolsExtended.getCalendarEvents(context, params.optInt("days_ahead", 7))
                "add_calendar_event" -> DeviceToolsExtended.addCalendarEvent(
                    context,
                    params.getString("title"),
                    params.getLong("start_ms"),
                    params.getLong("end_ms"),
                    params.optString("description", ""),
                    params.optString("location", "")
                )

                // Files
                "list_files" -> DeviceToolsExtended.listFiles(params.optString("path", "/sdcard/"))
                "read_file" -> DeviceToolsExtended.readTextFile(params.getString("path"), params.optInt("max_chars", 5000))
                "write_file" -> DeviceToolsExtended.writeTextFile(params.getString("path"), params.getString("content"))
                "delete_file" -> DeviceToolsExtended.deleteFile(params.getString("path"))

                // Memory
                "save_memory" -> {
                    memoryStore.saveFact(params.getString("key"), params.getString("value"))
                    "✅ Remembered: ${params.getString("key")} = ${params.getString("value")}"
                }
                "get_memory" -> {
                    val value = memoryStore.getFact(params.getString("key"))
                    value ?: "❌ No memory found for key: ${params.getString("key")}"
                }
                "list_memories" -> {
                    val facts = memoryStore.getAllFacts()
                    if (facts.isEmpty()) "No memories stored yet"
                    else facts.entries.joinToString("\n") { "• ${it.key}: ${it.value.value}" }
                }
                "delete_memory" -> {
                    memoryStore.deleteFact(params.getString("key"))
                    "✅ Memory deleted: ${params.getString("key")}"
                }
                "write_workspace_file" -> {
                    memoryStore.writeFile(params.getString("filename"), params.getString("content"))
                    "✅ Workspace file written: ${params.getString("filename")}"
                }
                "read_workspace_file" -> {
                    memoryStore.readFile(params.getString("filename"))
                        ?: "❌ File not found: ${params.getString("filename")}"
                }

                // Voice
                "speak" -> {
                    val text = params.getString("text")
                    withContext(Dispatchers.IO) { voiceEngine.speak(text) }
                    "✅ Spoke: $text"
                }
                "listen" -> {
                    withContext(Dispatchers.Main) { voiceEngine.listen() }
                }

                // Device
                "get_device_info" -> DeviceToolsExtended.getDeviceInfo(context)
                "share_text" -> DeviceToolsExtended.shareText(context, params.getString("text"))

                // Skills
                "list_skills" -> {
                    val skills = skillsEngine.listSkills()
                    if (skills.isEmpty()) "No skills installed"
                    else skills.joinToString("\n") { "• ${it.name}: ${it.description}" }
                }
                "create_skill" -> {
                    skillsEngine.saveSkill(
                        name = params.getString("name"),
                        description = params.getString("description"),
                        content = params.getString("instructions"),
                        triggers = params.getString("triggers").split(",").map { it.trim() }
                    )
                    "✅ Skill created: ${params.getString("name")}"
                }

                // Utility
                "wait" -> {
                    DeviceController.waitForScreen(params.optLong("ms", 1000)).toString()
                }
                "task_done" -> params.optString("message", "Task complete")

                else -> "❌ ERROR: Unknown tool '$toolName'. Available: ${tools.map { it.name }.joinToString(", ")}"
            }
        } catch (e: Exception) {
            "❌ ERROR in $toolName: ${e.message}"
        }
    }

    private suspend fun executeScreenshot(): String {
        if (!ScreenshotEngine.isReady()) {
            return "❌ Screen capture not initialized. The user needs to grant screen capture permission. Ask them to tap the camera icon in the app."
        }
        val result = ScreenshotEngine.capture()
        return if (result.ok && result.bitmap != null) {
            "✅ Screenshot captured (${result.bitmap.width}x${result.bitmap.height}). " +
                    "The image has been captured and can be sent to vision-capable LLMs."
        } else {
            "❌ Screenshot failed: ${result.error}"
        }
    }
}
