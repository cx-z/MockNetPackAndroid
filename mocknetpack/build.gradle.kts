plugins {
    alias(libs.plugins.android.library)
    // 发布方式：SDK 发布到 GitHub（cx-z/MockNetPackAndroid），接入方经
    // JitPack 按 tag 解析对应版本的 SDK。
    // POM 携带完整传递依赖，包括 CameraX、ML Kit 和 WebKit。
    id("maven-publish")
}

android {
    namespace = "com.mocknetpack.android"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // 对齐宿主 minSdk 21（OkHttp 3.12.x 也是最后一条支持 API 21 的主线）。
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Maven 坐标使用项目的 group 和 version，artifactId 与仓库名一致。
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = project.group.toString()
                artifactId = "MockNetPackAndroid"
                version = project.version.toString()
                from(components.findByName("release")
                    ?: error("android component missing; available: ${components.names}"))
            }
        }
    }
}

dependencies {
    // 公开 API 暴露 okhttp3.Interceptor（MockNetPack.interceptor()），
    // 宿主需在 OkHttpClient.Builder 上直接 addInterceptor —— 故为 api 依赖。
    api(libs.okhttp)
    // 发布与已验证宿主兼容的标准库，避免把 SDK 编译器的运行库版本传递给宿主。
    api(libs.kotlin.stdlib)
    // 连接层/采集层内部调度使用协程（对宿主不可见）。
    implementation(libs.coroutines.android)
    // 扫码：CameraX（PreviewView/ImageAnalysis）+ ML Kit barcode（bundled，无需 Play Services）。
    // 依赖随 AAR 合并进宿主 Debug 包；外网包不引入 SDK ⇒ 天然零携带。
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    // 扫码界面基类 ComponentActivity（CameraX bindToLifecycle 需要 LifecycleOwner）。
    implementation(libs.activity)
    // 文档开始注入与 WebView 双向消息。1.14 保留 minSdk 21。
    implementation(libs.webkit)
    testImplementation(libs.junit)
    // MockServer 桩（注册/心跳/上传/规则拉取契约验证）。
    testImplementation(libs.mockwebserver)
    // Dispatchers.Main 在 JVM 单测可用（回调主线程派发验证）。
    testImplementation(libs.coroutines.test)
    // JVM 单测提供真实 org.json 实现（Android 运行时由 framework 提供，不进 AAR）。
    testImplementation(libs.orgjson)
}

// Dokimo ships Kotlin 1.8. Keep the SDK's generated calls compatible with that runtime.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
    compilerOptions.freeCompilerArgs.add("-Xmetadata-version=1.9.0")
}
