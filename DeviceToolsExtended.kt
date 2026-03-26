package com.clawdroid.tools

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DeviceToolsExtended
 *
 * Full device access toolkit — everything the agent can do beyond screen control.
 *
 * Tools:
 *  - Contacts: list, search, add, call
 *  - SMS: read, send
 *  - Call log: recent calls
 *  - Calendar: read events, add events
 *  - Files: list, read, write, delete in user directories
 *  - Apps: list installed, open app, check if installed
 *  - Notifications: read active notifications
 *  - System: WiFi, Bluetooth, brightness, volume, airplane mode
 *  - Device info: battery, storage, screen size
 *
 * All methods return structured JSON strings for the LLM to read.
 */
object DeviceToolsExtended {

    private const val TAG = "DeviceTools"

    // ──────────────────────────────────────────────
    // CONTACTS
    // ──────────────────────────────────────────────

    fun searchContacts(context: Context, query: String): String {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return error("READ_CONTACTS permission not granted")
        }
        return try {
            val results = JSONArray()
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            cursor?.use {
                while (it.moveToNext() && results.length() < 20) {
                    results.put(JSONObject().apply {
                        put("name", it.getString(0) ?: "")
                        put("phone", it.getString(1) ?: "")
                        put("type", phoneTypeLabel(it.getInt(2)))
                    })
                }
            }
            if (results.length() == 0) "No contacts found matching: $query"
            else results.toString(2)
        } catch (e: Exception) {
            error("Failed to search contacts: ${e.message}")
        }
    }

    fun getAllContacts(context: Context, limit: Int = 50): String {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return error("READ_CONTACTS permission not granted")
        }
        return try {
            val results = JSONArray()
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            cursor?.use {
                while (it.moveToNext() && results.length() < limit) {
                    results.put(JSONObject().apply {
                        put("name", it.getString(0) ?: "")
                        put("phone", it.getString(1) ?: "")
                    })
                }
            }
            "Found ${results.length()} contacts:\n${results.toString(2)}"
        } catch (e: Exception) {
            error("Contacts read failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // SMS
    // ──────────────────────────────────────────────

    fun readSms(context: Context, limit: Int = 20, filterContact: String? = null): String {
        if (!hasPermission(context, Manifest.permission.READ_SMS)) {
            return error("READ_SMS permission not granted")
        }
        return try {
            val results = JSONArray()
            val selection = if (filterContact != null) {
                "${Telephony.Sms.ADDRESS} LIKE ?"
            } else null
            val selectionArgs = if (filterContact != null) arrayOf("%$filterContact%") else null

            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                selection, selectionArgs,
                "${Telephony.Sms.DATE} DESC"
            )
            cursor?.use {
                while (it.moveToNext() && results.length() < limit) {
                    val type = if (it.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "sent" else "received"
                    results.put(JSONObject().apply {
                        put("from", it.getString(0) ?: "")
                        put("body", it.getString(1) ?: "")
                        put("time", formatTimestamp(it.getLong(2)))
                        put("type", type)
                    })
                }
            }
            if (results.length() == 0) "No SMS messages found"
            else "Recent SMS (${results.length()}):\n${results.toString(2)}"
        } catch (e: Exception) {
            error("SMS read failed: ${e.message}")
        }
    }

    fun sendSms(context: Context, phoneNumber: String, message: String): String {
        if (!hasPermission(context, Manifest.permission.SEND_SMS)) {
            return error("SEND_SMS permission not granted")
        }
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
                ?: SmsManager.getDefault()
            // Split if message is too long
            if (message.length > 160) {
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }
            "✅ SMS sent to $phoneNumber"
        } catch (e: Exception) {
            error("Failed to send SMS: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // CALL LOG
    // ──────────────────────────────────────────────

    fun getCallLog(context: Context, limit: Int = 20): String {
        if (!hasPermission(context, Manifest.permission.READ_CALL_LOG)) {
            return error("READ_CALL_LOG permission not granted")
        }
        return try {
            val results = JSONArray()
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                while (it.moveToNext() && results.length() < limit) {
                    val callType = when (it.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        else -> "unknown"
                    }
                    results.put(JSONObject().apply {
                        put("name", it.getString(0) ?: "Unknown")
                        put("number", it.getString(1) ?: "")
                        put("type", callType)
                        put("time", formatTimestamp(it.getLong(3)))
                        put("duration_sec", it.getLong(4))
                    })
                }
            }
            "Recent calls (${results.length()}):\n${results.toString(2)}"
        } catch (e: Exception) {
            error("Call log read failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // CALENDAR
    // ──────────────────────────────────────────────

    fun getCalendarEvents(context: Context, daysAhead: Int = 7): String {
        if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) {
            return error("READ_CALENDAR permission not granted")
        }
        return try {
            val results = JSONArray()
            val now = System.currentTimeMillis()
            val end = now + (daysAhead * 24 * 60 * 60 * 1000L)

            val cursor = context.contentResolver.query(
                android.provider.CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    android.provider.CalendarContract.Events.TITLE,
                    android.provider.CalendarContract.Events.DTSTART,
                    android.provider.CalendarContract.Events.DTEND,
                    android.provider.CalendarContract.Events.DESCRIPTION,
                    android.provider.CalendarContract.Events.EVENT_LOCATION
                ),
                "${android.provider.CalendarContract.Events.DTSTART} >= ? AND ${android.provider.CalendarContract.Events.DTSTART} <= ?",
                arrayOf(now.toString(), end.toString()),
                android.provider.CalendarContract.Events.DTSTART
            )
            cursor?.use {
                while (it.moveToNext()) {
                    results.put(JSONObject().apply {
                        put("title", it.getString(0) ?: "")
                        put("start", formatTimestamp(it.getLong(1)))
                        put("end", formatTimestamp(it.getLong(2)))
                        put("description", it.getString(3) ?: "")
                        put("location", it.getString(4) ?: "")
                    })
                }
            }
            if (results.length() == 0) "No calendar events in the next $daysAhead days"
            else "Upcoming events (${results.length()}):\n${results.toString(2)}"
        } catch (e: Exception) {
            error("Calendar read failed: ${e.message}")
        }
    }

    fun addCalendarEvent(
        context: Context,
        title: String,
        startMs: Long,
        endMs: Long,
        description: String = "",
        location: String = ""
    ): String {
        if (!hasPermission(context, Manifest.permission.WRITE_CALENDAR)) {
            return error("WRITE_CALENDAR permission not granted")
        }
        return try {
            val values = ContentValues().apply {
                put(android.provider.CalendarContract.Events.TITLE, title)
                put(android.provider.CalendarContract.Events.DTSTART, startMs)
                put(android.provider.CalendarContract.Events.DTEND, endMs)
                put(android.provider.CalendarContract.Events.DESCRIPTION, description)
                put(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
                put(android.provider.CalendarContract.Events.CALENDAR_ID, 1)
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            context.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
            "✅ Calendar event added: $title at ${formatTimestamp(startMs)}"
        } catch (e: Exception) {
            error("Failed to add calendar event: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // INSTALLED APPS
    // ──────────────────────────────────────────────

    fun listInstalledApps(context: Context): String {
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 } // user apps only
                .sortedBy { pm.getApplicationLabel(it).toString() }

            val results = JSONArray()
            apps.take(100).forEach { app ->
                results.put(JSONObject().apply {
                    put("name", pm.getApplicationLabel(app).toString())
                    put("package", app.packageName)
                })
            }
            "Installed apps (${apps.size} total, showing ${results.length()}):\n${results.toString(2)}"
        } catch (e: Exception) {
            error("Failed to list apps: ${e.message}")
        }
    }

    fun openApp(context: Context, packageName: String): String {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return error("App not found: $packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "✅ Opened app: $packageName"
        } catch (e: Exception) {
            error("Failed to open app: ${e.message}")
        }
    }

    fun isAppInstalled(context: Context, packageName: String): String {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            "✅ App is installed: $packageName"
        } catch (e: PackageManager.NameNotFoundException) {
            "❌ App is NOT installed: $packageName"
        }
    }

    // ──────────────────────────────────────────────
    // FILES
    // ──────────────────────────────────────────────

    fun listFiles(path: String = "/sdcard/"): String {
        return try {
            val dir = File(path)
            if (!dir.exists()) return error("Path not found: $path")
            val files = dir.listFiles() ?: return "Empty directory"
            val results = JSONArray()
            files.sortedWith(compareBy({ !it.isDirectory }, { it.name })).take(50).forEach { f ->
                results.put(JSONObject().apply {
                    put("name", f.name)
                    put("type", if (f.isDirectory) "dir" else "file")
                    put("size", if (f.isFile) f.length() else 0)
                    put("modified", formatTimestamp(f.lastModified()))
                })
            }
            "Files in $path (${files.size} items):\n${results.toString(2)}"
        } catch (e: Exception) {
            error("Failed to list files: ${e.message}")
        }
    }

    fun readTextFile(path: String, maxChars: Int = 5000): String {
        return try {
            val file = File(path)
            if (!file.exists()) return error("File not found: $path")
            if (!file.isFile) return error("Not a file: $path")
            val content = file.readText()
            if (content.length > maxChars) {
                "File: $path (truncated to $maxChars chars)\n---\n${content.take(maxChars)}\n[... ${content.length - maxChars} more chars]"
            } else {
                "File: $path\n---\n$content"
            }
        } catch (e: Exception) {
            error("Failed to read file: ${e.message}")
        }
    }

    fun writeTextFile(path: String, content: String): String {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            "✅ File written: $path (${content.length} chars)"
        } catch (e: Exception) {
            error("Failed to write file: ${e.message}")
        }
    }

    fun deleteFile(path: String): String {
        return try {
            val file = File(path)
            if (!file.exists()) return error("File not found: $path")
            if (file.delete()) "✅ Deleted: $path"
            else error("Failed to delete: $path")
        } catch (e: Exception) {
            error("Failed to delete: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // DEVICE INFO
    // ──────────────────────────────────────────────

    fun getDeviceInfo(context: Context): String {
        return try {
            val batteryManager = context.getSystemService(android.os.BatteryManager::class.java)
            val batteryLevel = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val isCharging = batteryManager?.isCharging ?: false

            val statFs = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
            val totalStorage = statFs.totalBytes / (1024 * 1024 * 1024)
            val freeStorage = statFs.freeBytes / (1024 * 1024 * 1024)

            val runtime = Runtime.getRuntime()
            val totalRam = runtime.maxMemory() / (1024 * 1024)
            val usedRam = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

            JSONObject().apply {
                put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                put("android_version", android.os.Build.VERSION.RELEASE)
                put("sdk_version", android.os.Build.VERSION.SDK_INT)
                put("battery_percent", batteryLevel)
                put("battery_charging", isCharging)
                put("storage_total_gb", totalStorage)
                put("storage_free_gb", freeStorage)
                put("ram_total_mb", totalRam)
                put("ram_used_mb", usedRam)
            }.toString(2)
        } catch (e: Exception) {
            error("Failed to get device info: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // OPEN SYSTEM SETTINGS
    // ──────────────────────────────────────────────

    fun openSettings(context: Context, settingType: String): String {
        val action = when (settingType.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "network", "data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "✅ Opened $settingType settings"
        } catch (e: Exception) {
            error("Failed to open settings: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // DIAL / CALL
    // ──────────────────────────────────────────────

    fun dialNumber(context: Context, phoneNumber: String): String {
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "✅ Dialing: $phoneNumber"
        } catch (e: Exception) {
            error("Failed to dial: ${e.message}")
        }
    }

    fun makeCall(context: Context, phoneNumber: String): String {
        if (!hasPermission(context, Manifest.permission.CALL_PHONE)) {
            return error("CALL_PHONE permission not granted. Using dial intent instead...")
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "✅ Calling: $phoneNumber"
        } catch (e: Exception) {
            error("Failed to call: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // SHARE / SEND INTENT
    // ──────────────────────────────────────────────

    fun shareText(context: Context, text: String, chooserTitle: String = "Share via"): String {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "✅ Share dialog opened"
        } catch (e: Exception) {
            error("Failed to share: ${e.message}")
        }
    }

    fun openUrl(context: Context, url: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "✅ Opened URL: $url"
        } catch (e: Exception) {
            error("Failed to open URL: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun phoneTypeLabel(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        else -> "other"
    }

    private fun formatTimestamp(ms: Long): String =
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(Date(ms))

    private fun error(msg: String): String = "❌ ERROR: $msg"
}
