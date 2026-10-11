package com.ashorts.blocker.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * In-memory ring buffer (shown in the app's Detection log screen) plus an optional append-only
 * file. Consecutive duplicate matches are suppressed so a scrolling feed does not flood the log.
 * File writes happen on a background thread.
 */
object DetectionLog {

    data class Entry(val timestamp: Long, val detail: String, val screenDetected: Boolean)

    private const val MAX_ENTRIES = 300
    private const val MAX_FILE_BYTES = 256 * 1024

    private val buffer = ArrayDeque<Entry>()
    private val lock = Any()
    private var logFile: File? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var lastKey: String? = null

    fun init(filesDir: File) {
        logFile = File(filesDir, "detection_log.txt")
    }

    fun add(details: List<String>, screenDetected: Boolean) {
        if (details.isEmpty()) return
        val key = (if (screenDetected) "S|" else "T|") + details.joinToString("|")
        synchronized(lock) {
            if (key == lastKey) return
            lastKey = key
        }
        val entry = Entry(System.currentTimeMillis(), details.joinToString(" | "), screenDetected)
        synchronized(lock) {
            while (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
        }
        val file = logFile ?: return
        executor.execute {
            try {
                if (file.length() > MAX_FILE_BYTES) {
                    val old = File(file.parentFile, file.name + ".1")
                    if (old.exists()) old.delete()
                    file.renameTo(old)
                }
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                file.appendText(
                    "${fmt.format(Date(entry.timestamp))} " +
                        "${if (entry.screenDetected) "SCREEN" else "TAB"} ${entry.detail}\n"
                )
            } catch (_: Exception) {
                // Never let logging break blocking.
            }
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList() }

    fun clear() = synchronized(lock) {
        buffer.clear()
        lastKey = null
    }
}
