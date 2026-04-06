# okhttp-jdk-adapter

An OkHttp [Interceptor](https://square.github.io/okhttp/features/interceptors/) that routes HTTP traffic through the JDK's built-in `java.net.http.HttpClient` instead of OkHttp's own network stack.

## Why?

OkHttp does not yet support HTTP/3. The JDK's `HttpClient` (Java 26+) does. This adapter lets you keep the OkHttp API (interceptors, call lifecycle, request/response model) while delegating the actual transport to the JDK client, which may support HTTP/3 depending on the JVM runtime.

## Usage

```kotlin
// import com.farcsal.okhttp.jdk.setup
// import okhttp3.OkHttpClient
// import java.net.http.HttpClient

private val httpClient = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_3)
    .build()

private val okHttpClient = OkHttpClient.Builder()
    .setup(httpClient)
    .build()
```

## Caveats

- **OkHttp's network interceptors are bypassed.** Only application interceptors run. The internal cache can be enabled by adding `OkHttpCacheInterceptor` as an application interceptor before calling `setup(httpClient)`.
- **Request bodies are fully buffered in heap memory before sending.** Not suitable for large streaming uploads.
- **WebSocket upgrades are passed through to OkHttp's native stack.** The JDK `HttpClient` is not used for WebSocket connections.
- **TLS/SSL configuration is not transferred** by `setup(httpClient)`. Configure it separately on each client if needed.
- **HTTP/2 throughput may be lower than expected.** The JDK `HttpClient` maintains a process-level connection pool and honors the server's `SETTINGS_MAX_CONCURRENT_STREAMS` limit. Exceeding the limit raises an `IOException` not only on the offending request but potentially on other in-flight requests sharing the same connection. Use the `retryOnFailure` parameter of `setup(httpClient, retryOnFailure = RetryOnFailure(...))` to handle these transient failures transparently.

## Requirements

- Java 21+
- OkHttp 5.x

## Building

```bash
./gradlew build
```

## Running tests

```bash
./gradlew test
```

Tests use [WireMock](https://wiremock.org/) and OkHttp's `MockWebServer`.
