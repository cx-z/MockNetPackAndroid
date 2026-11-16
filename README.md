# MockNetPackAndroid

MockNetPack's Android debug SDK captures HTTP/HTTPS traffic through an OkHttp application interceptor, uploads it in batches, synchronizes device-scoped rules, and returns mock responses locally. No system proxy, VPN, or capture certificate is required.

The project release version is **1.1.0**, as declared in `gradle.properties`, the repository tag, and the runtime `MockNetPack.VERSION` reported during registration.

## Platform requirements

| Item | Current configuration |
|---|---|
| Minimum Android version | API 21 |
| SDK build configuration | compileSdk 36 / minor API 1, AGP 9.3.0 |
| Build JDK | JitPack uses JDK 17; Java compilation targets 11 |
| Business network integration | OkHttp application interceptor; current dependency is OkHttp 3.12.8 |
| QR scanning | CameraX + bundled ML Kit barcode, without Google Play Services |

Capture covers instrumented or explicitly installed OkHttp clients, wrapped `HttpURLConnection` connections, configured Debug WebViews, and exchanges reported by the host through `MockNetPack.reportHttp(...)`.

## Versioned integration

SDK repository: [MockNetPackAndroid](https://github.com/cx-z/MockNetPackAndroid). Add JitPack to the host project and include the SDK only in debug variants:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    debugImplementation("com.github.cx-z:MockNetPackAndroid:1.1.0")
}
```

Preserve existing repository configuration instead of duplicating the entire block. The SDK publication includes transitive OkHttp, CameraX, ML Kit, Activity, and coroutine dependencies. Check the host's resolved OkHttp / AndroidX dependency versions during integration.

## Initialization, interception, and pairing

Place SDK calls in debug implementations under `src/debug/...`:

```kotlin
import com.mocknetpack.android.MockNetPack
import okhttp3.OkHttpClient

// Initialize once during Application startup.
MockNetPack.initialize(applicationContext)
MockNetPack.start()

// Add an application interceptor to each business OkHttpClient factory
// whose traffic should be captured.
val client = OkHttpClient.Builder()
    .addInterceptor(MockNetPack.interceptor())
    .build()
```

> Prefer the build-time plugin below instead of hand-adding the interceptor to each client: it covers clients created inside third-party libraries too. `MockNetPack.install(builder)` is idempotent per builder, so mixing both paths never double-reports.

Without a saved address, `start()` enters `UNCONFIGURED` without contacting the server. If the host has a stable device ID, use `start(did = businessDeviceID)`. Otherwise, the SDK generates and persists an app-scoped did.

Launch QR pairing from an Activity's debug entry point:

```kotlin
import com.mocknetpack.android.ConnectByScanResult
import com.mocknetpack.android.MockNetPack

MockNetPack.connectByScan(this) { result ->
    when (result) {
        ConnectByScanResult.Success -> {
            // Address saved and connection started; no additional start() needed.
        }
        is ConnectByScanResult.Failure -> {
            // Handle result.error: cancellation, permission denial,
            // app mismatch, expired token, or other pairing errors.
        }
    }
}
```

The server address comes from the pairing QR code, so business code does not need a hard-coded address. Call `start()` on later launches to reconnect. Scan again to change servers; a failed scan does not overwrite the saved address. Callbacks run on the main thread. Avoid launching overlapping scan flows.

## Two-line plugin integration (recommended)

The `mocknetpack-instrument` Gradle plugin weaves `MockNetPack.installFromInstrumentation(builder)` into every `okhttp3.OkHttpClient(Builder)` constructor at build time, so clients created by libraries (image loaders, downloaders, ad SDKs) are captured without touching their code.

```groovy
// app/build.gradle — in buildscript { dependencies { ... } }
classpath files('libs/debug/mocknetpack-instrument-1.1.0-all.jar')
// after `apply plugin: 'com.android.application'`
apply plugin: 'com.mocknetpack.android.instrument'
```

Keep this JAR and the SDK AAR from the same build. Automatically installed clients also participate in the existing Mock rules.

Behavior and safety:

- **Debug only.** Only `debuggable` variants that are not `preview` are instrumented; release/preview builds contain neither the weaving nor the SDK.
- **Fail-open.** Unsupported configurations can skip instrumentation with a warning. If an AGP transform fails during execution, rebuild with the kill switch. The runtime call is guarded by `try/catch (Throwable)`.
- **Kill switch.** `-Pmocknetpack.instrument=false` or `mocknetpack { enabled = false }` disables it for one build.
- **No double reporting.** The plugin recognizes an installed SDK interceptor or decorated SDK interceptor, including when `newBuilder()` copies it.
- **Runtime evidence.** The SDK logs the first execution of its woven hook and the first eligible business OkHttp request separately. Check target library requests to validate coverage.
- **Build log.** With `-i`: `[mocknetpack] instrumentation enabled for variant '...'` / `variant '...' skipped: debug-only instrumentation`.

### WebView capture and Mock

In a Debug build, call `MockNetPack.installH5Capture(webView)` on the UI thread before the first load. The Gradle plugin can install the same hook at a configured WebView setup method through `mocknetpack { h5WebViewSetup = 'com.example.BrowserActivity#initWebView' }`.

The hook uses AndroidX WebKit document-start scripts and message listeners. It captures supported main-frame HTTP activity during an active session. Fetch and asynchronous XHR can use origin-scoped H5 Mock rules when the server advertises Android WebView support. Beacon, EventSource, navigation and resource events provide capture diagnostics.

### HttpURLConnection and self-reported traffic

```kotlin
// HttpURLConnection (only captured while a session is active; no Mock here)
val conn = MockNetPack.wrap(URL(url).openConnection() as HttpURLConnection)
try {
    conn.inputStream.use { /* read the body */ }
} finally {
    conn.disconnect()
}

// Self-built network stacks (Cronet, native callbacks, custom sockets)
MockNetPack.reportHttp(
    method = "GET", url = url, statusCode = 200, durationMs = 12,
    requestHeaders = mapOf(), responseHeaders = mapOf(),
    requestBody = null, responseBody = bytes, adapter = "cronet",
)
```

### 常见问题 / FAQ

- **设备页显示「尚未观测到插桩」** — the last registration did not report execution of the woven hook. Use APK bytecode to verify build-time weaving.
- **插件会不会改坏报文？** — No. Only one `invoke-static` is inserted into the constructor; request/response bytes are untouched (request bodies use a delegating tee that preserves `contentLength`).
- **会不会上报两次？** — No, see idempotency above. The same `TrafficEntry` appears once.
- **同一条流量会不会重复出现在 Web？** — Only if the same URL is genuinely requested by two different stacks; `adapter` distinguishes them. Capture does not de-duplicate by URL on purpose.
- **为什么 release 包里没有 SDK？** — By design; the plugin is debug-only and the dependency should stay `debugImplementation`.

### 抓不到什么（Known blind spots）

- **Native (NDK) HTTP stacks** — including ad SDKs that issue requests from native code; only their Java/Kotlin OkHttp layer is visible.
- **Raw sockets / custom protocols** — unless the host reports them via `reportHttp`.
- **OkHttp classes outside the transformed variant** — automatic installation applies to constructors that contain the woven call.
- **Release builds** — no SDK, no capture by design.

## Server pairing and capture

Server repository: [MockNetPackageServer](https://github.com/cx-z/MockNetPackageServer).

`server_address` represents the deployed server's DNS name or IP address. The Web console is `http://server_address:4290/mocknetpack/`; the SDK API base URL is `http://server_address:4290/api/v1`. Use the actual external scheme and ports if your deployment exposes different URLs. The local project checkout is for development and testing.

1. Add the host's actual applicationId to the server's app catalog.
2. Sign in to the Web console, select the app, and generate a pairing QR code. Tokens expire after 10 minutes by default.
3. Scan from the app. The device is registered or reused under the pairing account, and the SDK saves the address and starts heartbeats.
4. Activate a capture session in the Web console. Capture and mock execution start only while the session is active.
5. Create and enable a rule from traffic or manually. The SDK refreshes its local snapshot when the rules version changes.

Rules are isolated by `(App, did)` and match **method + URL path**, ignoring the host, query string, and request body. Unmatched requests reach the real server. A synchronization failure retains the existing rule snapshot; control-channel failures do not directly become business request errors.

## Permissions and network access

The SDK Manifest declares `INTERNET`, `CAMERA`, and an internal scanner Activity. They merge into host variants that include the SDK. The scanner requests camera permission at runtime; denial returns `CAMERA_DENIED`.

The phone must be able to reach the deployed server address in the QR code. For HTTP endpoints, allow the appropriate cleartext destinations through the host's debug Manifest / Network Security Config. Test camera and server connectivity on a device; JVM unit tests do not cover these system interactions.

## State and controls

| API | Purpose |
|---|---|
| `initialize(context)` | Supply ApplicationContext before other SDK operations |
| `interceptor()` | Supply an application interceptor for business OkHttpClient instances |
| `install(builder)` / `install(builder) { decorator }` | Idempotently attach the SDK interceptor to one builder (used by the plugin; keeps an existing crypto wrapper via `decorator`) |
| `wrap(conn)` / `wrap(conn, adapter)` | Capture an `HttpURLConnection` (no-op when no session is active) |
| `reportHttp(...)` | Report traffic from a self-built network stack (default `adapter = "business"`) |
| `start(did)` / `stop()` | Start or stop the connection; stopping preserves did and the saved address |
| `did` / `savedServerURL` | Inspect the device ID and saved address |
| `connectionState` | `UNCONFIGURED` / `CONNECTING` / `CONNECTED` / `OFFLINE` / `TOKEN_INVALID` |
| `sessionState` | `IDLE` / `CAPTURING` |
| `onConnectionStateChange` / `onSessionStateChange` | State callbacks on the main thread |
| `resetServer()` | Clear the saved address without stopping the current connection |
| `registerBinaryCodec(...)` | Display and mock-replay codecs for private binary protocols |

To return to an unconfigured state, call `stop()`, `resetServer()`, then `start()`. Pair again after the server-side device is deleted.

## Capture and replay boundaries

- The SDK records `networkResponse.request` when available and falls back to `response.request`. This provides the request associated with the final response.
- Response bodies are captured through a tee while business code reads them, with a 1 MiB recording buffer. Recording completes on EOF / close, so callers must consume or close response bodies. Recorded request bodies also have a 1 MiB limit.
- Uploads are asynchronous batches. Failed batches are dropped without retry. SDK requests are excluded from recursive capture.
- Binary replay uses original `bodyBase64` bytes, then edited text encoded by a codec, then UTF-8 text. `BinaryCompression.GZIP` compresses before encryption; the host supplies decryption and corresponding decompression.
- Supply `requestDecrypt` and `responseDecrypt` separately when needed. Without a request decoder, the SDK does not use the response decoder as a substitute.

## Release isolation

The SDK does not inspect the host build type or decide Debug/Release integration. **Release APK/AAB artifacts must not contain SDK code or symbols**:

- Use `debugImplementation` and place SDK calls in `src/debug/...`. Provide a Release implementation of shared business entry points without an SDK dependency.
- Do not reference the SDK directly from `src/main/...`. A `BuildConfig.DEBUG` condition does not replace dependency and source-set isolation.
- Host CI should check the Release dependency graph and final APK/AAB for `com.mocknetpack.android.*` symbols. Manage debug-specific network and permission configuration by variant as well.

The SDK's publication task uses a library variant named `release`. This does not mean the host's Release build should include the SDK.

## Repository and local testing

Implementation lives in `mocknetpack/src/main/java/com/mocknetpack/android/`. The facade is `MockNetPack.kt` and models are in `Models.kt`. Internal code is organized into connection / capture / mock / codec / store / scan / log modules.

Run from the SDK repository root with JDK 17 and the Android SDK available:

```bash
./gradlew :mocknetpack:assembleDebug
./gradlew :mocknetpack:testDebugUnitTest
```

On macOS, you can set `JAVA_HOME` to Android Studio's bundled JDK, for example `/Applications/Android Studio.app/Contents/jbr/Contents/Home`; use your actual installation path. Verify camera pairing, business network capture, deployed-server session integration, and Release isolation in the host project.
