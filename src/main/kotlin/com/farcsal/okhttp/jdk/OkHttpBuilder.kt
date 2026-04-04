package com.farcsal.okhttp.jdk

import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.http.HttpClient

/**
 * Creates an [OkHttpClient.Builder] pre-configured to mirror this [HttpClient]'s settings,
 * so both clients stay in sync without duplicating configuration.
 *
 * This is useful when [JdkInterceptor] does not terminate the interceptor chain -- for example,
 * when OkHttp is used for WebSocket upgrades while the JDK client handles the underlying HTTP
 * traffic. In that case OkHttp still needs its own compatible settings.
 *
 * **Not a complete solution:** only a subset of settings is propagated (connect timeout, redirect
 * policy, HTTP version, proxy). Notably, TLS/SSL configuration is not transferred.
 */
fun HttpClient.okHttpClientBuilder(): OkHttpClient.Builder {
    val b = OkHttpClient.Builder()

    connectTimeout().ifPresent { b.connectTimeout(it) }

    when (followRedirects()) {
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

    when (version()) {
        HttpClient.Version.HTTP_1_1 -> b.protocols(listOf(Protocol.HTTP_1_1))
        HttpClient.Version.HTTP_2, HttpClient.Version.HTTP_3 -> b.protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
    }

    proxy().ifPresent { b.proxySelector(it) }

    return b
}
