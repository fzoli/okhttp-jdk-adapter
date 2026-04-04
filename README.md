# okhttp-jdk-adapter

An OkHttp [Interceptor](https://square.github.io/okhttp/features/interceptors/) that routes HTTP traffic through the JDK's built-in `java.net.http.HttpClient` instead of OkHttp's own network stack.

## Why?

OkHttp does not yet support HTTP/3. The JDK's `HttpClient` (Java 26+) does. This adapter lets you keep the OkHttp API (interceptors, call lifecycle, request/response model) while delegating the actual transport to the JDK client, which may support HTTP/3 depending on the JVM runtime.

## Usage

```kotlin
val httpClient = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_3)
    .build()

val okHttpClient = httpClient.okHttpClientBuilder()  // mirrors JDK settings onto OkHttp
    .addInterceptor(JdkInterceptor.of(httpClient))
    .build()
```

`JdkInterceptor` should be added as the **last application interceptor** so all other interceptors run before the request hits the wire and after the response is returned.

`HttpClient.okHttpClientBuilder()` is an extension function that creates an `OkHttpClient.Builder` pre-configured to mirror the JDK client's settings (connect timeout, redirect policy, HTTP version, proxy). It is optional but recommended to keep both clients in sync — especially since WebSocket connections bypass the JDK client entirely and are handled by OkHttp's native stack, so both clients remain active.

## Caveats

- **OkHttp's network interceptors and internal cache are bypassed.** Only application interceptors run.
- **Cancellation is polled every 500 ms**, not event-driven. There is a short window between a call being cancelled and the underlying request being interrupted.
- **Request bodies are fully buffered in heap memory before sending.** Not suitable for large streaming uploads.
- **WebSocket upgrades are passed through to OkHttp's native stack.** The JDK `HttpClient` is not used for WebSocket connections.
- **TLS/SSL configuration is not transferred** by `okHttpClientBuilder()`. Configure it separately on each client if needed.

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
