plugins {
    // 纯 JVM Gradle 插件模块（不应用 AGP）：宿主工程的 Android 变体 API 在
    // 应用期通过 AGP 提供的 gradle-api 访问，故 compileOnly。
    `java-gradle-plugin`
    `maven-publish`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    // AGP 变体 API（AsmClassVisitorFactory / InstrumentationScope）。宿主 AGP 8.10.1。
    compileOnly("com.android.tools.build:gradle-api:8.10.1")
    // ASM：AGP 运行期自带，插件内 compileOnly；单测需要真实实现。
    compileOnly("org.ow2.asm:asm:9.7")
    compileOnly("org.ow2.asm:asm-commons:9.7")
    testImplementation("org.ow2.asm:asm:9.7")
    testImplementation("org.ow2.asm:asm-commons:9.7")
    testImplementation("junit:junit:4.13.2")
    // 单测里用 ProjectBuilder 验证插件行为（fail-open / kill switch）：
    // 需要 AGP API 类出现在测试运行时（生产由宿主 AGP 提供，故 compileOnly）。
    testImplementation("com.android.tools.build:gradle-api:8.10.1")
}

gradlePlugin {
    plugins {
        create("mocknetpackInstrument") {
            id = "com.mocknetpack.android.instrument"
            implementationClass = "com.mocknetpack.android.plugin.MockNetPackInstrumentPlugin"
            displayName = "MockNetPack Android instrumentation plugin"
            description =
                "Debug-only ASM instrumentation: weaves MockNetPack.install(builder) " +
                    "into okhttp3.OkHttpClient(Builder) so every OkHttpClient is captured."
        }
    }
}

// 本地接入走 mavenLocal（M3a-2 才补 JitPack 多模块发布配置，本阶段不发布到远端）。
// java-gradle-plugin 自动创建 pluginMaven 发布 + plugin marker（com.mocknetpack.android.instrument）。
