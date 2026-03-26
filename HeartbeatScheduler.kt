package com.clawdroid.scheduler

import android.content.Context
import android.util.Log
import androidx.work.*
import com.clawdroid.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * HeartbeatScheduler
 *
 * Proactive background scheduler — inspired by OpenClaw's heartbeat system.
 *
 * OpenClaw runs a heartbeat at configurable intervals (default 30 min).
 * At each heartbeat, the agent reads HEARTBEAT.md from the workspace
 * and decides if any items need action.
 *
 * ClawDroid does the same using Android WorkManager:
 *  - Schedules periodic background work
 *  - Reads HEARTBEAT.md from workspace
 *  - If checklist has pending items → runs agent on them
 *  - Sends a notification with the result
 *
 * Example HEARTBEAT.md:
 * ## Daily Checklist
 * - [ ] Check calendar for today's events and notify if any in next hour
 * - [ ] Monitor battery — alert if below 20%
 * - [ ] Check for unread messages and summarize
 * - [x] Morning briefing (done)
 */
object HeartbeatScheduler {

    private const val TAG = "HeartbeatScheduler"
    private const val WORK_NAME = "clawdroid_heartbeat"

    fun schedule(context: Context, intervalMinutes: Long = 30) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // works offline
            .build()

        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
            intervalMinutes, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES  // flex period
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

        Log.i(TAG, "Heartbeat scheduled every $intervalMinutes minutes")
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.i(TAG, "Heartbeat cancelled")
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<HeartbeatWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
        Log.d(TAG, "Immediate heartbeat queued")
    }
}

// ──────────────────────────────────────────────
// The Worker that runs at each heartbeat
// ──────────────────────────────────────────────

class HeartbeatWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "HeartbeatWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔔 Heartbeat running...")

        try {
            val memoryStore = MemoryStore(context)
            val checklist = memoryStore.getHeartbeatChecklist()

            if (checklist.isNullOrBlank()) {
                Log.d(TAG, "No heartbeat checklist found — skipping")
                return@withContext Result.success()
            }

            // Find unchecked items
            val pendingItems = checklist.lines()
                .filter { it.trim().startsWith("- [ ]") }
                .map { it.trim().removePrefix("- [ ]").trim() }

            if (pendingItems.isEmpty()) {
                Log.d(TAG, "All heartbeat items complete — HEARTBEAT_OK")
                return@withContext Result.success()
            }

            Log.d(TAG, "Found ${pendingItems.size} pending heartbeat items")

            // Run the agent on each pending item
            // Note: This is intentionally lightweight — no UI, just background
            // For now, log the items. Full agent execution is done via AgentForegroundService.
            pendingItems.forEach { item ->
                Log.d(TAG, "Heartbeat item: $item")
                // TODO: When service is running, dispatch to AgentForegroundService
                // AgentForegroundService.runTask(context, item)
            }

            // Show notification with pending items
            showHeartbeatNotification(pendingItems)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat failed", e)
            Result.retry()
        }
    }

    private fun showHeartbeatNotification(items: List<String>) {
        try {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            val channelId = "clawdroid_heartbeat"

            // Create channel if needed
            val channel = android.app.NotificationChannel(
                channelId, "ClawDroid Heartbeat",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Periodic agent check-ins" }
            nm.createNotificationChannel(channel)

            val summary = items.take(3).joinToString("\n") { "• $it" }
            val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setContentTitle("🦞 ClawDroid Heartbeat")
                .setContentText("${items.size} pending item(s)")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(summary))
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()

            nm.notify(2001, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show heartbeat notification", e)
        }
    }
}
