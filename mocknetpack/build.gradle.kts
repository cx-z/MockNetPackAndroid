plugins {
    alias(libs.plugins.android.library)
    // AN-5 接入方式：SDK 发布到本地 Maven（mavenLocal），宿主 Debug 变体
    // debugImplementation("com.mocknetpack.android:mocknetpack:0.1.0-SNAPSHOT")，
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
        minSdk = 24
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

// mavenLocal 发布坐标：com.mocknetpack.android:mocknetpack:0.1.0-SNAPSHOT
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.mocknetpack.android"
                artifactId = "mocknetpack"
                version = "0.1.0-SNAPSHOT"
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
