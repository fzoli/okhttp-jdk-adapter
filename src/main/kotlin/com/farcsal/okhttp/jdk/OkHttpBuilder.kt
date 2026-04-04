package com.farcsal.okhttp.jdk

import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.http.HttpClient

/**
 * Convenience function that wires a [JdkInterceptor] into this builder in a single call,
 * optionally mirroring the [HttpClient]'s configuration via [load].
 *
 * **Single event listener limitation:** OkHttp supports only one [okhttp3.EventListener] at a time,
 * and this function registers the one required by [JdkInterceptor] automatically. If you need
 * additional listeners, combine them first with [okhttp3.EventListener.plus] and set the result
 * manually — in that case, do not use this convenience function.
 *
 * @param httpClient The JDK [HttpClient] to delegate requests to.
 * @param loadConfiguration If `true` (default), calls [load] to mirror the [HttpClient]'s settings
 *   (connect timeout, redirect policy, HTTP version, proxy) onto this builder before adding the
 *   interceptor.
 */
fun OkHttpClient.Builder.setup(httpClient: HttpClient, loadConfiguration: Boolean = true): OkHttpClient.Builder {
    if (loadConfiguration) {
        loadConfiguration(httpClient)
    }
    val interceptor = JdkInterceptor.of(httpClient)
    addInterceptor(interceptor)
    eventListener(interceptor.useEventListener())
    return this
}

/**
 * Mirrors a subset of this [HttpClient]'s settings onto the builder so that both clients stay
 * in sync without duplicating configuration.
 *
 * This is useful when [JdkInterceptor] does not terminate the interceptor chain — for example,
 * when OkHttp is used for WebSocket upgrades while the JDK client handles the underlying HTTP
 * traffic. In that case OkHttp still needs its own compatible settings.
 *
 * **Propagated settings:** connect timeout, redirect policy, HTTP version
 * ([HttpClient.Version.HTTP_1_1] → HTTP/1.1 only; [HttpClient.Version.HTTP_2] or
 * [HttpClient.Version.HTTP_3] → HTTP/2 + HTTP/1.1), and proxy selector.
 *
 * **Not propagated:** TLS/SSL configuration and other advanced settings.
 */
fun OkHttpClient.Builder.loadConfiguration(httpClient: HttpClient): OkHttpClient.Builder {
    val b = this
    httpClient.connectTimeout().ifPresent { b.connectTimeout(it) }

    when (httpClient.followRedirects()) {
        HttpClient.Redirect.NEVER -> {
            b.followRedirects(false)
            b.followSslRedirects(false)
        }
        HttpClient.Redirect.ALWAYS -> {
            b.followRedirects(true)
            b.followSslRedirects(true)
        }
        HttpClient.Redirect.NORMAL -> {
            // follow same-protocol redirects, but not HTTPS->HTTP
            b.followRedirects(true)
            b.followSslRedirects(false)
        }
    }

    when (httpClient.version()) {
        HttpClient.Version.HTTP_1_1 -> b.protocols(listOf(Protocol.HTTP_1_1))
        HttpClient.Version.HTTP_2, HttpClient.Version.HTTP_3 -> b.protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
    }

    httpClient.proxy().ifPresent { b.proxySelector(it) }

    return this
}
