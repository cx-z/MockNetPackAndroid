# MockNetPackAndroid

MockNetPack 的 **Android Debug SDK**：应用内 HTTP 抓包上传与远程 Mock 客户端（iOS 端为 `iOS/MockNetPackKit`）。

> 当前为 **AN-0 骨架阶段**（0.1.0-SNAPSHOT）：仓库结构、构建配置、契约模型与模块骨架已就绪；
> 连接层（AN-1）、采集层（AN-2）、Mock 层（AN-3）、扫码连接（AN-4）按里程碑实现。
> 技术方案见 [`tasks/安卓SDK技术方案.md`](../../tasks/安卓SDK技术方案.md)。

## 产品定位

与 iOS SDK 完全对齐（除必要平台差异）：

- **流量采集**：应用内 HTTP 流量捕获并批量上传至中心 Mock 服务器（无系统代理、无证书）。
- **远程 Mock 客户端**：拉取设备级生效规则，按 `Method + URL 路径` 本地匹配并回放 canned response。
- **Fail-open**：服务器不可达/规则同步失败时，业务请求不受影响。
- **内网包专用（SDK–接入方解耦）**：SDK 对自身是否被集成完全无感（无运行期开关、不探测宿主）；「只在 BaseDemo 内网包集成」是接入方构建期决策——外网包不含本 SDK（依赖门控 + CI dex 校验，见技术方案 §8.3）。

## 必要平台差异（相对 iOS）

| 项 | iOS（MockNetPackKit） | Android（本 SDK） |
|---|---|---|
| 拦截机制 | URLProtocol 全局注册 + swizzle（业务零改动） | OkHttp application interceptor（宿主一行注入） |
| 持久化 | UserDefaults | SharedPreferences（需 `MockNetPack.initialize(context)`） |
| 扫码 | AVCaptureSession | CameraX + ML Kit（AN-4） |
| 并发/回调 | GCD 串行队列 + main dispatch | 协程单线程调度器 + Main dispatcher |
| gzip | zlib deflateInit2(MAX_WBITS+16) | java.util.zip.GZIPOutputStream（同为标准 gzip） |
| 系统信息 | Bundle ID / UIDevice | applicationId / Build.VERSION |

## 仓库结构

```
MockNetPackAndroid/
├── knowledgebase/           # 金字塔式知识库（overview/architecture/code-map，随实现补齐）
├── mocknetpack/             # SDK 库模块（com.mocknetpack.android）
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/mocknetpack/android/
│       │   ├── MockNetPack.kt            # 门面（start/stop/state/callbacks/codec/connectByScan）
│       │   ├── Models.kt                 # 契约模型（对齐 openapi/mocknetpack.yaml）
│       │   └── internal/
│       │       ├── connection/           # ConnectionController / ConnectionClient（AN-1）
│       │       ├── capture/              # TrafficCaptureController / MockNetPackInterceptor（AN-2/3）
│       │       ├── mock/                 # MockRuleController（AN-3）
│       │       ├── codec/                # BinaryCodec（gzip，已实现）
│       │       ├── store/                # DIDStore / ServerAddressStore（已实现）
│       │       ├── scan/                 # QRConnectParser（已实现）/ QRScannerActivity（AN-4）
│       │       └── log/                  # SDKLogger（已实现）
│       └── test/java/...                 # 本地单测（QR 协议解析 / gzip）
```

## 本地构建与测试

```bash
cd Android/MockNetPackAndroid
./gradlew :mocknetpack:assembleDebug      # 编译 SDK AAR
./gradlew :mocknetpack:testDebugUnitTest  # 本地单测
```

## 接入方式（宿主 App，Debug-only）

```kotlin
// 1) 初始化（Application.onCreate，Debug 构建）
MockNetPack.initialize(applicationContext)

// 2) 将拦截器加入业务 OkHttpClient（每个客户端工厂一行）
val client = OkHttpClient.Builder()
    .addInterceptor(MockNetPack.interceptor())
    .build()

// 3) 启动连接（直连服务器地址；或 connectByScan 扫码）
MockNetPack.start(server = "http://<host>:4290/api/v1")

// 4) Release 隔离：debugImplementation 引入 SDK + R8 keep；产物校验不含 SDK 类
```

详细接入与里程碑见技术方案文档。
