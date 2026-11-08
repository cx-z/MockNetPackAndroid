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

// ---------------------------------------------------------------------------
// AN-10 M3a-2：自包含 jar（archiveClassifier = "all"）。
//
// 宿主只引这一个文件即可 apply 插件，不需要额外往 buildscript classpath 加 ASM：
// 插件类 + ASM（asm / asm-commons）打进同一个 jar。Gradle 的插件 classloader 是
// parent-first，AGP 运行期已带同一版本的 ASM，因此打进 jar 的副本不会与 AGP
// 自己的 ASM 冲突（谁先加载谁生效，两边 ABI 一致）。
// ---------------------------------------------------------------------------
val asmBundle: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    asmBundle("org.ow2.asm:asm:9.7")
    asmBundle("org.ow2.asm:asm-commons:9.7")
}

val selfContainedJar by tasks.registering(Jar::class) {
    group = "build"
    description = "打包插件类 + ASM，产物可单独作为 buildscript classpath 使用。"
    archiveClassifier.set("all")
    from(sourceSets.main.get().output)
    from({ asmBundle.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// 本地接入走 mavenLocal（M3a-2 补 JitPack 多模块发布配置，本阶段只配置、不发布到远端）。
// java-gradle-plugin 自动创建 pluginMaven 发布 + plugin marker（com.mocknetpack.android.instrument）。
