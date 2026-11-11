package dev.flagbearer.android

import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import org.json.JSONArray
import org.json.JSONObject

internal const val DEFAULT_URL = "https://sdk.flagbearer.dev"

private const val REQUEST_TIMEOUT_MS = 5000
private const val POLL_INTERVAL_MS = 30_000L
private const val MAX_RETRY_MS = 30_000L

// resume() refetches only when the last successful check is older than this.
private const val RESUME_STALE_MS = 15_000L

// Spreads refetches out so a flag change doesn't hit the API all at once.
private const val MAX_VERSION_REFRESH_DELAY_MS = 2000L

// In the background this long, the client drops its stream and stops polling
// until the app is back.
private const val IDLE_HIDDEN_MS = 5 * 60_000L

// Cached flags older than this are ignored, so a flag turned off doesn't stay
// on for long on a device that's been offline.
private const val MAX_CACHE_AGE_MS = 7 * 86_400_000L
private const val ANONYMOUS_ID_KEY = "flagbearer:anon"
private const val CACHE_KEY = "flagbearer:flags"
private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
private val REASONS = setOf("off", "tester", "rollout", "fallback")

internal class Options(
    val clientKey: String,
    val url: String,
    val user: User,
    val debug: Boolean,
    val appVersion: String?,
    val cacheFlags: Boolean,
)

private class Snapshot(
    val version: Int,
    val flags: Map<String, Boolean>,
    val details: Map<String, Evaluation>?,
    val json: String,
)

// What isEnabled reads. Replaced whole, never changed, so any thread can read it.
private class View(
    val user: User,
    val status: FlagsStatus = FlagsStatus.LOADING,
    val flags: Map<String, Boolean> = emptyMap(),
    val details: Map<String, Evaluation> = emptyMap(),
    // Set only by flags from the server; cached flags leave it null, so the
    // next fetch asks for everything.
    val version: Int? = null,
    // True while `details` lists every flag in the environment, so a flag
    // missing from it doesn't exist.
    val knowsEveryFlag: Boolean = false,
)

// What's wrong with a client key, when that can be told without a request.
private fun clientKeyProblem(key: String): String? = when {
    key.isEmpty() ->
        "No client key was given. Pass your environment's client key (fbc_...) as clientKey."
    key.any { it.isWhitespace() } ->
        "The client key contains a space or line break. Copy it again from the Flagbearer dashboard."
    key.startsWith("fbs_") ->
        "This is a server key (fbs_), which is secret and must not ship in an app. Use the environment's client key (fbc_) instead."
    else -> null
}

private fun parseSnapshot(body: String): Snapshot? {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val version = (json.opt("version") as? Number)?.toInt()?.takeIf { it >= 0 } ?: return null
    val rawFlags = json.optJSONObject("flags") ?: return null
    val flags = HashMap<String, Boolean>()
    for (key in rawFlags.keys()) {
        flags[key] = rawFlags.opt(key) as? Boolean ?: return null
    }
    val rawDetails = json.optJSONObject("details")
    val details = rawDetails?.let {
        val parsed = HashMap<String, Evaluation>()
        for (key in it.keys()) {
            val detail = it.optJSONObject(key) ?: return null
            val value = detail.opt("value") as? Boolean ?: return null
            val reason = (detail.opt("reason") as? String)?.takeIf(REASONS::contains) ?: return null
            val configuration = detail.opt("configurationVersion")
            if (configuration != null && configuration != JSONObject.NULL && configuration !is Number) return null
            parsed[key] = Evaluation(value, reason, (configuration as? Number)?.toInt())
        }
        parsed
    }
    return Snapshot(version, flags, details, body)
}

/**
 * The client's logic, with no Android in it: fetches a user's flags, keeps
 * them fresh from the server's stream of changes (polling when there's no
 * stream), caches them for the next launch, and reports checks.
 * Every change to its state happens on the platform's one state thread.
 */
internal class Client(private val options: Options, private val platform: Platform) {
    private val origin = Regex("^https?://[^/?#]+", RegexOption.IGNORE_CASE).find(options.url)?.value
        ?: throw IllegalArgumentException("Flagbearer url must use HTTP or HTTPS")

    // A bad key doesn't throw: an app keeps running on default values, and the
    // log says why. No request is made with it.
    private val keyProblem = clientKeyProblem(options.clientKey)
    private val reporter =
        ExposureReporter(platform, "$origin/v1/exposures", options.clientKey, options.appVersion)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val warnedFlags = HashSet<String>()

    @Volatile private var view = View(options.user)

    // Everything below is touched only on the state thread.
    private var closed = false

    // Goes up when the user changes, so answers for the previous user are dropped.
    private var generation = 0

    // Waiters on the request in flight, or null when there's none.
    private var inFlight: MutableList<(Throwable?) -> Unit>? = null

    // Set by a failure retrying can't fix. Only sync() or identify() tries again.
    private var blocked = false
    private var paused = false
    private var retryAttempt = 0

    // Set only from a server's Retry-After, which coming back must not cut short.
    private var serverNotBefore = 0L
    private var lastCheckedAt = Long.MIN_VALUE / 2
    private var cancelPoll: (() -> Unit)? = null
    private var cancelRetry: (() -> Unit)? = null
    private var cancelHidden: (() -> Unit)? = null

    // The live-update stream. While it's open, flags are fetched when it
    // announces a change, and polling stops.
    private var closeStream: (() -> Unit)? = null
    private var streamId = 0
    private var streaming = false
    private var hasStreamed = false

    // Set when the server refuses the stream outright. Polling carries on.
    private var streamRefused = false
    private var streamAttempt = 0
    private var cancelStreamRetry: (() -> Unit)? = null
    private var cancelVersionRefresh: (() -> Unit)? = null

    // The newest version the stream has announced.
    private var wantedVersion: Int? = null
    private var eventName = "message"
    private var eventData = ""
    private var anonymousId: String? = null
    private var anonymousIdLoaded = false

    init {
        platform.run { start(null) }
    }

    val status: FlagsStatus get() = view.status

    fun isEnabled(flag: String, defaultValue: Boolean): Boolean {
        val current = view
        val known = current.flags[flag]
        val value = known ?: defaultValue
        val evaluation = current.details[flag]
        if (evaluation == null && known != null) return value
        if (evaluation == null && current.knowsEveryFlag) warnUnknown(flag)
        reporter.record(
            flag,
            current.user,
            evaluation?.let { Evaluation(value, it.reason, it.configurationVersion) }
                ?: Evaluation(value, "fallback", current.version),
        )
        return value
    }

    // Once per flag, in debuggable builds or with debug on: a misspelled or
    // deleted flag otherwise looks like one that's off.
    private fun warnUnknown(flag: String) {
        if (!options.debug && !platform.development) return
        synchronized(warnedFlags) {
            if (warnedFlags.size >= 100 || !warnedFlags.add(flag)) return
        }
        platform.warn(
            "No flag \"$flag\" exists in this environment, so isEnabled returns its default. Check the spelling, or create the flag.",
        )
    }

    /** A plain copy for error reports. It reports no exposures. */
    fun allFlags(): Map<String, Boolean> = HashMap(view.flags)

    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun sync(done: (Throwable?) -> Unit) = platform.run { refresh(done, manual = true) }

    fun identify(user: User, done: (Throwable?) -> Unit) = platform.run {
        if (closed) {
            done(FlagbearerException("Flagbearer client is closed"))
        } else if (user == view.user) {
            // The same user again changes nothing, so their flags stay as they are.
            done(null)
        } else {
            generation++
            inFlight?.let { waiters ->
                inFlight = null
                waiters.forEach { it(FlagbearerException("The Flagbearer user changed before this finished")) }
            }
            cancelRetry?.invoke()
            cancelRetry = null
            retryAttempt = 0
            publish(View(user))
            start(done)
        }
    }

    fun flush(done: (Throwable?) -> Unit) = reporter.flush(done)

    /** The app is in the foreground again. */
    fun resume() = platform.run {
        cancelHidden?.invoke()
        cancelHidden = null
        paused = false
        if (closed || blocked) return@run
        connectStream()
        if (platform.now() - lastCheckedAt < RESUME_STALE_MS) {
            ensurePolling()
            return@run
        }
        // Coming back cancels the SDK's own backoff, never the server's Retry-After.
        if (platform.now() >= serverNotBefore) {
            cancelRetry?.invoke()
            cancelRetry = null
            retryAttempt = 0
        }
        cancelPoll?.invoke()
        cancelPoll = null
        refresh(null, manual = false)
        ensurePolling()
    }

    /**
     * The app went to the background: upload what's pending. If it stays
     * there, drop the stream and stop polling until it's back.
     */
    fun background() = platform.run {
        reporter.flush(null)
        cancelHidden?.invoke()
        cancelHidden = platform.schedule(IDLE_HIDDEN_MS) {
            cancelHidden = null
            paused = true
            disconnectStream()
            cancelPoll?.invoke()
            cancelPoll = null
        }
    }

    fun close() = platform.run {
        if (closed) return@run
        closed = true
        listeners.clear()
        for (cancel in listOf(cancelPoll, cancelRetry, cancelHidden, cancelVersionRefresh)) cancel?.invoke()
        disconnectStream()
        inFlight?.forEach { it(FlagbearerException("Flagbearer client is closed")) }
        inFlight = null
        reporter.close()
    }

    // The current user's first load: their cached flags at once, then fresh ones.
    private fun start(done: ((Throwable?) -> Unit)?) {
        val startedFor = generation
        val user = view.user
        if (options.cacheFlags && platform.storage != null) {
            platform.io {
                val cached = readCache(user)
                platform.run {
                    // Fresh flags always win, even if the cache is slow to read.
                    if (cached != null && !closed && generation == startedFor && view.version == null) {
                        debug("Cached flags applied")
                        publish(View(user, FlagsStatus.READY, cached.flags, cached.details ?: emptyMap()))
                    }
                }
            }
        }
        blocked = false
        refresh(done, manual = true)
        connectStream()
        ensurePolling()
    }

    private fun refresh(done: ((Throwable?) -> Unit)?, manual: Boolean) {
        if (closed) {
            done?.invoke(FlagbearerException("Flagbearer client is closed"))
            return
        }
        inFlight?.let { waiters ->
            done?.let(waiters::add)
            return
        }
        if (!manual && (blocked || cancelRetry != null)) return
        if (manual) blocked = false
        cancelRetry?.invoke()
        cancelRetry = null

        val waiters = ArrayList<(Throwable?) -> Unit>()
        done?.let(waiters::add)
        inFlight = waiters
        val requestedFor = generation
        val current = view
        debug("Refresh started")
        platform.io {
            val result = runCatching { fetch(current.user, current.version) }
            platform.run { finish(requestedFor, waiters, result) }
        }
    }

    private fun finish(requestedFor: Int, waiters: List<(Throwable?) -> Unit>, result: Result<Snapshot?>) {
        // identify() or close() already answered these waiters.
        if (closed || requestedFor != generation) return
        inFlight = null
        val failure = result.exceptionOrNull()
        if (failure == null) {
            lastCheckedAt = platform.now()
            val snapshot = result.getOrNull()
            val known = view.version
            if (snapshot != null && (known == null || snapshot.version >= known)) {
                publish(
                    View(
                        view.user, FlagsStatus.READY, snapshot.flags,
                        snapshot.details ?: emptyMap(), snapshot.version, snapshot.details != null,
                    ),
                )
                writeCache(view.user, snapshot)
                debug("Snapshot applied")
            }
            if (isBehindWantedVersion()) {
                // The server answered with less than the stream announced:
                // ask again later rather than spin.
                scheduleRetry(null)
            } else {
                retryAttempt = 0
            }
            connectStream()
        } else {
            if (view.status == FlagsStatus.LOADING) publish(view.with(FlagsStatus.FAILED))
            if (failure is FlagbearerException && !failure.retryable) {
                // Said once, without debug on: retrying can't fix this, and
                // silent defaults look like flags that are simply off.
                blocked = true
                cancelPoll?.invoke()
                cancelPoll = null
                disconnectStream()
                platform.error(
                    "Flags have stopped updating and keep their current or default values until this is fixed. ${failure.message}",
                )
            } else {
                // Being offline is ordinary on a phone, so this isn't an error.
                debug("Refresh failed; retrying: ${failure.message}")
                scheduleRetry(failure)
            }
        }
        waiters.forEach { it(failure) }
        ensurePolling()
    }

    private fun View.with(status: FlagsStatus) = View(user, status, flags, details, version, knowsEveryFlag)

    private fun publish(next: View) {
        val previous = view
        view = next
        if (previous.flags == next.flags && previous.status == next.status) return
        for (listener in listeners) {
            platform.notify {
                // A listener removed since, or a closed client, hears nothing.
                if (listener in listeners) {
                    runCatching(listener).onFailure { platform.error("A flags listener failed: $it") }
                }
            }
        }
    }

    private fun ensurePolling() {
        if (closed || blocked || paused || streaming || cancelPoll != null) return
        val interval = (POLL_INTERVAL_MS * (0.9 + platform.random() * 0.2)).toLong()
        cancelPoll = platform.schedule(interval) {
            cancelPoll = null
            refresh(null, manual = false)
            ensurePolling()
        }
    }

    private fun isBehindWantedVersion(): Boolean {
        val wanted = wantedVersion ?: return false
        val known = view.version
        return known == null || known < wanted
    }

    private fun connectStream() {
        if (closed || blocked || paused || streamRefused || keyProblem != null) return
        if (closeStream != null || cancelStreamRetry != null) return
        val id = ++streamId
        eventName = "message"
        eventData = ""
        debug("Stream connecting")
        val url = "$origin/v1/stream?clientKey=${java.net.URLEncoder.encode(options.clientKey, "UTF-8")}"
        closeStream = platform.openStream(
            url,
            object : StreamEvents {
                // Each stream's events are dropped once another has replaced it.
                override fun open() = platform.run { if (id == streamId) onStreamOpen() }
                override fun line(text: String) = platform.run { if (id == streamId) onStreamLine(text) }
                override fun closed(status: Int?, retryAfter: String?) =
                    platform.run { if (id == streamId) onStreamClosed(status, retryAfter) }
            },
        )
    }

    private fun disconnectStream() {
        streamId++
        closeStream?.invoke()
        closeStream = null
        cancelStreamRetry?.invoke()
        cancelStreamRetry = null
        if (streaming) debug("Stream closed")
        streaming = false
    }

    private fun onStreamOpen() {
        debug("Stream connected")
        streaming = true
        streamAttempt = 0
        cancelPoll?.invoke()
        cancelPoll = null
        // Changes made while there was no stream weren't announced, so catch up.
        if (hasStreamed) refresh(null, manual = false)
        hasStreamed = true
    }

    // Server-Sent Events: "event:" and "data:" lines, then a blank line.
    private fun onStreamLine(text: String) {
        if (text.isEmpty()) {
            if (eventName == "version") {
                val version = runCatching { JSONObject(eventData).opt("version") as? Number }.getOrNull()
                version?.toInt()?.takeIf { it >= 0 }?.let(::onVersion)
            }
            eventName = "message"
            eventData = ""
            return
        }
        if (text.startsWith(":")) return
        val field = text.substringBefore(":")
        val content = text.substringAfter(":", "").removePrefix(" ")
        if (field == "event") eventName = content
        if (field == "data" && eventData.length < 10_000) eventData += content
    }

    private fun onVersion(version: Int) {
        wantedVersion = maxOf(wantedVersion ?: 0, version)
        if (!isBehindWantedVersion() || cancelVersionRefresh != null) return
        val delay = (platform.random() * MAX_VERSION_REFRESH_DELAY_MS).toLong()
        cancelVersionRefresh = platform.schedule(delay) {
            cancelVersionRefresh = null
            if (isBehindWantedVersion()) refresh(null, manual = false)
        }
    }

    private fun onStreamClosed(status: Int?, retryAfter: String?) {
        closeStream = null
        streaming = false
        debug("Stream disconnected; using polling")
        ensurePolling()
        // A refusal that reconnecting can't fix. Polling still works, and the
        // next fetch reports a bad key.
        if (status != null && status in 400..499 && status != 408 && status != 429) {
            streamRefused = true
            return
        }
        if (closed || blocked || paused) return
        // The server ends every stream after about half an hour; reconnecting
        // after 5 to 15 seconds is what it asks of browsers too.
        val base = minOf(60_000L, 5000L shl minOf(streamAttempt++, 4))
        val wait = (retryAfter?.toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0) * 1000
        val delay = maxOf((base * (1 + platform.random() * 2)).toLong(), wait.toLong())
        cancelStreamRetry = platform.schedule(delay) {
            cancelStreamRetry = null
            connectStream()
        }
    }

    private fun scheduleRetry(failure: Throwable?) {
        val backoff = minOf(MAX_RETRY_MS, 1000L shl minOf(retryAttempt++, 5))
        val jittered = minOf(MAX_RETRY_MS, (backoff * (0.8 + platform.random() * 0.4)).toLong())
        val retryAfter = (failure as? FlagbearerException)?.retryAfterMs ?: 0
        if (retryAfter > 0) serverNotBefore = maxOf(serverNotBefore, platform.now() + retryAfter)
        cancelRetry?.invoke()
        cancelRetry = platform.schedule(maxOf(jittered, retryAfter)) {
            cancelRetry = null
            refresh(null, manual = false)
        }
    }

    // Runs off the state thread. Null means the server confirmed nothing changed.
    private fun fetch(user: User, knownVersion: Int?): Snapshot? {
        if (keyProblem != null) throw FlagbearerException(keyProblem)
        val body = JSONObject().put("clientKey", options.clientKey).put("includeDetails", true)
        knownVersion?.let { body.put("knownVersion", it) }
        user.key?.let { body.put("userKey", it) }
        user.email?.let { body.put("userEmail", it) }
        loadAnonymousId()?.let { body.put("anonymousId", it) }

        val response = try {
            platform.post("$origin/v1/evaluate", emptyMap(), body.toString(), REQUEST_TIMEOUT_MS)
        } catch (error: IOException) {
            throw FlagbearerException("Flagbearer couldn't be reached: ${error.message}", retryable = true)
        }
        if (response.status == 204) return null
        if (response.status !in 200..299) {
            // The server's own explanation of a refused request, when it sent one.
            val reason = runCatching { JSONObject(response.body).optString("message") }
                .getOrNull()?.take(500)?.takeIf { it.isNotEmpty() }
            val seconds = response.retryAfter?.toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0
            throw FlagbearerException(
                "Flagbearer request failed: ${response.status}${reason?.let { ". $it" } ?: ""}",
                retryable = response.status == 408 || response.status == 429 || response.status >= 500,
                retryAfterMs = (seconds * 1000).toLong(),
            )
        }
        return parseSnapshot(response.body)
            ?: throw FlagbearerException("Invalid flag evaluation response")
    }

    // A random ID kept on this device, so a signed-out person counts once
    // however often they open the app. It's never used for targeting. Without
    // storage there's no ID, and the server falls back to the device's address.
    @Synchronized
    private fun loadAnonymousId(): String? {
        if (anonymousIdLoaded) return anonymousId
        anonymousIdLoaded = true
        val storage = platform.storage ?: return null
        anonymousId = runCatching {
            // The server rejects a malformed ID, which would block flags, so replace it.
            storage.get(ANONYMOUS_ID_KEY)?.takeIf(UUID::matches)
                ?: platform.randomId().also { storage.set(ANONYMOUS_ID_KEY, it) }
        }.getOrNull()
        return anonymousId
    }

    private fun cacheUser(user: User) = JSONArray().put(user.key ?: JSONObject.NULL).put(user.email ?: JSONObject.NULL).toString()

    private fun readCache(user: User): Snapshot? = runCatching {
        val cached = JSONObject(platform.storage?.get(CACHE_KEY) ?: return null)
        if (cached.optString("clientKey") != options.clientKey ||
            cached.optString("user") != cacheUser(user) ||
            platform.now() - cached.optLong("savedAt", 0) > MAX_CACHE_AGE_MS
        ) {
            return null
        }
        parseSnapshot(cached.optString("snapshot"))
    }.getOrNull()

    private fun writeCache(user: User, snapshot: Snapshot) {
        val storage = platform.storage
        if (!options.cacheFlags || storage == null) return
        val saved = JSONObject()
            .put("clientKey", options.clientKey)
            .put("user", cacheUser(user))
            .put("savedAt", platform.now())
            .put("snapshot", snapshot.json)
            .toString()
        platform.io { runCatching { storage.set(CACHE_KEY, saved) } }
    }

    private fun debug(message: String) {
        if (options.debug) platform.debug(message)
    }
}
