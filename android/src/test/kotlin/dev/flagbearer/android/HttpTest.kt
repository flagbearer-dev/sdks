package dev.flagbearer.android

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The real network code against a real HTTP server on this machine.
class HttpTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @After
    fun stop() = server.stop(0)

    private fun on(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange -> exchange.use(handler) }
    }

    // What a stream reported, in order: "open", "line:<text>" or "closed:<status>".
    private fun listen(path: String): Pair<EventStream, LinkedBlockingQueue<String>> {
        val heard = LinkedBlockingQueue<String>()
        val stream = EventStream(
            "$base$path",
            object : StreamEvents {
                override fun open() {
                    heard.add("open")
                }

                override fun line(text: String) {
                    heard.add("line:$text")
                }

                override fun closed(status: Int?, retryAfter: String?) {
                    heard.add("closed:$status:$retryAfter")
                }
            },
        )
        Thread(stream).start()
        return stream to heard
    }

    private fun LinkedBlockingQueue<String>.next() = poll(5, TimeUnit.SECONDS)

    @Test
    fun `a post sends the body and headers, and returns the status, body and Retry-After`() {
        var received = ""
        var authorization: String? = null
        on("/v1/exposures") { exchange ->
            received = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.responseHeaders.add("Retry-After", "30")
            val answer = """{"message":"Slow down"}""".toByteArray()
            exchange.sendResponseHeaders(429, answer.size.toLong())
            exchange.responseBody.write(answer)
        }

        val response = httpPost("$base/v1/exposures", mapOf("Authorization" to "Bearer fbc_test"), """{"a":1}""", 5000)

        assertEquals("""{"a":1}""", received)
        assertEquals("Bearer fbc_test", authorization)
        assertEquals(429, response.status)
        assertEquals("""{"message":"Slow down"}""", response.body)
        assertEquals("30", response.retryAfter)
    }

    @Test
    fun `a stream reports opening, then each line as the server sends it, then the server ending it`() {
        val release = CountDownLatch(1)
        on("/v1/stream") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            out.write("retry: 5000\n\nevent: version\ndata: {\"version\":3}\n\n".toByteArray())
            out.flush()
            // Held open, as the real server holds a stream, until the test lets go.
            release.await(5, TimeUnit.SECONDS)
        }

        val (_, heard) = listen("/v1/stream")
        val beforeEnd = (1..6).map { heard.next() }
        release.countDown()

        assertEquals(
            listOf("open", "line:retry: 5000", "line:", "line:event: version", "line:data: {\"version\":3}", "line:"),
            beforeEnd,
        )
        assertEquals("closed:null:null", heard.next())
    }

    @Test
    fun `a stream the server refuses reports the status and Retry-After, and never opens`() {
        on("/v1/stream") { exchange ->
            exchange.responseHeaders.add("Retry-After", "60")
            exchange.sendResponseHeaders(429, -1)
        }

        val (_, heard) = listen("/v1/stream")

        assertEquals("closed:429:60", heard.next())
        assertNull(heard.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `closing a stream stops it at once and reports nothing more`() {
        val release = CountDownLatch(1)
        on("/v1/stream") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("event: ping\n".toByteArray())
            exchange.responseBody.flush()
            release.await(5, TimeUnit.SECONDS)
        }

        val (stream, heard) = listen("/v1/stream")
        val first = listOf(heard.next(), heard.next())
        stream.close()
        val after = heard.poll(500, TimeUnit.MILLISECONDS)
        release.countDown()

        assertEquals(listOf("open", "line:event: ping"), first)
        assertNull(after)
    }

    @Test
    fun `a server that can't be reached ends the stream without a status`() {
        server.stop(0)

        val (_, heard) = listen("/v1/stream")

        assertTrue(heard.next()!!.startsWith("closed:null"))
    }
}
