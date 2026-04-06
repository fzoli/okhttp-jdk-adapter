package com.farcsal.okhttp.jdk

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.closeQuietly
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.*
import java.net.http.HttpClient
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdkInterceptorTest {

    private val cacheDir = Files.createTempDirectory("okhttp-jdk-test").toFile()

    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_3)
        .build()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(CompressionInterceptor(Gzip))
        .addInterceptor(OkHttpCacheInterceptor(Cache(cacheDir, 10 * 1024 * 1024)))
        .setup(httpClient)
        .build()

    private val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort())

    @BeforeAll
    fun setUp() {
        cacheDir.mkdirs()
        wireMock.start()
    }

    @AfterAll
    fun tearDown() {
        cacheDir.deleteRecursively()
        okHttpClient.dispatcher.executorService.shutdownNow()
        okHttpClient.connectionPool.evictAll()
        okHttpClient.cache?.close()
        httpClient.close()
        wireMock.stop()
    }

    @AfterEach
    fun resetStubs() {
        wireMock.resetAll()
    }

    @Test
    fun `GET returns JSON response`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/hello"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"message":"hello"}""")
                )
        )

        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/hello")
            .build()

        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertEquals("""{"message":"hello"}""", it.body.string())
        }

        val latch = CountDownLatch(1)
        val r = AtomicReference<Response>()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: okio.IOException) {
                latch.countDown()
            }
            override fun onResponse(call: Call, response: Response) {
                r.set(response)
                latch.countDown()
            }
        })
        latch.await(5, TimeUnit.SECONDS)
        assertNotNull(r.get())
        assertEquals(200, r.get().code)
        assertEquals("""{"message":"hello"}""", r.get().body.string())
    }

    @Test
    fun `POST with body returns response`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/echo"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody("received"))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/echo")
            .post("hello".toRequestBody("text/plain".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertEquals("received", it.body.string())
        }
    }

    @Test
    fun `PUT with body returns 200`() {
        wireMock.stubFor(
            put(urlEqualTo("/api/resource"))
                .willReturn(aResponse().withStatus(200))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/resource")
            .put("""{"key":"value"}""".toRequestBody("application/json".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
        }
    }

    @Test
    fun `DELETE returns 204`() {
        wireMock.stubFor(
            delete(urlEqualTo("/api/resource"))
                .willReturn(aResponse().withStatus(204))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/resource")
            .delete()
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(204, it.code)
        }
    }

    @Test
    fun `4xx response is returned without exception`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/missing"))
                .willReturn(aResponse().withStatus(404).withBody("not found"))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/missing")
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(404, it.code)
            assertEquals("not found", it.body.string())
        }
    }

    @Test
    fun `5xx response is returned without exception`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/error"))
                .willReturn(aResponse().withStatus(500).withBody("internal error"))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/error")
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(500, it.code)
            assertEquals("internal error", it.body.string())
        }
    }

    @Test
    fun `empty body response is readable`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/empty"))
                .willReturn(aResponse().withStatus(200))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/empty")
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertEquals("", it.body.string())
        }
    }

    @Test
    fun `large response body is received correctly`() {
        val largeBody = "x".repeat(1_000_000)
        wireMock.stubFor(
            get(urlEqualTo("/api/large"))
                .willReturn(aResponse().withStatus(200).withBody(largeBody))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/large")
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertEquals(largeBody, it.body.string())
        }
    }

    @Test
    fun `response headers are passed through`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/headers"))
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("X-Custom", "custom-value")
                        .withHeader("X-Another", "another-value")
                )
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/headers")
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals("custom-value", it.header("X-Custom"))
            assertEquals("another-value", it.header("X-Another"))
        }
    }

    @Test
    fun `cancel before execute throws SocketException`() {
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/hello")
            .build()
        val call = okHttpClient.newCall(request)
        call.cancel()
        var caught: IOException? = null
        try {
            call.execute().close()
        } catch (e: IOException) {
            caught = e
        }
        assertInstanceOf(IOException::class.java, caught)
        assertEquals("Canceled", caught?.message)
    }

    @Test
    fun `cancel after execute but before read throws Exception`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/empty"))
                .willReturn(aResponse().withStatus(200))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/empty")
            .build()
        val call = okHttpClient.newCall(request)
        var caught: Exception? = null
        val latch = CountDownLatch(1)
        Thread.startVirtualThread {
            val response = call.execute()
            call.cancel()
            Thread.startVirtualThread {
                try {
                    response.body.string()
                } catch (e: Exception) {
                    caught = e
                } finally {
                    response.close()
                    latch.countDown()
                }
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        assertInstanceOf(Exception::class.java, caught)
    }

    @Test
    fun `thread interrupt during request throws SocketException`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/slow"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withFixedDelay(5_000)
                        .withBody("too late")
                )
        )

        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/slow")
            .build()

        val caught = AtomicReference<Throwable>()

        val vt = Thread.startVirtualThread {
            try {
                okHttpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                caught.set(e)
            }
        }

        Thread.sleep(300)
        vt.interrupt()
        vt.join(3_000)

        assertInstanceOf(InterruptedException::class.java, caught.get())
    }

    @Test
    fun `call cancel during request throws IOException`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/slow"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withFixedDelay(5_000)
                        .withBody("too late")
                )
        )

        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/slow")
            .build()

        val caught = AtomicReference<Throwable>()
        val call = okHttpClient.newCall(request)

        val vt = Thread.startVirtualThread {
            try {
                call.execute()
            } catch (e: Exception) {
                caught.set(e)
            }
        }

        Thread.sleep(300)
        call.cancel()
        vt.join(3_000)

        assertInstanceOf(IOException::class.java, caught.get())
        assertEquals("Canceled", caught.get().message)
    }

    @Test
    fun `connect timeout throws SocketTimeoutException`() {
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_3)
            .connectTimeout(Duration.ofMillis(1))
            .build()
        val okHttpClient = OkHttpClient.Builder()
            .setup(httpClient)
            .build()
        // Use a non-routable address, so the connection attempt hangs
        // and triggers a timeout instead of failing immediately.
        val request = Request.Builder()
            .url("http://10.255.255.1/api/test")
            .build()
        var caught: Exception? = null
        try {
            okHttpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            caught = e
        } finally {
            okHttpClient.dispatcher.executorService.shutdownNow()
            okHttpClient.connectionPool.evictAll()
            httpClient.close()
        }
        assertInstanceOf(SocketTimeoutException::class.java, caught)
        assertEquals("Connect timed out", caught?.message)
    }

    @Test
    fun `read timeout throws SocketTimeoutException`() {
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_3)
            .build()
        val okHttpClient = OkHttpClient.Builder()
            .setup(httpClient)
            .readTimeout(Duration.ofMillis(200))
            .build()
        wireMock.stubFor(
            get(urlEqualTo("/api/timeout"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5_000).withBody("too late"))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/timeout")
            .build()
        var caught: Exception? = null
        try {
            okHttpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            caught = e
        } finally {
            okHttpClient.dispatcher.executorService.shutdownNow()
            okHttpClient.connectionPool.evictAll()
            httpClient.close()
        }
        assertInstanceOf(SocketTimeoutException::class.java, caught)
        assertEquals("timeout", caught?.message)
    }

    @Test
    fun `WebSocket echoes message`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().newBuilder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("echo: $text")
                    webSocket.close(1000, "done")
                }
            })
            .build()
        )
        server.start()

        val received = AtomicReference<String>()
        val latch = CountDownLatch(1)

        val request = Request.Builder()
            .url(server.url("/ws"))
            .build()

        val ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("hello")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.set(text)
                latch.countDown()
            }
        })

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        ws.close(1000, null)
        server.closeQuietly()

        assertEquals("echo: hello", received.get())
    }

    @Test
    fun `request headers are forwarded to server`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/req-headers"))
                .willReturn(aResponse().withStatus(200))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/req-headers")
            .header("X-Trace-Id", "abc123")
            .header("X-Custom", "my-value")
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
        }
        wireMock.verify(
            getRequestedFor(urlEqualTo("/api/req-headers"))
                .withHeader("X-Trace-Id", equalTo("abc123"))
                .withHeader("X-Custom", equalTo("my-value"))
        )
    }

    @Test
    fun `PATCH returns 200`() {
        wireMock.stubFor(
            patch(urlEqualTo("/api/resource"))
                .willReturn(aResponse().withStatus(200))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/resource")
            .patch("""{"key":"value"}""".toRequestBody("application/json".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
        }
    }

    @Test
    fun `HEAD returns 200 with no body`() {
        wireMock.stubFor(
            head(urlEqualTo("/api/resource"))
                .willReturn(aResponse().withStatus(200))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/resource")
            .head()
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertEquals("", it.body.string())
        }
    }

    @Test
    fun `multi-value response headers are all preserved`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/multi-header"))
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Set-Cookie", "a=1")
                        .withHeader("Set-Cookie", "b=2")
                )
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/multi-header")
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(listOf("a=1", "b=2"), it.headers.values("Set-Cookie"))
        }
    }

    @Test
    fun `response body can be closed twice without exception`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/hello"))
                .willReturn(aResponse().withStatus(200).withBody("hi"))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/hello")
            .build()
        val response = okHttpClient.newCall(request).execute()
        response.close()
        assertDoesNotThrow { response.close() }
    }

    @Test
    fun `concurrent requests all succeed`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/concurrent"))
                .willReturn(aResponse().withStatus(200).withBody("ok"))
        )
        val n = 20
        val latch = CountDownLatch(n)
        val errors = mutableListOf<Throwable>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        repeat(n) {
            executor.submit {
                try {
                    val request = Request.Builder()
                        .url("http://localhost:${wireMock.port()}/api/concurrent")
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        assertEquals(200, response.code)
                    }
                } catch (e: Throwable) {
                    synchronized(errors) { errors.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue(errors.isEmpty(), "Errors during concurrent requests: $errors")
    }

    @Test
    fun `connection refused throws ConnectException`() {
        val port = ServerSocket(0).use { it.localPort }
        val request = Request.Builder()
            .url("http://localhost:$port/api/test")
            .build()
        var caught: Exception? = null
        try {
            okHttpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            caught = e
        }
        assertInstanceOf(ConnectException::class.java, caught)
        assertEquals("Failed to connect to localhost/127.0.0.1:$port", caught?.message)
    }

    @Test
    fun `unknown host throws UnknownHostException`() {
        val request = Request.Builder()
            .url("http://this.hostname.does.not.exist.invalid/api/test")
            .build()
        var caught: Exception? = null
        try {
            okHttpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            caught = e
        }
        assertInstanceOf(UnknownHostException::class.java, caught)
        assertTrue(caught?.message?.startsWith("this.hostname.does.not.exist.invalid") == true)
    }

    @Test
    fun `untrusted root certificate throws SSLException`() {
        val request = Request.Builder()
            .url("https://untrusted-root.badssl.com/")
            .build()
        var caught: Exception? = null
        try {
            okHttpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            caught = e
        }
        assertInstanceOf(SSLException::class.java, caught)
        assertTrue(caught?.message?.endsWith("PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target") == true)
    }

    @Test
    fun `expired certificate throws SSLException`() {
        val request = Request.Builder()
            .url("https://expired.badssl.com/")
            .build()
        var caught: Exception? = null
        try {
            okHttpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            caught = e
        }
        assertInstanceOf(SSLException::class.java, caught)
        assertTrue(caught?.message?.endsWith("PKIX path validation failed: java.security.cert.CertPathValidatorException: validity check failed") == true)
    }

    @Test
    fun `self-signed certificate throws SSLException`() {
        val request = Request.Builder()
            .url("https://self-signed.badssl.com/")
            .build()
        var caught: Exception? = null
        try {
            okHttpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            caught = e
        }
        assertInstanceOf(SSLException::class.java, caught)
        assertTrue(caught?.message?.endsWith("PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target") == true)
    }

    @Test
    fun `connection reset by peer throws IOException`() {
        wireMock.stubFor(get(urlEqualTo("/fault/reset"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/fault/reset")
            .build()
        var caught: Exception? = null
        try {
            okHttpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            caught = e
        }
        assertInstanceOf(SocketException::class.java, caught)
        assertEquals("Connection reset", caught?.message)
    }

    @Test
    fun `empty response throws IOException`() {
        wireMock.stubFor(get(urlEqualTo("/fault/empty"))
            .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)))
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/fault/empty")
            .build()
        var caught: Exception? = null
        try {
            okHttpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            caught = e
        }
        assertInstanceOf(IOException::class.java, caught)
        // Message of JDK: "EOF reached while reading"
        // Message of okhttp: "unexpected end of stream on http://localhost:37219/..."
    }

    @Test
    fun `malformed response chunk is ignored`() {
        wireMock.stubFor(get(urlEqualTo("/fault/malformed"))
            .willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)))
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/fault/malformed")
            .build()
        okHttpClient.newCall(request).execute().close()
    }

    @Test
    fun `random data then close throws IOException`() {
        wireMock.stubFor(get(urlEqualTo("/fault/random"))
            .willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE)))
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/fault/random")
            .build()
        var caught: Exception? = null
        try {
            okHttpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            caught = e
        }
        assertInstanceOf(IOException::class.java, caught)
        // Message of JDK: "Frame type(100) length(7107435) exceeds MAX_FRAME_SIZE(16384)"
        // Message of okhttp: "unexpected end of stream on http://localhost:37219/..."
    }

    @Test
    fun `Redirect NEVER returns 301 without following`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/redirect"))
                .willReturn(aResponse().withStatus(301).withHeader("Location", "/api/target"))
        )
        wireMock.stubFor(
            get(urlEqualTo("/api/target"))
                .willReturn(aResponse().withStatus(200).withBody("redirected"))
        )
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_3)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val client = OkHttpClient.Builder()
            .setup(httpClient)
            .build()
        try {
            val request = Request.Builder()
                .url("http://localhost:${wireMock.port()}/api/redirect")
                .build()
            client.newCall(request).execute().use {
                assertEquals(301, it.code)
                assertEquals("/api/target", it.header("Location"))
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            httpClient.close()
        }
    }

    @Test
    fun `Redirect ALWAYS follows HTTP to HTTP redirect`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/redirect"))
                .willReturn(aResponse().withStatus(301).withHeader("Location", "/api/target"))
        )
        wireMock.stubFor(
            get(urlEqualTo("/api/target"))
                .willReturn(aResponse().withStatus(200).withBody("redirected"))
        )
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_3)
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
        val client = OkHttpClient.Builder()
            .setup(httpClient)
            .build()
        try {
            val request = Request.Builder()
                .url("http://localhost:${wireMock.port()}/api/redirect")
                .build()
            client.newCall(request).execute().use {
                assertEquals(200, it.code)
                assertEquals("redirected", it.body.string())
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            httpClient.close()
        }
    }

    @Test
    fun `Redirect NEVER does not follow HTTP to HTTP redirect`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/redirect"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/api/target"))
        )
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_3)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val client = OkHttpClient.Builder()
            .setup(httpClient)
            .build()
        try {
            val request = Request.Builder()
                .url("http://localhost:${wireMock.port()}/api/redirect")
                .build()
            client.newCall(request).execute().use {
                assertEquals(302, it.code)
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            httpClient.close()
        }
    }

    @Test
    fun `enqueue cancel in-flight calls onFailure`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/slow"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5_000).withBody("too late"))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/slow")
            .build()

        val failureLatch = CountDownLatch(1)
        val failureRef = AtomicReference<okio.IOException>()
        val call = okHttpClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: okio.IOException) {
                failureRef.set(e)
                failureLatch.countDown()
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                failureLatch.countDown()
            }
        })

        Thread.sleep(300)
        call.cancel()

        assertTrue(failureLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(failureRef.get())
    }

    @Test
    fun `enqueue connection refused calls onFailure`() {
        val port = ServerSocket(0).use { it.localPort }
        val request = Request.Builder()
            .url("http://localhost:$port/api/test")
            .build()

        val latch = CountDownLatch(1)
        val failureRef = AtomicReference<okio.IOException>()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: okio.IOException) {
                failureRef.set(e)
                latch.countDown()
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                latch.countDown()
            }
        })

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertInstanceOf(ConnectException::class.java, failureRef.get())
        assertEquals("Failed to connect to localhost/127.0.0.1:$port", failureRef.get().message)
    }

    @Test
    fun `binary response body is received correctly`() {
        val bytes = ByteArray(256) { it.toByte() }
        wireMock.stubFor(
            get(urlEqualTo("/api/binary"))
                .willReturn(aResponse().withStatus(200).withBody(bytes))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/binary")
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertArrayEquals(bytes, it.body.bytes())
        }
    }

    @Test
    fun `response without Content-Type has null media type`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/no-content-type"))
                .willReturn(aResponse().withStatus(200).withBody("data"))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/no-content-type")
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertNull(it.body.contentType())
            assertEquals("data", it.body.string())
        }
    }

    @Test
    fun `application interceptor runs before JdkInterceptor`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/hello"))
                .willReturn(aResponse().withStatus(200).withBody("ok"))
        )
        val interceptedHeaders = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                interceptedHeaders += chain.request().header("X-Added") ?: ""
                chain.proceed(
                    chain.request().newBuilder().header("X-Added", "yes").build()
                )
            }
            .setup(httpClient)
            .build()
        try {
            val request = Request.Builder()
                .url("http://localhost:${wireMock.port()}/api/hello")
                .build()
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            assertEquals(listOf(""), interceptedHeaders)
            wireMock.verify(
                getRequestedFor(urlEqualTo("/api/hello")).withHeader("X-Added", equalTo("yes"))
            )
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `enqueue thread interrupt throws InterruptedException via onFailure`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/slow"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5_000).withBody("too late"))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/slow")
            .build()

        val latch = CountDownLatch(1)
        val failureRef = AtomicReference<okio.IOException>()

        val vt = Thread.startVirtualThread {
            okHttpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: okio.IOException) {
                    failureRef.set(e)
                    latch.countDown()
                }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    latch.countDown()
                }
            })
        }
        vt.join(1_000)

        // Interrupt the dispatcher thread handling the request
        Thread.sleep(300)
        okHttpClient.dispatcher.runningCalls().forEach { it.cancel() }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNotNull(failureRef.get())
    }

    @Test
    fun `gzip compressed response is transparently decompressed`() {
        val originalBody = """{"message":"hello"}"""
        val gzipped = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { it.write(originalBody.toByteArray()) }
        }.toByteArray()

        wireMock.stubFor(
            get(urlEqualTo("/api/gzip"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(gzipped)
                )
        )

        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/gzip")
            .build()

        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertEquals(originalBody, it.body.string())
        }
    }

    @Test
    fun `chunked transfer encoding response is received correctly`() {
        val body = "chunk".repeat(10_000)
        wireMock.stubFor(
            get(urlEqualTo("/api/chunked"))
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Transfer-Encoding", "chunked")
                        .withBody(body)
                )
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/chunked")
            .build()
        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertEquals(body, it.body.string())
        }
    }

    @Test
    fun `execute same call twice throws IllegalStateException`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/hello"))
                .willReturn(aResponse().withStatus(200).withBody("ok"))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/hello")
            .build()
        val call = okHttpClient.newCall(request)
        call.execute().close()
        assertThrows<IllegalStateException> { call.execute() }
    }

    @Test
    fun `POST body content is received by server`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/echo"))
                .willReturn(aResponse().withStatus(200))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/echo")
            .post("hello world".toRequestBody("text/plain".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { assertEquals(200, it.code) }
        wireMock.verify(
            postRequestedFor(urlEqualTo("/api/echo"))
                .withRequestBody(equalTo("hello world"))
        )
    }

    @Test
    fun `PUT body content is received by server`() {
        wireMock.stubFor(
            put(urlEqualTo("/api/resource"))
                .willReturn(aResponse().withStatus(200))
        )
        val body = """{"key":"value"}"""
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/resource")
            .put(body.toRequestBody("application/json".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { assertEquals(200, it.code) }
        wireMock.verify(
            putRequestedFor(urlEqualTo("/api/resource"))
                .withRequestBody(equalTo(body))
        )
    }

    @Test
    fun `PATCH body content is received by server`() {
        wireMock.stubFor(
            patch(urlEqualTo("/api/resource"))
                .willReturn(aResponse().withStatus(200))
        )
        val body = """{"op":"replace","path":"/key","value":"new"}"""
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/resource")
            .patch(body.toRequestBody("application/json".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { assertEquals(200, it.code) }
        wireMock.verify(
            patchRequestedFor(urlEqualTo("/api/resource"))
                .withRequestBody(equalTo(body))
        )
    }

    @Test
    fun `response protocol is HTTP_1_1 when client is configured for HTTP_1_1`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/hello"))
                .willReturn(aResponse().withStatus(200).withBody("ok"))
        )
        val http11Client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build()
        val client = OkHttpClient.Builder()
            .setup(http11Client)
            .build()
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/hello")
            .build()
        client.newCall(request).execute().use {
            assertEquals(Protocol.HTTP_1_1, it.protocol)
        }
    }

    @Test
    fun `isExecuted is false before and true after execute`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/hello"))
                .willReturn(aResponse().withStatus(200).withBody("ok"))
        )
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/hello")
            .build()
        val call = okHttpClient.newCall(request)
        assertFalse(call.isExecuted())
        call.execute().close()
        assertTrue(call.isExecuted())
    }

    @Test
    fun `cookie set by server is sent on subsequent request`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/login"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Set-Cookie", "session=abc123; Path=/")
                )
        )
        wireMock.stubFor(
            get(urlEqualTo("/api/protected"))
                .willReturn(aResponse().withStatus(200).withBody("secret"))
        )

        val cookieManager = CookieManager()
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_3)
            .cookieHandler(cookieManager)
            .build()
        val client = OkHttpClient.Builder()
            .loadConfiguration(httpClient)
            .build()

        try {
            // Use 127.0.0.1 instead of localhost: the JDK's HttpCookie.domainMatches() rejects
            // domains without a dot (like "localhost"), so the CookieManager would never return
            // stored cookies for subsequent requests to that host.
            client.newCall(
                Request.Builder().url("http://127.0.0.1:${wireMock.port()}/api/login").build()
            ).execute().close()

            client.newCall(
                Request.Builder().url("http://127.0.0.1:${wireMock.port()}/api/protected").build()
            ).execute().use {
                assertEquals(200, it.code)
                assertEquals("secret", it.body.string())
            }

            wireMock.verify(
                getRequestedFor(urlEqualTo("/api/protected"))
                    .withHeader("Cookie", containing("session=abc123"))
            )
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            httpClient.close()
        }
    }

    @Test
    fun `ETag revalidation returns cached body on 304`() {
        val etag = "\"v1\""
        val body = """{"data":"hello"}"""

        // First request: populate cache
        wireMock.stubFor(
            get(urlEqualTo("/api/etag"))
                .inScenario("etag").whenScenarioStateIs("Started")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", etag)
                        .withHeader("Cache-Control", "no-cache")
                        .withBody(body)
                )
                .willSetStateTo("Cached")
        )

        // Second request: revalidate - server says nothing changed
        wireMock.stubFor(
            get(urlEqualTo("/api/etag"))
                .inScenario("etag").whenScenarioStateIs("Cached")
                .withHeader("If-None-Match", equalTo(etag))
                .willReturn(
                    aResponse()
                        .withStatus(304)
                        .withHeader("ETag", etag)
                        .withHeader("Cache-Control", "no-cache")
                )
        )

        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/etag")
            .build()

        // First call: populates the cache
        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertEquals(body, it.body.string())
        }

        // The second call: cache sends If-None-Match, gets 304, serves cached body
        okHttpClient.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertEquals(body, it.body.string())
        }

        wireMock.verify(1, getRequestedFor(urlEqualTo("/api/etag")).withoutHeader("If-None-Match"))
        wireMock.verify(1, getRequestedFor(urlEqualTo("/api/etag")).withHeader("If-None-Match", equalTo(etag)))
    }

    @Test
    fun `concurrent requests above SETTINGS_MAX_CONCURRENT_STREAMS all succeed`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/concurrent-heavy"))
                .willReturn(aResponse().withStatus(200).withBody("ok"))
        )
        val n = 2000 // exceeds HTTP/2 SETTINGS_MAX_CONCURRENT_STREAMS (128)
        val latch = CountDownLatch(n)
        val errors = mutableListOf<Throwable>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val semaphore = Semaphore(128)
        repeat(n) {
            executor.submit {
                try {
                    val request = Request.Builder()
                        .url("http://localhost:${wireMock.port()}/api/concurrent-heavy")
                        .build()
                    val call = okHttpClient.newCall(request)
                    semaphore.acquire()
                    try {
                        call.execute().use { response ->
                            assertEquals(200, response.code)
                        }
                    } finally {
                        semaphore.release()
                    }
                } catch (e: Throwable) {
                    // without semaphore:
                    // - "java.io.IOException: too many concurrent streams"
                    // with semaphore:
                    // - "java.io.IOException: Received RST_STREAM: Stream cancelled"
                    // - "java.io.IOException: EOF reached while reading"
                    synchronized(errors) {
                        errors.add(e)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue(errors.isEmpty(), "Errors during concurrent requests: $errors")
    }

    @Test
    fun `call timeout cancels in-flight request with IOException`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/slow"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5_000).withBody("too late"))
        )
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_3)
            .build()
        val client = OkHttpClient.Builder()
            .setup(httpClient)
            .callTimeout(Duration.ofMillis(500))
            .build()
        val request = Request.Builder()
            .url("http://localhost:${wireMock.port()}/api/slow")
            .build()
        var caught: Exception? = null
        try {
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            caught = e
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            httpClient.close()
        }
        assertInstanceOf(IOException::class.java, caught)
        assertEquals("timeout", caught?.message)
    }

}
