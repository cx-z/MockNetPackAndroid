# MockNetPackAndroid

MockNetPack's **Android Debug SDK** — an in-app HTTP capture/upload and remote mock client (the iOS counterpart is `iOS/MockNetPackKit` in the server repo).

> Current version **1.0.0**. Contains the connection layer (registration/heartbeat/session state), the OkHttp capture interceptor, the remote mock client, and the QR scan connection flow.

## What it does

Fully aligned with the iOS SDK except for necessary platform differences:

- **Traffic capture**: captures in-app HTTP traffic and uploads it in batches to the central mock server (no system proxy, no certificates).
- **Remote mock client**: pulls device-scoped enabled rules, matches locally on `Method + URL path`, and replays canned responses.
- **Fail-open**: business requests are never blocked when the server is unreachable or rule sync fails.
- **Debug-package only (SDK–integrator decoupling)**: the SDK makes **no Debug/Release decision itself** — it has no runtime switch and never probes the host build type. Whether (and how) to integrate is entirely the integrating app's build-time decision (see "Release isolation" below).

## Integration (GitHub + JitPack, by version)

The SDK is published to GitHub and consumed through JitPack by version number. No local Maven installs, no code copy.

```kotlin
// settings.gradle.kts — add the JitPack repository
dependencyResolutionManagement {
    repositories {
        // ...existing repositories
        maven(url = "https://jitpack.io")
    }
}

// app/build.gradle.kts — Debug variant only (see "Release isolation")
dependencies {
    debugImplementation("com.github.cx-z:MockNetPackAndroid:1.0.0")
}
```

To upgrade, just bump the version in the `debugImplementation` coordinate.

## Usage

Callers must **not hard-code the server address in code** — the server address is provided once by scanning the pairing QR code and is then persisted by the SDK (restart does not require re-scanning).

```kotlin
// 1) Initialize once in Application.onCreate (Debug build)
MockNetPack.initialize(applicationContext)

// 2) Add the interceptor to each business OkHttpClient factory (one line)
val client = OkHttpClient.Builder()
    .addInterceptor(MockNetPack.interceptor())
    .build()

// 3) Scan the pairing QR code (shows the server-side "Pair device" QR, e.g. from
//    the Web management UI): the SDK probes the server, registers the device
//    with the pairing token, persists the server address, and starts connecting.
MockNetPack.connectByScan(activity) { result ->
    when (result) {
        is ConnectByScanResult.Success -> // connected; address saved
        is ConnectByScanResult.Failure -> // see ConnectByScanError for classification
    }
}

// 4) Start (no-arg): reads the persisted server address + device ID and connects.
//    Before the first scan this enters UNCONFIGURED state (no network, waits for scan).
MockNetPack.start()
```

- First-time onboarding: scan once → `connectByScan` succeeds → address persisted → `start()` reconnects automatically on every launch (no re-scan).
- To point the SDK at a different server, scan again (a failed scan never overwrites a saved address).

### Server repository

The MockNetPack central server (Go) lives at: **https://github.com/cx-z/MockNetPackageServer.git**

It provides the HTTP endpoints the SDK talks to (`/api/v1/devices/register`, `/heartbeat`, `/traffic`, `/mock-rules`, pairing tokens), the Web management UI, and the Android pairing QR codes. See its README for server setup and the Web UI.

## Release isolation (Debug-only, SDK–integrator decoupling)

The requirement on the integrator is: **the Release artifact must not contain this SDK's code/symbols** (must be "not carried", not merely "not active"). The SDK itself does not self-inspect; the guarantee comes from the integrating app's build layer:

1. SDK is pulled in via `debugImplementation` only (zero references in the Release dependency graph);
2. all SDK call sites live in the `debug` source set (`src/debug/...`), so `main` code compiles without the SDK;
3. CI verifies the Release APK dex contains no `com.mocknetpack.android.*` symbols (e.g. `dexdump` grep = 0 hits) and blocks the release otherwise.

## Platform differences (vs iOS)

| Item | iOS (MockNetPackKit) | Android (this SDK) |
|---|---|---|
| Capture mechanism | URLProtocol global registration + swizzle (zero business changes) | OkHttp application interceptor (one line per OkHttpClient factory) |
| Persistence | UserDefaults | SharedPreferences (requires `MockNetPack.initialize(context)`) |
| QR scan | AVCaptureSession | CameraX + ML Kit (bundled, no Play Services) |
| Concurrency/callbacks | GCD serial queue + main dispatch | coroutine single-thread dispatcher + Main dispatcher |
| gzip | zlib deflateInit2(MAX_WBITS+16) | java.util.zip.GZIPOutputStream (standard gzip) |
| System info | Bundle ID / UIDevice | applicationId / Build.VERSION |

## Repository structure

```
MockNetPackAndroid/
├── jitpack.yml               # JitPack build config (JDK 17)
├── mocknetpack/              # SDK library module (com.mocknetpack.android)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/mocknetpack/android/
│       │   ├── MockNetPack.kt            # facade (initialize/interceptor/start/connectByScan/codec)
│       │   ├── Models.kt                 # contract models
│       │   └── internal/
│       │       ├── connection/           # ConnectionController / ConnectionClient
│       │       ├── capture/              # TrafficCaptureController / MockNetPackInterceptor
│       │       ├── mock/                 # MockRuleController
│       │       ├── codec/                # BinaryCodec (gzip)
│       │       ├── store/                # DIDStore / ServerAddressStore
│       │       ├── scan/                 # QRConnectParser / QRScannerActivity / ConnectByScanFlow
│       │       └── log/                  # SDKLogger
│       └── test/java/...                 # local unit tests
```

## Local build & test

```bash
cd MockNetPackAndroid
./gradlew :mocknetpack:assembleDebug      # build the SDK AAR
./gradlew :mocknetpack:testDebugUnitTest  # run unit tests
```

> On macOS, prefix Gradle commands with `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` (a plain `java` from the shell is unavailable).
