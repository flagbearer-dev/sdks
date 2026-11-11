package dev.flagbearer.android

import java.net.HttpURLConnection
import java.net.URL

// The SDK's real network code. Nothing here needs Android, so its tests run
// against a real HTTP server on the JVM.

/** Blocks until the server answers. Throws IOException when it can't be reached. */
internal fun httpPost(
    url: String,
    headers: Map<String, String>,
    body: String,
    timeoutMs: Int,
): HttpResponse {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        connection.outputStream.use { it.write(body.toByteArray()) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return HttpResponse(status, text, connection.getHeaderField("Retry-After"))
    } finally {
        connection.disconnect()
    }
}

// Reads the server's stream of flag changes on its own thread, a line at a
// time, until it ends, fails, goes silent or is closed.
internal class EventStream(private val url: String, private val events: StreamEvents) : Runnable {
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null

    override fun run() {
        var status: Int? = null
        var retryAfter: String? = null
        try {
            val open = URL(url).openConnection() as HttpURLConnection
            connection = open
            open.connectTimeout = 5000
            // The server pings every 15 seconds, so this is three missed pings.
            open.readTimeout = 45_000
            open.instanceFollowRedirects = false
            open.useCaches = false
            open.setRequestProperty("Accept", "text/event-stream")
            if (closed) return
            if (open.responseCode != 200) {
                status = open.responseCode
                retryAfter = open.getHeaderField("Retry-After")
                return
            }
            if (!closed) events.open()
            open.inputStream.bufferedReader().use { reader ->
                while (!closed) {
                    events.line(reader.readLine() ?: break)
                }
            }
        } catch (error: Exception) {
            // Closed, unreachable or silent: all end the stream the same way.
        } finally {
            connection?.disconnect()
            if (!closed) events.closed(status, retryAfter)
        }
    }

    fun close() {
        closed = true
        // Unblocks a read that's waiting for the next line.
        connection?.disconnect()
    }
}
