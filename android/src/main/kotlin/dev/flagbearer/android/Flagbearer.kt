package dev.flagbearer.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "Flagbearer"

/**
 * Feature flags for an Android app. Create one for the whole app, with your
 * environment's client key (it starts with `fbc_` and is safe to ship):
 *
 * ```kotlin
 * val flags = Flagbearer(applicationContext, clientKey = "fbc_...")
 *
 * if (flags.isEnabled("new-checkout")) { ... }
 * ```
 *
 * Flags load in the background and are kept on the device, so the next launch
 * starts with the flags the user last had, even offline. While the app is in
 * the foreground, a change made in the dashboard arrives within a couple of
 * seconds over a live connection; without one, flags are rechecked about
 * every 30 seconds.
 *
 * @param url the Flagbearer SDK address. Only self-hosted setups change it.
 * @param user who flags are evaluated for. Call [identify] when it changes.
 * @param debug logs each fetch, and warns about flags that don't exist even
 *   in release builds.
 * @param appVersion shown beside flag checks in the dashboard. Defaults to
 *   the app's versionName.
 * @param cacheFlags start with the flags this user last had, until fresh
 *   ones arrive.
 */
class Flagbearer @JvmOverloads constructor(
    context: Context,
    clientKey: String,
    url: String = DEFAULT_URL,
    user: User = User(),
    debug: Boolean = false,
    appVersion: String? = null,
    cacheFlags: Boolean = true,
) : AutoCloseable {
    private val app = context.applicationContext
    private val lifecycle = ForegroundWatcher(
        onForeground = { client.resume() },
        onBackground = { client.background() },
    )
    private val client: Client = Client(
        Options(
            clientKey, url, user, debug,
            appVersion ?: runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName
            }.getOrNull(),
            cacheFlags,
        ),
        AndroidPlatform(app),
    )

    init {
        (app as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
    }

    /** Whether the current user's flags have arrived. */
    val status: FlagsStatus get() = client.status

    /**
     * Whether a flag is on for the current user. Never blocks and never
     * throws: until flags arrive, or for a flag that doesn't exist, it
     * returns [defaultValue].
     */
    @JvmOverloads
    fun isEnabled(flag: String, defaultValue: Boolean = false): Boolean =
        client.isEnabled(flag, defaultValue)

    /** Every flag's current value, for error reports. Doesn't count as checking them. */
    fun allFlags(): Map<String, Boolean> = client.allFlags()

    /**
     * Switches to another user, such as after sign-in or sign-out, and waits
     * for their flags. Until they arrive, isEnabled returns that user's
     * cached flags, or defaults. Throws [FlagbearerException] if they can't
     * be fetched.
     */
    suspend fun identify(user: User): Unit = await { client.identify(user, it) }

    /** Fetches the current user's flags now and waits for them. */
    suspend fun sync(): Unit = await { client.sync(it) }

    /** Uploads pending flag-check reports now. They're also sent every 30 seconds. */
    suspend fun flush(): Unit = await { client.flush(it) }

    /**
     * Calls [listener] on the main thread whenever flags or [status] change.
     * Close the result to stop.
     */
    fun subscribe(listener: () -> Unit): AutoCloseable {
        val unsubscribe = client.subscribe(listener)
        return AutoCloseable { unsubscribe() }
    }

    /** Stops updates and uploads what's pending. The last flags stay readable. */
    override fun close() {
        (app as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
        client.close()
    }

    private suspend fun await(start: ((Throwable?) -> Unit) -> Unit): Unit =
        suspendCoroutine { continuation ->
            start { failure ->
                if (failure == null) continuation.resume(Unit)
                else continuation.resumeWithException(failure)
            }
        }
}

// The app is in the foreground while any activity is started.
private class ForegroundWatcher(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) onForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        // A rotation restarts the activity, so it isn't a trip to the background.
        if (--started <= 0 && !activity.isChangingConfigurations) {
            started = 0
            onBackground()
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

private class AndroidPlatform(private val app: Context) : Platform {
    private val state = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "flagbearer").apply { isDaemon = true }
    }
    private val network = Executors.newSingleThreadExecutor { task ->
        Thread(task, "flagbearer-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val preferences = app.getSharedPreferences("dev.flagbearer", Context.MODE_PRIVATE)

    override val sdkVersion = BuildConfig.SDK_VERSION
    override val development =
        app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    override val storage = object : Storage {
        override fun get(key: String): String? = preferences.getString(key, null)
        override fun set(key: String, value: String) =
            preferences.edit().putString(key, value).apply()
    }

    override fun now() = System.currentTimeMillis()
    override fun random() = Math.random()
    override fun randomId() = UUID.randomUUID().toString()

    override fun run(task: () -> Unit) {
        state.execute(guarded(task))
    }

    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val scheduled = state.schedule(guarded(task), delayMs, TimeUnit.MILLISECONDS)
        return { scheduled.cancel(false) }
    }

    override fun io(task: () -> Unit) {
        network.execute(guarded(task))
    }

    override fun notify(task: () -> Unit) {
        main.post(task)
    }

    // The SDK must never crash the app it's in.
    private fun guarded(task: () -> Unit) = Runnable {
        try {
            task()
        } catch (error: Throwable) {
            Log.e(TAG, "Flagbearer hit an unexpected error", error)
        }
    }

    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int,
    ): HttpResponse = httpPost(url, headers, body, timeoutMs)

    override fun openStream(url: String, events: StreamEvents): () -> Unit {
        val stream = EventStream(url, events)
        Thread(stream, "flagbearer-stream").apply { isDaemon = true }.start()
        return stream::close
    }

    override fun debug(message: String) {
        Log.d(TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }

    override fun error(message: String) {
        Log.e(TAG, message)
    }
}
