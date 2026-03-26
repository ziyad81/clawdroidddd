package com.clawdroid.skills

import android.content.Context
import android.util.Log
import com.clawdroid.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SkillsEngine
 *
 * Reusable task scripts — inspired by OpenClaw's AgentSkills system.
 *
 * A "skill" is a Markdown file with:
 *  - YAML frontmatter (name, description, triggers)
 *  - Natural language instructions for the agent
 *  - Optional parameters
 *
 * Example skill (morning-briefing.md):
 * ---
 * name: morning-briefing
 * description: Give a daily morning briefing with weather, calendar, and tasks
 * triggers: ["good morning", "morning briefing", "start my day"]
 * ---
 * 1. Check today's calendar events
 * 2. Read the top 3 unread notifications
 * 3. Check battery level
 * 4. Summarize everything in 3-5 sentences
 *
 * Built-in skills are included at app install.
 * Users can create/edit custom skills from the app.
 * The agent can create new skills automatically.
 */
class SkillsEngine(
    private val context: Context,
    private val memoryStore: MemoryStore
) {

    companion object {
        private const val TAG = "SkillsEngine"

        // Built-in skills installed at first run
        val BUILTIN_SKILLS = mapOf(
            "morning-briefing" to """
---
name: morning-briefing
description: Give a daily morning briefing
triggers: ["good morning", "morning briefing", "start my day", "what's today"]
---
Complete these steps in order:
1. Call get_calendar_events to see today's events
2. Call get_device_info to check battery level
3. Call read_screen to see any notifications on screen
4. Summarize: date, events count, battery, any urgent notifications
5. End with a motivating one-liner

Keep the briefing short — under 5 sentences total.
            """.trimIndent(),

            "send-whatsapp" to """
---
name: send-whatsapp
description: Send a WhatsApp message to a contact
triggers: ["whatsapp", "send message", "message via whatsapp"]
params:
  - name: contact
    description: Name or number to message
  - name: message
    description: The message to send
---
Steps to send a WhatsApp message:
1. Call press_home to go to home screen
2. Call tap_text with text "WhatsApp" to open it, OR call open_app with package "com.whatsapp"
3. Wait 1000ms for app to load
4. Call read_screen to see the current state
5. If on main WhatsApp screen, tap the new message / search icon
6. Type the contact name using type_text
7. Select the contact from search results
8. Tap the message input field
9. Type the message
10. Tap the send button

Verify the message was sent by checking the screen shows it delivered.
            """.trimIndent(),

            "take-screenshot-describe" to """
---
name: take-screenshot-describe
description: Take a screenshot and describe what's on screen
triggers: ["describe screen", "what's on screen", "screenshot", "read screen as image"]
---
1. Call capture_screenshot to get a screenshot
2. Describe in detail: what app is open, what content is visible, any buttons or inputs
3. Note anything important or actionable the user might want to know
4. If there's text on screen, read it out
            """.trimIndent(),

            "remember-fact" to """
---
name: remember-fact
description: Remember something the user tells you
triggers: ["remember", "don't forget", "note that", "save this"]
---
1. Extract the key information from the user's message
2. Call save_memory with a descriptive key and the value
3. Confirm what was saved
4. This will persist across sessions — you'll remember it next time
            """.trimIndent(),

            "battery-check" to """
---
name: battery-check
description: Check battery status and give charging advice
triggers: ["battery", "how much battery", "is it charging"]
---
1. Call get_device_info
2. Report the battery percentage and charging status
3. If under 20%, suggest plugging in
4. If already charging, report estimated time if possible
            """.trimIndent(),

            "open-and-search" to """
---
name: open-and-search
description: Open an app and search for something
triggers: ["search for", "look up", "find in", "google", "youtube search"]
---
1. Determine which app to open (Chrome/YouTube/Play Store/Maps etc.)
2. Call open_app or tap_text to open it
3. Wait for it to load
4. Find the search bar (read_screen)
5. Tap it and type the search query
6. Press Enter or tap the search button
7. Report what results appeared
            """.trimIndent(),

            "daily-report" to """
---
name: daily-report
description: Generate a summary of today's activity
triggers: ["daily report", "what did I do today", "activity summary", "end of day"]
---
1. Call get_calendar_events to see today's events
2. Call read_recent_sms to check messages received today
3. Call get_call_log to see today's calls
4. Summarize everything in a neat daily report format
5. Include: meetings attended, messages received, calls made/missed
            """.trimIndent()
        )
    }

    // ──────────────────────────────────────────────
    // Install built-ins on first run
    // ──────────────────────────────────────────────

    suspend fun installBuiltinSkills() = withContext(Dispatchers.IO) {
        val existing = memoryStore.listSkills()
        BUILTIN_SKILLS.forEach { (name, content) ->
            if (name !in existing) {
                memoryStore.saveSkill(name, content)
                Log.d(TAG, "Installed built-in skill: $name")
            }
        }
    }

    // ──────────────────────────────────────────────
    // Find matching skill for a user query
    // ──────────────────────────────────────────────

    suspend fun findMatchingSkill(userQuery: String): Skill? = withContext(Dispatchers.IO) {
        val queryLower = userQuery.lowercase()
        val allSkills = listSkills()

        // Check trigger words
        allSkills.firstOrNull { skill ->
            skill.triggers.any { trigger ->
                queryLower.contains(trigger.lowercase())
            }
        }
    }

    // ──────────────────────────────────────────────
    // List all skills
    // ──────────────────────────────────────────────

    suspend fun listSkills(): List<Skill> = withContext(Dispatchers.IO) {
        val names = memoryStore.listSkills()
        names.mapNotNull { name ->
            memoryStore.loadSkill(name)?.let { parseSkill(name, it) }
        }
    }

    suspend fun getSkill(name: String): Skill? = withContext(Dispatchers.IO) {
        memoryStore.loadSkill(name)?.let { parseSkill(name, it) }
    }

    // ──────────────────────────────────────────────
    // Save a new skill (agent can create these)
    // ──────────────────────────────────────────────

    suspend fun saveSkill(name: String, description: String, content: String, triggers: List<String>) {
        val markdown = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: $description")
            appendLine("triggers: [${triggers.joinToString(", ") { "\"$it\"" }}]")
            appendLine("---")
            appendLine(content)
        }
        memoryStore.saveSkill(name, markdown)
        Log.d(TAG, "Skill saved: $name")
    }

    // ──────────────────────────────────────────────
    // Build skills section for LLM system prompt
    // ──────────────────────────────────────────────

    suspend fun buildSystemPromptSection(): String = withContext(Dispatchers.IO) {
        val skills = listSkills()
        if (skills.isEmpty()) return@withContext ""
        buildString {
            appendLine("## AVAILABLE SKILLS")
            appendLine("You have these reusable skills. Call them when the task matches:")
            appendLine()
            skills.forEach { skill ->
                appendLine("### ${skill.name}")
                appendLine(skill.description)
                if (skill.triggers.isNotEmpty()) {
                    appendLine("Triggers: ${skill.triggers.joinToString(", ")}")
                }
                appendLine()
            }
        }
    }

    // ──────────────────────────────────────────────
    // Parse skill from Markdown
    // ──────────────────────────────────────────────

    private fun parseSkill(name: String, markdown: String): Skill {
        val lines = markdown.lines()
        val frontmatterEnd = lines.drop(1).indexOfFirst { it.trim() == "---" }
        val content = if (frontmatterEnd >= 0) {
            lines.drop(frontmatterEnd + 2).joinToString("\n")
        } else markdown

        // Parse YAML frontmatter (simple regex approach)
        val descriptionMatch = Regex("description:\\s*(.+)").find(markdown)?.groupValues?.get(1)?.trim()
        val triggersMatch = Regex("triggers:\\s*\\[(.+?)\\]").find(markdown)?.groupValues?.get(1)
        val triggers = triggersMatch
            ?.split(",")
            ?.map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
            ?: emptyList()

        return Skill(
            name = name,
            description = descriptionMatch ?: "",
            triggers = triggers,
            content = content.trim()
        )
    }
}

data class Skill(
    val name: String,
    val description: String,
    val triggers: List<String>,
    val content: String
)
