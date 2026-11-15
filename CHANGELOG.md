# Changelog

本仓库只保留最终态，不记录开发过程。以下为当前版本（1.0.1）的能力概述。

## 当前版本

MockNetPack Android Debug SDK（`mocknetpack`）+ 构建期插桩插件（`mocknetpack-instrument`）。内网调试用，**release 产物不得包含**。

### 采集能力（AN-10）

- **统一采集管线 `HttpExchangeRecorder`**：URL → path/query 解析、body 三态（文本 / `[binary N bytes]` + base64 样本 / `[body unavailable]`）、1 MiB 截断 + `[truncated N bytes]` 前缀、codec 解码槽、`adapter` 标记，全部收敛一处；`TrafficCaptureController.record(entry, generation)` 与 200 条 / 8 MiB / 2s 阈值不变。
- **OkHttp 全量采集**：`MockNetPack.install(builder)` / `install(builder) { decorator }`，单例拦截器 + 按 builder 幂等（`WeakHashMap`），宿主显式注入与插件织入不会双份上报。
- **HttpURLConnection 采集**：`MockNetPack.wrap(conn)`，仅在 `http/https` 且会话接管期生效；连接前快照请求头、剔除状态行、4xx 读 `getErrorStream()` 并保留状态码、首个终止事件幂等落一条记录。**只采集，不执行 Mock。**
- **业务自报**：`MockNetPack.reportHttp(...)`，`adapter` 默认 `business`。
- **请求体委托式 tee `TeeRequestBody`**：边写边采样（≤1 MiB），不缓冲全量、不重建请求体，`contentLength()` 沿用 delegate（报文形态不变，`Content-Length` 不丢）。

### 构建期插桩插件（`mocknetpack-instrument`，id `com.mocknetpack.android.instrument`）

- **默认开启**，仅 `debuggable` 且非 `preview` 变体；织入点固定为 `okhttp3.OkHttpClient.<init>(Lokhttp3/OkHttpClient$Builder;)V`（`super()` 之后、`interceptors` 拷贝之前），是 `new OkHttpClient()` 与 `Builder.build()` 的共同收敛点。
- **fail-open**：不兼容配置告警跳过，执行期 transform 失败可关闭插桩重建。运行时注入调用包 `try/catch (Throwable)`。
- **kill switch**：`-Pmocknetpack.instrument=false` 或 `mocknetpack { enabled = false }`。
- **三级可见性**：构建日志和 APK 字节码验证织入。运行时分别记录织入入口与首次业务流量，设备页显示注册时的织入观测。
- **发布形态**：JitPack 多模块配置（AAR + 插件标记），`selfContainedJar`（classifier `all`）把插件类与 ASM 打成单 jar，宿主只引一个文件即可 apply。

### 契约与平台

- `TrafficEntry.adapter` 与服务端透传、Web「采集方式」展示。
- 设备注册上报 `capabilities{adapters,instrumented,requestBodyTee}`，平台设备页展示来源与插桩状态。

### 版本与兼容

- `MockNetPack.VERSION` 与 `gradle.properties` 对齐为 1.0.1；AAR 元数据锁定为 1.9.0，Kotlin API 目标为 1.8，使生成的协程字节码兼容 Dokimo 的运行时。
- OkHttp 3.12.8 / Okio 1.14.0 基线；`RequestBody` 无 `isOneShot()` / `isDuplex()`，`TeeRequestBody` 不 override 这两个方法。
