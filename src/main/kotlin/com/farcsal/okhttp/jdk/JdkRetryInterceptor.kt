package com.farcsal.okhttp.jdk

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.net.ssl.SSLException

class JdkRetryInterceptor(
    private val httpClient: HttpClient,
    private val maxConnectionRetries: Int,
) : Interceptor {

    init {
        require(maxConnectionRetries >= 0) { "maxConnectionRetries must be >= 0" }
    }

    // Per-connection completion semaphores: signaled whenever a request on that connection
    // finishes, so that a request blocked on "too many concurrent streams" can retry.
    private val connectionCompletions = ConcurrentHashMap<String, Semaphore>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        val connKey = connectionKey(chain.request())
        var connectionRetriesLeft = maxConnectionRetries
        while (true) {
            try {
                val response = chain.proceed(chain.request())
                return response.newBuilder()
                    .body(RetryResponseBody(connKey, connectionCompletions, response.body))
                    .build()
            } catch (e: IOException) {
                if (e.message == TOO_MANY_STREAMS_MESSAGE) {
                    // HTTP/2 SETTINGS_MAX_CONCURRENT_STREAMS exceeded on this connection.
                    // Wait until at least one in-flight request on the same connection completes,
                    // then retry.
                    val semaphore = connectionCompletions.getOrPut(connKey) { Semaphore(0) }
                    try {
                        semaphore.tryAcquire(TOO_MANY_STREAMS_WAIT_MILLIS, MILLISECONDS)
                    } catch (ie: InterruptedException) {
                        connectionCompletions.signalCompletion(connKey)
                        Thread.currentThread().interrupt()
                        if (call.isCanceled()) {
                            throw IOException("Canceled", ie)
                        }
                        throw ie
                    }
                    continue
                }
                if (isRetryable(e) && connectionRetriesLeft-- > 0) {
                    // Treat as a transient connection failure (RST_STREAM, EOF, broken pipe,
                    // connection reset during recycling, etc.).
                    // Signal waiters so a blocked "too many concurrent streams" request can
                    // proceed, then retry — the JDK will open a new connection if needed.
                    connectionCompletions.signalCompletion(connKey)
                    continue
                }
                connectionCompletions.signalCompletion(connKey)
                throw e
            } catch (t: Throwable) {
                connectionCompletions.signalCompletion(connKey)
                throw t
            }
        }
    }

    private class RetryResponseBody(
        private val connKey: String,
        private val connectionCompletions: ConcurrentHashMap<String, Semaphore>,
        private val delegate: ResponseBody,
    ) : ResponseBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): okio.BufferedSource = delegate.source()
        override fun close() {
            connectionCompletions.signalCompletion(connKey)
            delegate.close()
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

    companion object {
        private const val TOO_MANY_STREAMS_MESSAGE = "too many concurrent streams"
        private const val TOO_MANY_STREAMS_WAIT_MILLIS = 5_000L
    }

}

private fun ConcurrentHashMap<String, Semaphore>.signalCompletion(connKey: String) {
    this[connKey]?.release()
}
