package com.clawdroid.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MemoryStore
 *
 * Persistent memory system inspired by OpenClaw's local-first Markdown/JSON approach.
 *
 * Three types of memory:
 *
 * 1. SHORT-TERM (session)
 *    Conversation history for the current task.
 *    Lives in RAM, cleared when task ends.
 *
 * 2. LONG-TERM (episodic)
 *    Key facts the agent learns about the user.
 *    "User's name is Ziyad", "prefers dark mode", "alarm is 7am"
 *    Stored as: /memory/facts.json
 *
 * 3. TASK HISTORY (logs)
 *    Log of past tasks and outcomes.
 *    "Open WhatsApp → Sent message to John ✅"
 *    Stored as: /memory/history_YYYY-MM.json
 *
 * 4. WORKSPACE (files)
 *    Files the agent creates/manages.
 *    Stored as: /workspace/<filename>
 *
 * Like OpenClaw, memory is stored as plain files — inspectable, backuppable, editable by user.
 */
class MemoryStore(context: Context) {

    companion object {
        private const val TAG = "MemoryStore"
        private const val MAX_HISTORY_ENTRIES = 500
        private const val MAX_FACTS = 200
    }

    private val baseDir = File(context.filesDir, "clawdroid")
    private val memoryDir = File(baseDir, "memory")
    private val workspaceDir = File(baseDir, "workspace")
    private val skillsDir = File(baseDir, "skills")

    init {
        memoryDir.mkdirs()
        workspaceDir.mkdirs()
        skillsDir.mkdirs()
        Log.d(TAG, "MemoryStore initialized at: ${baseDir.absolutePath}")
    }

    // ──────────────────────────────────────────────
    // LONG-TERM FACTS
    // ──────────────────────────────────────────────

    private val factsFile = File(memoryDir, "facts.json")

    suspend fun saveFact(key: String, value: String) = withContext(Dispatchers.IO) {
        val facts = loadFacts().toMutableMap()
        facts[key] = MemoryFact(
            key = key,
            value = value,
            updatedAt = System.currentTimeMillis()
        )
        val json = JSONObject()
        facts.forEach { (k, v) ->
            json.put(k, JSONObject().apply {
                put("value", v.value)
                put("updatedAt", v.updatedAt)
            })
        }
        factsFile.writeText(json.toString(2))
        Log.d(TAG, "Fact saved: $key = $value")
    }

    suspend fun getFact(key: String): String? = withContext(Dispatchers.IO) {
        loadFacts()[key]?.value
    }

    suspend fun getAllFacts(): Map<String, MemoryFact> = withContext(Dispatchers.IO) {
        loadFacts()
    }

    suspend fun deleteFact(key: String) = withContext(Dispatchers.IO) {
        val facts = loadFacts().toMutableMap()
        facts.remove(key)
        val json = JSONObject()
        facts.forEach { (k, v) ->
            json.put(k, JSONObject().apply {
                put("value", v.value)
                put("updatedAt", v.updatedAt)
            })
        }
        factsFile.writeText(json.toString(2))
    }

    private fun loadFacts(): Map<String, MemoryFact> {
        if (!factsFile.exists()) return emptyMap()
        return try {
            val json = JSONObject(factsFile.readText())
            val result = mutableMapOf<String, MemoryFact>()
            json.keys().forEach { key ->
                val obj = json.getJSONObject(key)
                result[key] = MemoryFact(
                    key = key,
                    value = obj.getString("value"),
                    updatedAt = obj.optLong("updatedAt")
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load facts", e)
            emptyMap()
        }
    }

    // Build a compact summary of all facts for the LLM system prompt
    suspend fun buildFactsSummary(): String = withContext(Dispatchers.IO) {
        val facts = loadFacts()
        if (facts.isEmpty()) return@withContext ""
        val sb = StringBuilder("## What I know about you:\n")
        facts.values.sortedByDescending { it.updatedAt }.forEach {
            sb.appendLine("- ${it.key}: ${it.value}")
        }
        sb.toString()
    }

    // ──────────────────────────────────────────────
    // TASK HISTORY
    // ──────────────────────────────────────────────

    private fun historyFile(): File {
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        return File(memoryDir, "history_$month.json")
    }

    suspend fun logTask(task: String, result: String, success: Boolean) = withContext(Dispatchers.IO) {
        val file = historyFile()
        val history = loadHistory(file).toMutableList()
        history.add(TaskEntry(
            task = task,
            result = result.take(500),
            success = success,
            timestamp = System.currentTimeMillis()
        ))
        // Keep last N entries
        if (history.size > MAX_HISTORY_ENTRIES) {
            history.removeAt(0)
        }
        val arr = JSONArray()
        history.forEach { entry ->
            arr.put(JSONObject().apply {
                put("task", entry.task)
                put("result", entry.result)
                put("success", entry.success)
                put("timestamp", entry.timestamp)
            })
        }
        file.writeText(arr.toString(2))
    }

    suspend fun getRecentHistory(n: Int = 10): List<TaskEntry> = withContext(Dispatchers.IO) {
        loadHistory(historyFile()).takeLast(n)
    }

    private fun loadHistory(file: File): List<TaskEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TaskEntry(
                    task = obj.getString("task"),
                    result = obj.getString("result"),
                    success = obj.getBoolean("success"),
                    timestamp = obj.getLong("timestamp")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load history", e)
            emptyList()
        }
    }

    // ──────────────────────────────────────────────
    // WORKSPACE FILES
    // ──────────────────────────────────────────────

    suspend fun writeFile(filename: String, content: String) = withContext(Dispatchers.IO) {
        val file = File(workspaceDir, filename)
        file.parentFile?.mkdirs()
        file.writeText(content)
        Log.d(TAG, "Workspace file written: $filename (${content.length} chars)")
    }

    suspend fun readFile(filename: String): String? = withContext(Dispatchers.IO) {
        val file = File(workspaceDir, filename)
        if (!file.exists()) null else file.readText()
    }

    suspend fun listFiles(subdir: String = ""): List<String> = withContext(Dispatchers.IO) {
        val dir = if (subdir.isBlank()) workspaceDir else File(workspaceDir, subdir)
        dir.listFiles()?.map { it.name } ?: emptyList()
    }

    suspend fun deleteFile(filename: String): Boolean = withContext(Dispatchers.IO) {
        File(workspaceDir, filename).delete()
    }

    // ──────────────────────────────────────────────
    // HEARTBEAT CHECKLIST (like OpenClaw's HEARTBEAT.md)
    // ──────────────────────────────────────────────

    private val heartbeatFile = File(workspaceDir, "HEARTBEAT.md")

    suspend fun getHeartbeatChecklist(): String? = withContext(Dispatchers.IO) {
        if (!heartbeatFile.exists()) null else heartbeatFile.readText()
    }

    suspend fun updateHeartbeat(content: String) = withContext(Dispatchers.IO) {
        heartbeatFile.writeText(content)
    }

    // ──────────────────────────────────────────────
    // Skills storage
    // ──────────────────────────────────────────────

    suspend fun saveSkill(name: String, content: String) = withContext(Dispatchers.IO) {
        File(skillsDir, "$name.md").writeText(content)
        Log.d(TAG, "Skill saved: $name")
    }

    suspend fun loadSkill(name: String): String? = withContext(Dispatchers.IO) {
        val file = File(skillsDir, "$name.md")
        if (!file.exists()) null else file.readText()
    }

    suspend fun listSkills(): List<String> = withContext(Dispatchers.IO) {
        skillsDir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    // ──────────────────────────────────────────────
    // Export everything (for backup)
    // ──────────────────────────────────────────────

    fun getBaseDir(): File = baseDir
}

// ──────────────────────────────────────────────
// Data classes
// ──────────────────────────────────────────────

data class MemoryFact(
    val key: String,
    val value: String,
    val updatedAt: Long = 0L
)

data class TaskEntry(
    val task: String,
    val result: String,
    val success: Boolean,
    val timestamp: Long
) {
    fun formattedTime(): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(timestamp))
}
