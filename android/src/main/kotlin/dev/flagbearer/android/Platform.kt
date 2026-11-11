package dev.flagbearer.android

/** Who flags are evaluated for. Both fields are optional; a signed-out user has neither. */
data class User(val key: String? = null, val email: String? = null)

/**
 * LOADING: no flags yet for the current user, so isEnabled returns defaults.
 * READY: the current user's flags have arrived, from the server or the cache.
 * They stay ready through later failed refreshes, keeping the last flags.
 * FAILED: fetching the current user's flags failed before any arrived.
 */
enum class FlagsStatus { LOADING, READY, FAILED }

/** A request Flagbearer couldn't complete, with the server's reason when it gave one. */
class FlagbearerException internal constructor(
    message: String,
    internal val retryable: Boolean = false,
    internal val retryAfterMs: Long = 0,
) : Exception(message)

internal class HttpResponse(
    val status: Int,
    val body: String,
    val retryAfter: String? = null,
)

/** What a live-update stream reports, from whichever thread reads it. */
internal interface StreamEvents {
    /** The server accepted the stream. */
    fun open()

    /** One line of the stream, without its line ending. */
    fun line(text: String)

    /** The stream ended. [status] is the server's refusal, or null if it had opened or never connected. */
    fun closed(status: Int?, retryAfter: String?)
}

/** Where the anonymous ID and the last flags are kept between launches. */
internal interface Storage {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

/**
 * Everything the client needs from the device, so tests can stand in for it:
 * threads, timers, the network, storage and the log.
 */
internal interface Platform {
    val sdkVersion: String

    /** A debuggable build, where warnings about the app's own code are worth printing. */
    val development: Boolean
    val storage: Storage?

    fun now(): Long

    /** A number in [0, 1), for jitter. */
    fun random(): Double

    fun randomId(): String

    /** Runs on the one thread that owns the client's state. */
    fun run(task: () -> Unit)

    /** Runs [task] on the state thread after [delayMs]. Returns a cancel function. */
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit

    /** Runs blocking work (network, storage) off the state thread. */
    fun io(task: () -> Unit)

    /** Calls a listener where the app expects it: the main thread on Android. */
    fun notify(task: () -> Unit)

    /** Blocks until the server answers. Throws IOException when it can't be reached. */
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResponse

    /**
     * Opens the server's stream of flag changes and reads it until it ends or
     * goes silent. Returns a function that closes it; after that, no more
     * events arrive.
     */
    fun openStream(url: String, events: StreamEvents): () -> Unit

    fun debug(message: String)
    fun warn(message: String)
    fun error(message: String)
}
