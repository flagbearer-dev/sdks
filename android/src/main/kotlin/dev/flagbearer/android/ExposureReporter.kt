package dev.flagbearer.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

internal class Evaluation(
    val value: Boolean,
    val reason: String,
    val configurationVersion: Int?,
)

private const val WINDOW_MS = 30_000L

// One upload per bucket; sending more often only splits a bucket into more uploads.
private const val FLUSH_MS = 30_000L
private const val MAX_RECORDS = 1000
private const val BATCH_SIZE = 100

private class Exposure(
    val flagKey: String,
    val result: Boolean,
    val reason: String,
    val configurationVersion: Int?,
    val bucket: String,
    var checkCount: Int,
    val firstCheckedAt: String,
    var lastCheckedAt: String,
)

// One upload's contents, fixed when sealed, so a retry sends exactly the same
// batch and the server can recognize it by batchId.
private class SealedBatch(val batchId: String, val user: User, val events: MutableList<Exposure>)

/**
 * Counts flag checks and uploads them in batches, so the dashboard can show
 * which flags are in use. Checks are counted per flag, user, value and
 * 30-second window; nothing is sent per check.
 */
internal class ExposureReporter(
    private val platform: Platform,
    private val url: String,
    private val key: String,
    private val appVersion: String?,
) {
    private val lock = Any()
    private val reporterId = platform.randomId()
    private val active = LinkedHashMap<String, Pair<User, Exposure>>()
    private val sealed = ArrayList<SealedBatch>()
    private var sealedEvents = 0
    private var droppedChecks = 0L
    private var reportedDroppedChecks = 0L
    private var sending = false
    private val waiting = ArrayList<(Throwable?) -> Unit>()
    private var closed = false
    private var retryAt = 0L
    private var failures = 0
    private var cancelTimer: (() -> Unit)? = null

    init {
        tick()
    }

    private fun tick() {
        cancelTimer = platform.schedule(FLUSH_MS) {
            if (closed) return@schedule
            if (platform.now() >= retryAt) flush(null)
            tick()
        }
    }

    private fun iso(time: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(time))
    }

    fun record(flagKey: String, user: User, details: Evaluation) {
        synchronized(lock) {
            if (closed) return
            if (flagKey.isEmpty() || flagKey.length > 256 || (user.key?.length ?: 0) > 256) {
                droppedChecks++
                return
            }
            val now = platform.now()
            val bucket = iso(now / WINDOW_MS * WINDOW_MS)
            val email = user.email
                ?.takeIf { it.length <= 254 }
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotEmpty() }
            val userKey = user.key?.takeIf { it.isNotEmpty() }
            val id = listOf(
                flagKey, userKey, email, details.value, details.reason,
                details.configurationVersion, bucket,
            ).joinToString("\u0000")
            val existing = active[id]
            if (existing != null && existing.second.checkCount < 1_000_000_000) {
                existing.second.checkCount++
                existing.second.lastCheckedAt = iso(now)
                return
            }
            if (existing != null || active.size + sealedEvents >= MAX_RECORDS) {
                droppedChecks++
                return
            }
            active[id] = User(userKey, email) to Exposure(
                flagKey, details.value, details.reason, details.configurationVersion,
                bucket, 1, iso(now), iso(now),
            )
    
        }
    }

    // identify() can switch users mid-session, so a batch never mixes users.
    private fun seal() {
        val open = HashMap<User, SealedBatch>()
        for ((user, event) in active.values) {
            var batch = open[user]
            if (batch == null || batch.events.size >= BATCH_SIZE) {
                batch = SealedBatch(platform.randomId(), user, ArrayList())
                open[user] = batch
                sealed.add(batch)
            }
            batch.events.add(event)
            sealedEvents++
        }
        active.clear()
    }

    private fun settle(batch: SealedBatch) {
        if (sealed.firstOrNull() !== batch) return
        sealed.removeAt(0)
        sealedEvents -= batch.events.size
    }

    /** Uploads everything pending. [done] gets null, or why the upload failed. */
    fun flush(done: ((Throwable?) -> Unit)?) {
        synchronized(lock) {
            done?.let(waiting::add)
            if (sending) return
            sending = true
        }
        platform.io {
            val failure = runCatching { send() }.exceptionOrNull()
            val finished = synchronized(lock) {
                sending = false
                if (failure == null) {
                    failures = 0
                } else {
                    failures++
                    val backoff = minOf(60_000L, 1000L shl minOf(failures, 6))
                    retryAt = maxOf(retryAt, platform.now() + backoff)
                }
                ArrayList(waiting).also { waiting.clear() }
            }
            finished.forEach { it(failure) }
        }
    }

    private fun send() {
        synchronized(lock) { seal() }
        while (true) {
            val batch: SealedBatch
            val dropped: Long
            synchronized(lock) {
                if (sealed.isEmpty() && droppedChecks <= reportedDroppedChecks) return
                // Only lost checks to report: an empty batch carries the count.
                batch = sealed.firstOrNull()
                    ?: SealedBatch(platform.randomId(), User(), ArrayList())
                dropped = droppedChecks
            }
            val checks = batch.events.sumOf { it.checkCount.toLong() }
            val response = platform.post(
                url,
                mapOf("Authorization" to "Bearer $key"),
                body(batch, dropped),
                10_000,
            )
            synchronized(lock) {
                if (response.status !in 200..299) {
                    val retryAfter = response.retryAfter?.toDoubleOrNull()
                    if (retryAfter != null && retryAfter > 0) {
                        val wait = (minOf(retryAfter, 3600.0) * 1000).toLong()
                        retryAt = maxOf(retryAt, platform.now() + wait)
                    }
                    // The server will never take this batch, so stop resending it.
                    if (response.status in 400..499 && response.status != 429) {
                        droppedChecks += checks
                        settle(batch)
                        retryAt = platform.now() + 60_000
                    }
                    throw FlagbearerException("Exposure upload failed (${response.status})")
                }
                val receipt = runCatching { JSONObject(response.body) }.getOrNull()
                val accepted = receipt?.optLong("accepted", -1) ?: -1
                val rejected = receipt?.optLong("rejectedChecks", -1) ?: -1
                if (accepted !in 0..batch.events.size || rejected !in 0..checks) {
                    throw FlagbearerException("Invalid exposure upload receipt")
                }
                droppedChecks += rejected
                settle(batch)
                reportedDroppedChecks = maxOf(reportedDroppedChecks, dropped)
            }
        }
    }

    private fun body(batch: SealedBatch, dropped: Long): String {
        val events = JSONArray()
        for (event in batch.events) {
            events.put(
                JSONObject()
                    .put("flagKey", event.flagKey)
                    .put("result", event.result)
                    .put("reason", event.reason)
                    .put("configurationVersion", event.configurationVersion ?: JSONObject.NULL)
                    .put("bucket", event.bucket)
                    .put("checkCount", event.checkCount)
                    .put("firstCheckedAt", event.firstCheckedAt)
                    .put("lastCheckedAt", event.lastCheckedAt),
            )
        }
        val user = JSONObject()
        batch.user.key?.let { user.put("key", it) }
        batch.user.email?.let { user.put("email", it) }
        val body = JSONObject()
            .put("batchId", batch.batchId)
            .put("reporterId", reporterId)
            .put("user", user)
            .put("events", events)
            .put("droppedChecks", dropped)
            .put("sdk", "android")
            .put("sdkVersion", platform.sdkVersion)
        appVersion?.let { body.put("appVersion", it.take(100)) }
        return body.toString()
    }

    fun close() {
        synchronized(lock) { closed = true }
        cancelTimer?.invoke()
        flush(null)
    }
}
