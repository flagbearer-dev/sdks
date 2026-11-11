package dev.flagbearer.android

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Stands in for the device: time moves only when a test advances it, work
// runs at once on the calling thread, and the test says how the server answers.
private class FakeDevice(override val development: Boolean = true) : Platform {
    class Request(val path: String, val headers: Map<String, String>, val body: JSONObject)

    private class Timer(val at: Long, val task: () -> Unit, var cancelled: Boolean = false)

    var time = 1_800_000_000_000L
    private val timers = ArrayList<Timer>()
    private var nextId = 0
    val requests = ArrayList<Request>()
    val warnings = ArrayList<String>()
    val errors = ArrayList<String>()
    val saved = HashMap<String, String>()

    // How /v1/evaluate answers. Uploads to /v1/exposures are always accepted.
    var evaluate: (JSONObject) -> HttpResponse = { flags(1) }

    override val sdkVersion = "0.0.0-test"
    override val storage: Storage? = object : Storage {
        override fun get(key: String) = saved[key]
        override fun set(key: String, value: String) {
            saved[key] = value
        }
    }

    override fun now() = time
    override fun random() = 0.5
    override fun randomId() = "00000000-0000-4000-8000-%012d".format(nextId++)
    override fun run(task: () -> Unit) = task()
    override fun io(task: () -> Unit) = task()
    override fun notify(task: () -> Unit) = task()

    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val timer = Timer(time + delayMs, task)
        timers.add(timer)
        return { timer.cancelled = true }
    }

    fun advance(ms: Long) {
        val until = time + ms
        while (true) {
            val next = timers.filter { !it.cancelled && it.at <= until }.minByOrNull { it.at } ?: break
            timers.remove(next)
            time = next.at
            next.task()
        }
        time = until
    }

    override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResponse {
        val request = Request(url.removePrefix("http://sdk.test"), headers, JSONObject(body))
        requests.add(request)
        if (request.path == "/v1/exposures") {
            val events = request.body.getJSONArray("events").length()
            return HttpResponse(200, """{"accepted":$events,"rejectedChecks":0}""")
        }
        return evaluate(request.body)
    }

    override fun debug(message: String) {}
    override fun warn(message: String) {
        warnings.add(message)
    }

    override fun error(message: String) {
        errors.add(message)
    }

    fun fetches() = requests.filter { it.path == "/v1/evaluate" }
    fun uploads() = requests.filter { it.path == "/v1/exposures" }
}

// A server answer with every flag given, as the real server sends them.
private fun flags(version: Int, vararg values: Pair<String, Boolean>): HttpResponse {
    val flags = JSONObject()
    val details = JSONObject()
    for ((key, value) in values) {
        flags.put(key, value)
        details.put(
            key,
            JSONObject().put("value", value).put("reason", if (value) "rollout" else "off")
                .put("configurationVersion", version),
        )
    }
    return HttpResponse(200, JSONObject().put("version", version).put("flags", flags).put("details", details).toString())
}

private fun refused(status: Int, message: String) =
    HttpResponse(status, JSONObject().put("code", "INVALID_CREDENTIALS").put("message", message).toString())

private val andrew = User(key = "1", email = "andrew@example.com")
private val bea = User(key = "2", email = "bea@example.com")

private fun start(device: FakeDevice, user: User = andrew, clientKey: String = "fbc_test", debug: Boolean = false) =
    Client(Options(clientKey, "http://sdk.test", user, debug, "1.2.3", cacheFlags = true), device)

class ClientTest {
    @Test
    fun `flags load for the user, and a missing flag returns the default given`() {
        val device = FakeDevice()
        device.evaluate = { flags(1, "new-checkout" to true, "dark-mode" to false) }

        val client = start(device)

        assertEquals(FlagsStatus.READY, client.status)
        assertTrue(client.isEnabled("new-checkout", false))
        assertFalse(client.isEnabled("dark-mode", true))
        assertTrue(client.isEnabled("not-a-flag", true))
        val sent = device.fetches().single().body
        assertEquals("fbc_test", sent.getString("clientKey"))
        assertEquals("1", sent.getString("userKey"))
        assertEquals("andrew@example.com", sent.getString("userEmail"))
        assertEquals(device.saved["flagbearer:anon"], sent.getString("anonymousId"))
    }

    @Test
    fun `an offline launch starts with the flags this user last had, and nobody else's`() {
        val device = FakeDevice()
        device.evaluate = { flags(1, "new-checkout" to true) }
        start(device).close()
        device.evaluate = { throw IOException("No network") }

        val sameUser = start(device)
        val otherUser = start(device, bea)

        assertEquals(FlagsStatus.READY, sameUser.status)
        assertTrue(sameUser.isEnabled("new-checkout", false))
        assertEquals(FlagsStatus.FAILED, otherUser.status)
        assertFalse(otherUser.isEnabled("new-checkout", false))
        // Being offline isn't reported as an error.
        assertEquals(emptyList<String>(), device.errors)
    }

    @Test
    fun `cached flags older than seven days are ignored`() {
        val device = FakeDevice()
        device.evaluate = { flags(1, "new-checkout" to true) }
        start(device).close()
        device.evaluate = { throw IOException("No network") }
        device.time += 8 * 86_400_000L

        val client = start(device)

        assertEquals(FlagsStatus.FAILED, client.status)
        assertFalse(client.isEnabled("new-checkout", false))
    }

    @Test
    fun `a server key is never sent, and the log says to use a client key`() {
        val device = FakeDevice()

        val client = start(device, clientKey = "fbs_secret")

        assertEquals(emptyList<FakeDevice.Request>(), device.fetches())
        assertEquals(FlagsStatus.FAILED, client.status)
        assertFalse(client.isEnabled("new-checkout", false))
        assertEquals(1, device.errors.size)
        assertTrue(device.errors.single().contains("This is a server key (fbs_)"))
    }

    @Test
    fun `a refused key is explained once with the server's reason, and nothing retries`() {
        val device = FakeDevice()
        device.evaluate = { refused(401, "This SDK key isn't recognized.") }

        val client = start(device)
        device.advance(10 * 60_000L)
        var failure: Throwable? = null
        client.sync { failure = it }

        assertEquals(FlagsStatus.FAILED, client.status)
        assertEquals(
            "Flags have stopped updating and keep their current or default values until this is fixed. " +
                "Flagbearer request failed: 401. This SDK key isn't recognized.",
            device.errors.first(),
        )
        // One request at launch, none from polling, one for the sync() asked for.
        assertEquals(2, device.fetches().size)
        assertEquals("Flagbearer request failed: 401. This SDK key isn't recognized.", failure?.message)
    }

    @Test
    fun `checking a flag that doesn't exist warns once in a debuggable build, and an existing flag never does`() {
        val device = FakeDevice()
        device.evaluate = { flags(1, "new-checkout" to true) }
        val client = start(device)

        client.isEnabled("new-checkout", false)
        client.isEnabled("new-checkotu", false)
        client.isEnabled("new-checkotu", false)

        assertEquals(1, device.warnings.size)
        assertTrue(device.warnings.single().startsWith("No flag \"new-checkotu\" exists"))
    }

    @Test
    fun `in a release build a flag that doesn't exist warns nobody, unless debug is on`() {
        val release = FakeDevice(development = false)
        start(release).isEnabled("new-checkotu", false)
        val debugging = FakeDevice(development = false)
        start(debugging, debug = true).isEnabled("new-checkotu", false)

        assertEquals(emptyList<String>(), release.warnings)
        assertEquals(1, debugging.warnings.size)
    }

    @Test
    fun `flags are rechecked every 30 seconds, and a listener hears only real changes`() {
        val device = FakeDevice()
        device.evaluate = { flags(1, "new-checkout" to false) }
        val client = start(device)
        var changes = 0
        client.subscribe { changes++ }

        // Nothing changed: the server answers 204 to the version the client has.
        device.evaluate = { body ->
            assertEquals(1, body.getInt("knownVersion"))
            HttpResponse(204, "")
        }
        device.advance(31_000)
        val afterUnchanged = changes
        device.evaluate = { flags(2, "new-checkout" to true) }
        device.advance(31_000)

        assertEquals(3, device.fetches().size)
        assertEquals(0, afterUnchanged)
        assertEquals(1, changes)
        assertTrue(client.isEnabled("new-checkout", false))
    }

    @Test
    fun `a failed fetch retries with backoff and keeps the flags it had`() {
        val device = FakeDevice()
        device.evaluate = { flags(1, "new-checkout" to true) }
        val client = start(device)
        device.evaluate = { HttpResponse(503, "") }

        // The poll at 30 seconds fails; the first retry comes a second later.
        device.advance(30_500)
        val afterFailure = device.fetches().size
        device.advance(1_000)

        assertEquals(2, afterFailure)
        assertEquals(3, device.fetches().size)
        assertEquals(FlagsStatus.READY, client.status)
        assertTrue(client.isEnabled("new-checkout", false))
        assertEquals(emptyList<String>(), device.errors)
    }

    @Test
    fun `a server's Retry-After is waited out`() {
        val device = FakeDevice()
        val client = start(device)
        device.evaluate = { HttpResponse(429, "", retryAfter = "120") }
        device.advance(31_000)
        val before = device.fetches().size

        device.advance(110_000)
        client.resume()
        val during = device.fetches().size
        device.advance(15_000)

        assertEquals(before, during)
        assertEquals(before + 1, device.fetches().size)
    }

    @Test
    fun `five minutes in the background stops polling, and coming back rechecks at once`() {
        val device = FakeDevice()
        val client = start(device)

        client.background()
        device.advance(5 * 60_000L)
        val whenPaused = device.fetches().size
        device.advance(10 * 60_000L)
        val stillPaused = device.fetches().size
        client.resume()

        assertEquals(whenPaused, stillPaused)
        assertEquals(whenPaused + 1, device.fetches().size)
    }

    @Test
    fun `identify switches to the new user's flags and drops the previous user's`() {
        val device = FakeDevice()
        device.evaluate = { body ->
            if (body.optString("userKey") == "1") flags(4, "new-checkout" to true) else flags(4, "dark-mode" to true)
        }
        val client = start(device)
        var failure: Throwable? = IllegalStateException("not called")

        client.identify(bea) { failure = it }

        assertNull(failure)
        assertFalse(client.isEnabled("new-checkout", false))
        assertTrue(client.isEnabled("dark-mode", false))
        // The new user's first fetch asks for everything, not for changes since version 4.
        assertFalse(device.fetches().last().body.has("knownVersion"))
        assertEquals(mapOf("dark-mode" to true), client.allFlags())
    }

    @Test
    fun `flag checks upload once per window with the user, the counts and the SDK's name`() {
        val device = FakeDevice()
        device.evaluate = { flags(7, "new-checkout" to true) }
        val client = start(device)

        repeat(3) { client.isEnabled("new-checkout", false) }
        client.isEnabled("not-a-flag", false)
        client.allFlags()
        var failure: Throwable? = IllegalStateException("not called")
        client.flush { failure = it }

        assertNull(failure)
        val upload = device.uploads().single()
        assertEquals("Bearer fbc_test", upload.headers["Authorization"])
        assertEquals("android", upload.body.getString("sdk"))
        assertEquals("1.2.3", upload.body.getString("appVersion"))
        assertEquals("1", upload.body.getJSONObject("user").getString("key"))
        val events = upload.body.getJSONArray("events")
        val byFlag = (0 until events.length()).map(events::getJSONObject).associateBy { it.getString("flagKey") }
        assertEquals(setOf("new-checkout", "not-a-flag"), byFlag.keys)
        assertEquals(3, byFlag.getValue("new-checkout").getInt("checkCount"))
        assertEquals("rollout", byFlag.getValue("new-checkout").getString("reason"))
        assertEquals("fallback", byFlag.getValue("not-a-flag").getString("reason"))
    }
}
