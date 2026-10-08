package com.mocknetpack.android.internal.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.mocknetpack.android.ConnectByScanError
import com.mocknetpack.android.MockNetPack
import com.mocknetpack.android.internal.connection.ConnectionClient
import com.mocknetpack.android.internal.log.SDKLogger
import com.mocknetpack.android.internal.store.DIDStore
import com.mocknetpack.android.internal.store.ServerAddressStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AN-4 扫码界面（CameraX PreviewView + ML Kit barcode 扫描）。
 *
 * 对齐 iOS QRScannerViewController 语义：
 * - 相机权限运行时请求（Android 无 AVFoundation 授权传播延迟，预授权可省，
 *   但保持"扫码页内先确认权限再建相机"的失败分类：拒绝 → CAMERA_DENIED）；
 * - 扫描到 `mocknetpack://connect?...` 即停止分析并进入注册流程；
 * - 解析失败分类：appID 不匹配 → APP_MISMATCH；其他 → INVALID_PAYLOAD；
 * - 注册流程（probe → register → 持久化 → 启动）交给 ConnectByScanFlow，
 *   结果经 MockNetPack.deliverScanResult 回传门面（宿主 connectByScan 回调）。
 *
 * 基类用 ComponentActivity（CameraX bindToLifecycle 需要 LifecycleOwner，
 * 无 appcompat 依赖）；协程 scope 自管（onDestroy cancel）。
 */
class QRScannerActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_CAMERA = 871
    }

    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    /// 已处理一次扫码/结果；防重复回调（analyzer 多帧并发 + 生命周期兜底）。
    @Volatile
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        setContentView(
            FrameLayout(this).apply {
                addView(
                    previewView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            },
        )

        // 相机权限：已授予直接建相机；未授予运行时请求（拒绝 → CAMERA_DENIED）。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            initCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        if (requestCode != REQUEST_CAMERA) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            return
        }
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            initCamera()
        } else {
            finishWith(ConnectByScanError.CAMERA_DENIED)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        imageAnalysis?.clearAnalyzer()
        scanScope.cancel()
        analyzerExecutor.shutdown()
        // 用户返回（未扫码）→ 兜底 CANCELLED（幂等：已 deliver 过则 no-op）。
        MockNetPack.deliverScanResultIfPending(ConnectByScanError.CANCELLED)
    }

    // MARK: - 相机

    private fun initCamera() {
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener(
                {
                    val provider = future.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analyzerExecutor) { imageProxy -> processFrame(imageProxy) }
                    imageAnalysis = analysis
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                },
                ContextCompat.getMainExecutor(this),
            )
        } catch (e: Exception) {
            SDKLogger.error("camera init failed: ${e.message}", category = "scan")
            finishWith(ConnectByScanError.CAMERA_UNAVAILABLE)
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(input)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull()?.rawValue
                if (raw != null && !handled) {
                    handled = true
                    imageAnalysis?.clearAnalyzer()
                    handleRaw(raw)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    // MARK: - 扫码结果处理

    private fun handleRaw(raw: String) {
        when (val result = QRConnectParser.parse(raw, expectedAppID = packageName)) {
            is QRConnectResult.Success -> runRegistration(result.payload)
            is QRConnectResult.Failure -> when (result.error) {
                is QRConnectParseError.AppMismatch ->
                    finishWith(ConnectByScanError.APP_MISMATCH)
                else ->
                    finishWith(ConnectByScanError.INVALID_PAYLOAD)
            }
        }
    }

    /// 注册流程（对齐 iOS connectByScan 核心）：probe → 令牌注册 → 持久化 + 启动。
    private fun runRegistration(payload: QRConnectPayload) {
        val appID = packageName
        val did = DIDStore.did(appID)
        scanScope.launch {
            ConnectByScanFlow.execute(
                payload = payload,
                appID = appID,
                did = did,
                appVersion = MockNetPack.hostVersionName() ?: "",
                appName = MockNetPack.hostAppName(),
                sdkVersion = MockNetPack.VERSION,
                osVersion = Build.VERSION.RELEASE ?: "",
                clientFactory = { ConnectionClient.create(it) },
                onStop = { MockNetPack.stop() },
                onSave = { server, app -> ServerAddressStore.save(server, app) },
                onStart = { server, app, d ->
                    MockNetPack.start(server = server, appId = app, did = d)
                },
                onResult = { error -> finishWith(error) },
            )
        }
    }

    private fun finishWith(error: ConnectByScanError?) {
        if (handled && error == null) {
            // 成功：已 handled，正常收尾。
        }
        runOnUiThread {
            MockNetPack.deliverScanResult(error)
            finish()
        }
    }
}
