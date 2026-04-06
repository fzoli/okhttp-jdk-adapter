package com.farcsal.okhttp.jdk

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.internal.closeQuietly
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.*
import java.net.http.*
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession

/**
 * OkHttp [Interceptor] that routes HTTP traffic through the JDK's built-in [HttpClient]
 * instead of OkHttp's network stack.
 *
 * Usage:
 * ```
 *     import com.farcsal.okhttp.jdk.setup
 *     import okhttp3.OkHttpClient
 *     import java.net.http.HttpClient
 *
 *     private val httpClient = HttpClient.newBuilder()
 *         .version(HttpClient.Version.HTTP_3)
 *         .build()
 *
 *     private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
 *         .setup(httpClient)
 *         .build()
 * ```
 *
 * If you are not using the [com.farcsal.okhttp.jdk.setup] extension function, register this
 * interceptor manually as the **last** application interceptor (so all other interceptors run
 * before the request hits the wire) and also call [useEventListener] to obtain the
 * [okhttp3.EventListener] and pass it to [okhttp3.OkHttpClient.Builder.eventListener].
 *
 * Caveats:
 * - OkHttp's network interceptors and its internal caching are bypassed.
 * - Cancellation is propagated via polling (every 500 ms) unless [useEventListener] is used.
 * - Request bodies are fully buffered into the heap before sending; not suitable for large uploads.
 */
class JdkInterceptor private constructor(
    private val httpClient: HttpClient,
) : Interceptor {

    private val activeCalls = ConcurrentHashMap<Call, Pair<Thread, Response?>>()
    private val scheduler = ScheduledThreadPoolExecutor(1)

    // Per-connection completion semaphores: signalled whenever a request on that connection
    // finishes, so that a request blocked on "too many concurrent streams" can retry.
    private val connectionCompletions = ConcurrentHashMap<String, Semaphore>()

    private fun Pair<Thread, Response?>.interrupt() {
        this.second?.closeQuietly()
        this.first.interrupt()
    }

    private val eventListener: EventListener = object : EventListener() {
        override fun canceled(call: Call) {
            val t = activeCalls.get(call)
            t?.interrupt()
        }
    }

    init {
        // OkHttp doesn't expose a cancellation callback on the chain, so we poll.
        // See https://github.com/square/okhttp/issues/7164
        // Call.addEventListener will be available soon.
        scheduler.scheduleAtFixedRate(
            {
                val iter = activeCalls.entries.iterator()
                while (iter.hasNext()) {
                    val entry = iter.next()
                    if (entry.key.isCanceled()) {
                        iter.remove()
                        entry.value.interrupt()
                    }
                }
                if (httpClient.isTerminated) {
                    scheduler.shutdown()
                }
            },
            CANCELLATION_POLL_INTERVAL_MILLIS,
            CANCELLATION_POLL_INTERVAL_MILLIS,
            MILLISECONDS,
        )
    }

    /**
     * Switches from scheduler-based cancellation polling to event listener mode, where the
     * underlying JDK request is cancelled immediately when OkHttp signals cancellation,
     * instead of being detected on the next poll interval.
     *
     * Shuts down the internal scheduler and returns the [EventListener] to be registered
     * on the [okhttp3.OkHttpClient.Builder].
     */
    fun useEventListener(): EventListener {
        scheduler.shutdown()
        return eventListener
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        if (call.isCanceled()) {
            throw IOException("Canceled")
        }

        val originalRequest = chain.request()
        if ("websocket".equals(originalRequest.header("Upgrade"), ignoreCase = true)) {
            // WebSocket upgrades are HTTP/1.1-only and not handled by the JDK HttpClient here.
            // The primary purpose of this interceptor is to enable HTTP/3 support via the JDK stack,
            // so WebSocket connections are passed through to OkHttp's native implementation.
            return chain.proceed(originalRequest)
        }

        val thread = Thread.currentThread()
        val jdkRequest = originalRequest.toJdkRequest(chain.readTimeoutMillis())
        val connKey = connectionKey(originalRequest)
        activeCalls[call] = Pair(thread, null)

        var connectionRetriesLeft = MAX_CONNECTION_RETRIES
        while (true) {
            val jdkResponse = try {
                httpClient.send(jdkRequest, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: InterruptedException) {
                activeCalls.remove(call)
                signalCompletion(connKey)
                Thread.currentThread().interrupt()
                if (call.isCanceled()) {
                    throw IOException("Canceled", e)
                }
                throw e
            } catch (e: HttpConnectTimeoutException) {
                activeCalls.remove(call)
                signalCompletion(connKey)
                throw SocketTimeoutException("Connect timed out")
                    .also { it.initCause(e) }
            } catch (e: HttpTimeoutException) {
                activeCalls.remove(call)
                signalCompletion(connKey)
                throw SocketTimeoutException("timeout")
                    .also { it.initCause(e) }
            } catch (e: ConnectException) {
                activeCalls.remove(call)
                signalCompletion(connKey)
                val host = originalRequest.url.host
                val port = originalRequest.url.port
                val ip = "/" + InetAddress.getByName(host).hostAddress
                throw ConnectException("Failed to connect to $host$ip:$port")
                    .also { it.initCause(e) }
            } catch (e: IOException) {
                if (e.message == TOO_MANY_STREAMS_MESSAGE) {
                    // HTTP/2 SETTINGS_MAX_CONCURRENT_STREAMS exceeded on this connection.
                    // Wait until at least one in-flight request on the same connection completes,
                    // then retry. We keep the activeCalls entry alive during the wait so that
                    // cancellation is still tracked.
                    val semaphore = connectionCompletions.getOrPut(connKey) { Semaphore(0) }
                    try {
                        semaphore.tryAcquire(TOO_MANY_STREAMS_WAIT_MILLIS, MILLISECONDS)
                    } catch (ie: InterruptedException) {
                        activeCalls.remove(call)
                        signalCompletion(connKey)
                        Thread.currentThread().interrupt()
                        if (call.isCanceled()) throw IOException("Canceled", ie)
                        throw ie
                    }
                    if (call.isCanceled()) {
                        activeCalls.remove(call)
                        signalCompletion(connKey)
                        throw IOException("Canceled")
                    }
                    continue
                }
                if (isRetryable(e) && connectionRetriesLeft-- > 0) {
                    // Treat as a transient connection failure (RST_STREAM, EOF, broken pipe,
                    // connection reset during recycling, etc.).
                    // Signal waiters so a blocked "too many concurrent streams" request can
                    // proceed, then retry — the JDK will open a new connection if needed.
                    signalCompletion(connKey)
                    continue
                }
                activeCalls.remove(call)
                signalCompletion(connKey)
                if (e.message?.equals("Connection reset") == true) {
                    throw SocketException(e.message, e)
                }
                throw e
            } catch (t: Throwable) {
                activeCalls.remove(call)
                signalCompletion(connKey)
                throw t
            }

            return try {
                val response = buildResponse(call, jdkResponse, originalRequest, connKey)
                activeCalls[call] = Pair(thread, response)
                response
            } catch (t: Throwable) {
                activeCalls.remove(call)
                signalCompletion(connKey)
                throw t
            }
        }
    }

    // Mirrors OkHttp's RetryAndFollowUpInterceptor.isRecoverable() logic.
    private fun isRetryable(e: IOException): Boolean {
        if (e is UnknownHostException) return false   // DNS failure — no point retrying
        if (e is ProtocolException) return false      // protocol violation
        if (e is InterruptedIOException) return false // intentional interruption
        if (e is SSLException) return false           // SSL/TLS failure (cert, pinning, etc.)
        return true
    }

    private fun signalCompletion(connKey: String) {
        connectionCompletions[connKey]?.release()
    }

    // Compute the connection pool key matching JDK's internal Http2ClientImpl pool key format:
    //   C:H:host:port  — direct HTTP
    //   S:H:host:port  — direct HTTPS
    //   C:P:proxy:port — clear HTTP through proxy
    //   S:T:H:host:port;P:proxy:port — HTTPS tunnel through proxy
    private fun connectionKey(request: Request): String {
        val secure = request.url.scheme == "https"
        val host = request.url.host
        val port = request.url.port
        val proxy = httpClient.proxy().orElse(null)
            ?.select(URI(request.url.toString()))
            ?.firstOrNull()
            ?.takeIf { it.type() != Proxy.Type.DIRECT }
            ?.address() as? InetSocketAddress
        return when {
            proxy != null && !secure -> "C:P:${proxy.hostString}:${proxy.port}"
            proxy != null && secure  -> "S:T:H:$host:$port;P:${proxy.hostString}:${proxy.port}"
            secure                   -> "S:H:$host:$port"
            else                     -> "C:H:$host:$port"
        }
    }

    private fun buildResponse(
        call: Call,
        httpResponse: HttpResponse<InputStream>,
        original: Request,
        connKey: String,
    ): Response {
        val mediaType = httpResponse.headers().firstValue("Content-Type").orElse(null)?.toMediaTypeOrNull()
        val contentLength = httpResponse.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull() ?: -1L
        val source = httpResponse.body().source().buffer()

        val responseBody = object : ResponseBody() {
            override fun contentType(): MediaType? = mediaType
            override fun contentLength(): Long = contentLength
            override fun source(): okio.BufferedSource = source
            override fun close() {
                activeCalls.remove(call)
                signalCompletion(connKey)
                super.close()
            }
        }

        val protocol = when (httpResponse.version()) {
            HttpClient.Version.HTTP_3 -> Protocol.HTTP_3
            HttpClient.Version.HTTP_2 -> Protocol.HTTP_2
            HttpClient.Version.HTTP_1_1 -> Protocol.HTTP_1_1
            else -> throw IllegalStateException("Unknown protocol: ${httpResponse.version()}")
        }

        val okHeaders = Headers.Builder().apply {
            httpResponse.headers().map().forEach { (name, values) ->
                values.forEach { value -> add(name, value) }
            }
        }.build()

        val handshake = httpResponse.sslSession().orElse(null)?.toOkHttpHandshake()

        return Response.Builder()
            .request(original)
            .protocol(protocol)
            .code(httpResponse.statusCode())
            .message("")
            .headers(okHeaders)
            .body(responseBody)
            .handshake(handshake)
            .build()
    }

    companion object {
        private const val CANCELLATION_POLL_INTERVAL_MILLIS = 500L
        private const val TOO_MANY_STREAMS_MESSAGE = "too many concurrent streams"
        private const val TOO_MANY_STREAMS_WAIT_MILLIS = 5_000L
        private const val MAX_CONNECTION_RETRIES = 5

        @JvmStatic
        fun of(httpClient: HttpClient): JdkInterceptor = JdkInterceptor(httpClient)
    }

}

private fun SSLSession.toOkHttpHandshake(): Handshake {
    val cipher = CipherSuite.forJavaName(cipherSuite)
    val peers = peerCertificates?.toList() ?: emptyList()
    val locals = localCertificates?.toList() ?: emptyList()
    val tls = TlsVersion.forJavaName(protocol)
    return Handshake.get(tls, cipher, peers, locals)
}

private fun Request.toJdkRequest(readTimeoutMillis: Int): HttpRequest {
    val builder = HttpRequest.newBuilder(URI.create(url.toString()))
    headers.forEach { (name, value) -> builder.header(name, value) }
    val publisher = body?.toBodyPublisher() ?: HttpRequest.BodyPublishers.noBody()
    builder.method(method, publisher)
    if (readTimeoutMillis > 0) {
        builder.timeout(Duration.ofMillis(readTimeoutMillis.toLong()))
    }
    return builder.build()
}

private fun RequestBody.toBodyPublisher(): HttpRequest.BodyPublisher {
    val buffer = okio.Buffer()
    writeTo(buffer)
    return HttpRequest.BodyPublishers.ofByteArray(buffer.readByteArray())
}
