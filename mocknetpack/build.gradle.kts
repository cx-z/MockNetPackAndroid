plugins {
    alias(libs.plugins.android.library)
    // 发布方式：SDK 发布到 GitHub（cx-z/MockNetPackAndroid），接入方经
    // JitPack 按版本号集成：debugImplementation("com.github.cx-z:MockNetPackAndroid:1.0.1")。
    // POM 携带完整传递依赖（okhttp api / CameraX / ML Kit / activity / coroutines）。
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

// MavenPublication：groupId/version 用 project.group/project.version（gradle.properties 默认
// com.mocknetpack.android:1.0.0；JitPack 构建时 -Pgroup=com.github.cx-z 覆盖 → 接入方坐标
// com.github.cx-z:MockNetPackAndroid:1.0.0）。artifactId 与仓库名一致（JitPack 单模块格式）。
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
    // 连接层/采集层内部调度使用协程（对宿主不可见）。
    implementation(libs.coroutines.android)
    // AN-4 扫码：CameraX（PreviewView/ImageAnalysis）+ ML Kit barcode（bundled，无需 Play Services）。
    // 依赖随 AAR 合并进宿主 Debug 包；外网包不引入 SDK ⇒ 天然零携带（§8.3，D6）。
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    // 扫码界面基类 ComponentActivity（CameraX bindToLifecycle 需要 LifecycleOwner）。
    implementation(libs.activity)
    testImplementation(libs.junit)
    // MockServer 桩（注册/心跳/上传/规则拉取契约验证）。
    testImplementation(libs.mockwebserver)
    // Dispatchers.Main 在 JVM 单测可用（回调主线程派发验证）。
    testImplementation(libs.coroutines.test)
    // JVM 单测提供真实 org.json 实现（Android 运行时由 framework 提供，不进 AAR）。
    testImplementation(libs.orgjson)
}
