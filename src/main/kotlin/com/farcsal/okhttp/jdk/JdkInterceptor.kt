package com.farcsal.okhttp.jdk

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.*
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * OkHttp [Interceptor] that routes HTTP traffic through the JDK's built-in [HttpClient]
 * instead of OkHttp's network stack.
 *
 * Add it as the last application interceptor so all other interceptors run before
 * the request hits the wire and after a response is returned:
 * ```
 * OkHttpClient.Builder()
 *     .addInterceptor(JdkInterceptor.of(HttpClient.newBuilder().build()))
 *     .build()
 * ```
 *
 * Caveats:
 * - OkHttp's network interceptors and its internal caching are bypassed.
 * - Cancellation is propagated via polling (every 500 ms), not via a callback.
 * - Request bodies are fully buffered into the heap before sending; not suitable for large uploads.
 */
class JdkInterceptor private constructor(
    private val httpClient: HttpClient,
) : Interceptor {

    private val activeCalls = ConcurrentHashMap<Call, Thread>()
    private val scheduler = ScheduledThreadPoolExecutor(1)

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

        val jdkRequest = originalRequest.toJdkRequest(chain.readTimeoutMillis())
        activeCalls[call] = Thread.currentThread()
        val jdkResponse = try {
            httpClient.send(jdkRequest, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (call.isCanceled()) {
                throw IOException("Canceled", e)
            }
            throw e
        } catch (e: HttpConnectTimeoutException) {
            throw SocketTimeoutException("Connect timed out")
                .also { it.initCause(e) }
        } catch (e: HttpTimeoutException) {
            throw SocketTimeoutException("timeout")
                .also { it.initCause(e) }
        } catch (e: ConnectException) {
            val host = originalRequest.url.host
            val port = originalRequest.url.port
            val ip = "/" + InetAddress.getByName(host).hostAddress
            throw ConnectException("Failed to connect to $host$ip:$port")
                .also { it.initCause(e) }
        } catch (e: IOException) {
            if (e.message?.equals("Connection reset") == true) {
                throw SocketException(e.message, e)
            }
            throw e
        } finally {
            activeCalls.remove(call)
        }
        return buildResponse(jdkResponse, originalRequest)
    }

    private fun buildResponse(
        httpResponse: HttpResponse<InputStream>,
        original: Request,
    ): Response {
        val mediaType = httpResponse.headers().firstValue("Content-Type").orElse(null)?.toMediaTypeOrNull()
        val contentLength = httpResponse.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull() ?: -1L
        val source = httpResponse.body().source().buffer()

        val responseBody = object : ResponseBody() {
            override fun contentType(): MediaType? = mediaType
            override fun contentLength(): Long = contentLength
            override fun source(): okio.BufferedSource = source
        }

        val protocol = when (httpResponse.version().name) {
            "HTTP_3" -> Protocol.HTTP_3
            "HTTP_2" -> Protocol.HTTP_2
            "HTTP_1_1" -> Protocol.HTTP_1_1
            else -> throw IllegalStateException("Unknown protocol: ${httpResponse.version()}")
        }

        val okHeaders = Headers.Builder().apply {
            httpResponse.headers().map().forEach { (name, values) ->
                values.forEach { value -> add(name, value) }
            }
        }.build()

        return Response.Builder()
            .request(original)
            .protocol(protocol)
            .code(httpResponse.statusCode())
            .message("")
            .headers(okHeaders)
            .body(responseBody)
            .build()
    }

    companion object {
        private const val CANCELLATION_POLL_INTERVAL_MILLIS = 500L

        @JvmStatic
        fun of(httpClient: HttpClient): JdkInterceptor = JdkInterceptor(httpClient)
    }

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
