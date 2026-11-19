package com.example.openslmserver

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class ServerLog(directory: File) {
    private val current = File(directory, "openslmserver.log")
    private val previous = File(directory, "openslmserver.previous.log")

    fun hasEntries(): Boolean = current.length() > 0 || previous.length() > 0

    fun record(event: String) {
        synchronized(lock) {
            val line = "${timestamp.format(Date())} $event\n".toByteArray(Charsets.UTF_8)
            try {
                if (current.length() + line.size > MAX_FILE_BYTES && !current.renameTo(previous)) return
                current.appendBytes(line)
            } catch (_: IOException) {
                // Logging must not interrupt server startup or requests.
            }
        }
    }

    fun snapshot(): ByteArray = synchronized(lock) {
        val output = ByteArrayOutputStream()
        for (file in listOf(previous, current)) {
            if (file.isFile) file.inputStream().use { it.copyTo(output) }
        }
        output.toByteArray()
    }

    companion object {
        private const val MAX_FILE_BYTES = 1024L * 1024L
        private val lock = Any()
        private val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
