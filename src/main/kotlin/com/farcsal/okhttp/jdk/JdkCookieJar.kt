package com.farcsal.okhttp.jdk

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.net.CookieHandler
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * [CookieJar] implementation that delegates cookie storage to a JDK [CookieHandler].
 *
 * Bridges OkHttp's cookie API to the JDK's [CookieHandler] so that both clients share the same
 * cookie store. Useful when a [java.net.http.HttpClient] is configured with a [CookieHandler]
 * (e.g. [java.net.CookieManager]) and the same cookies should be visible to OkHttp's interceptor
 * chain — most notably for WebSocket connections that bypass [JdkInterceptor].
 */
class JdkCookieJar(private val handler: CookieHandler) : CookieJar {

    companion object {
        private val HTTP_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val uri = url.toUri()
        val headers = handler.get(uri, emptyMap())
        return headers
            .filter { it.key.equals("Cookie", ignoreCase = true) }
            .values
            .flatten()
            .flatMap { it.split(';') }
            .mapNotNull { part ->
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                Cookie.Builder()
                    .name(trimmed.take(eq).trim())
                    .value(trimmed.substring(eq + 1))
                    .domain(url.host)
                    .build()
            }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val uri = url.toUri()
        val setCookieHeaders = cookies.map { cookie ->
            buildString {
                append(cookie.name).append('=').append(cookie.value)
                if (cookie.domain.isNotEmpty()) append("; Domain=").append(cookie.domain)
                append("; Path=").append(cookie.path)
                if (cookie.persistent) {
                    append("; Expires=").append(HTTP_DATE_FORMAT.format(Instant.ofEpochMilli(cookie.expiresAt)))
                }
                if (cookie.secure) append("; Secure")
                if (cookie.httpOnly) append("; HttpOnly")
            }
        }
        handler.put(uri, mapOf("Set-Cookie2" to setCookieHeaders))
    }

}
