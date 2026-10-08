# MockNetPackAndroid R8 keep rules
# ---------------------------------------------------------------------------
# 拦截器/连接层类均由宿主直接引用（addInterceptor / 门面方法），R8 正常保留；
# 以下 keep 覆盖通过反射/接口回调触达的成员，防止 release 混淆破坏状态机。

-keep class com.mocknetpack.android.MockNetPack { *; }
-keep class com.mocknetpack.android.internal.capture.MockNetPackInterceptor { *; }
-keep class com.mocknetpack.android.internal.connection.ConnectionController { *; }
-keep class com.mocknetpack.android.internal.mock.MockRuleController { *; }
-keep class com.mocknetpack.android.internal.capture.TrafficCaptureController { *; }

# AN-4 扫码：QRScannerActivity 由 SDK 内部 startActivity 启动（manifest 引用），
# 显式 keep 防止 release 混淆破坏 CameraX/ML Kit 回调链路。
# CameraX 与 ML Kit（bundled）的 AAR 自带 consumer rules，无需额外 keep。
-keep class com.mocknetpack.android.internal.scan.QRScannerActivity { *; }
-keep class com.mocknetpack.android.internal.scan.ConnectByScanFlow { *; }
