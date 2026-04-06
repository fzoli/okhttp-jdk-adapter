package com.farcsal.okhttp.jdk

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.cache.CacheInterceptor

/**
 * An [Interceptor] that wraps OkHttp's internal [CacheInterceptor] and registers it as a regular
 * (application-level) interceptor.
 *
 * By default, OkHttp's [CacheInterceptor] runs as a built-in network interceptor, after all
 * user-configured interceptors. However, [JdkInterceptor] short-circuits the chain and hands off
 * the request to the JDK HTTP client directly, so the built-in [CacheInterceptor] never gets a
 * chance to run. To restore caching behaviour, [OkHttpCacheInterceptor] must be added **before**
 * [JdkInterceptor] in the application interceptor chain.
 *
 * Because application interceptors run for every request (including upgrades), this wrapper skips
 * cache handling for WebSocket connections to avoid errors caused by the `Upgrade: websocket`
 * handshake being processed by the cache logic.
 */
class OkHttpCacheInterceptor(cache: okhttp3.Cache) : Interceptor {

    private val delegate = CacheInterceptor(cache)

    override fun intercept(chain: Interceptor.Chain): Response {
        if ("websocket".equals(chain.request().header("Upgrade"), ignoreCase = true)) {
            return chain.proceed(chain.request())
        }
        return delegate.intercept(chain)
    }

}
