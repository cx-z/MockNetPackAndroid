package com.mocknetpack.android.plugin;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.FramesComputationMode;
import com.android.build.api.instrumentation.InstrumentationParameters;
import com.android.build.api.instrumentation.InstrumentationScope;
import com.android.build.api.variant.AndroidComponentsExtension;
import com.android.build.api.variant.Variant;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * MockNetPack 构建期插桩插件（AN-10 M3a-1）。
 *
 * 目的：把 {@code MockNetPack.install(builder)} 织入 {@code okhttp3.OkHttpClient}
 * 的构造入口 {@code <init>(Lokhttp3/OkHttpClient$Builder;)V}，让宿主/三方库里
 * 所有经 Builder 构建的 OkHttpClient 自动携带采集拦截器（业务零改动）。
 *
 * 安全边界：
 * - 默认开，只对 debug 变体生效（release/preview 不织、不携带 SDK）；
 * - 关闭方式：{@code -Pmocknetpack.instrument=false} 或 {@code mocknetpack { enabled false }}；
 * - fail-open：非 Android 工程 / AGP 版本不兼容 / 无变体 API / SDK 不在 classpath
 *   → WARN 跳过，构建继续；插桩异常不阻断构建；
 * - 只改 OkHttpClient 一个构造，{@code new OkHttpClient()} 会委托到该构造，同样被覆盖。
 */
public class MockNetPackInstrumentPlugin implements Plugin<Project> {

    /** kill switch 的 Gradle 属性名。 */
    public static final String PROPERTY = "mocknetpack.instrument";

    @Override
    public void apply(Project project) {
        Logger logger = project.getLogger();
        MockNetPackExtension extension =
                project.getExtensions().create("mocknetpack", MockNetPackExtension.class);

        // kill switch：命令行属性优先（-Pmocknetpack.instrument=false），缺省开。
        boolean propertyEnabled = isPropertyEnabled(project);
        extension.getEnabled().convention(propertyEnabled);
        if (!propertyEnabled) {
            logger.lifecycle("[mocknetpack] instrumentation disabled by -P{}=false, skip", PROPERTY);
            return;
        }

        AndroidComponentsExtension<?, ?, ?> components;
        try {
            components = project.getExtensions().getByType(AndroidComponentsExtension.class);
        } catch (Throwable t) {
            // fail-open：非 Android 工程或 AGP 不兼容，不阻断构建。
            logger.warn(
                    "[mocknetpack] Android Components API unavailable (non-Android project or "
                            + "incompatible AGP), skip instrumentation: {}",
                    t.toString());
            return;
        }

        components.onVariants(components.selector().all(), variant -> {
            try {
                configureVariant(project, extension, variant, logger);
            } catch (Throwable t) {
                // fail-open：单个变体配置失败只跳过该变体。
                logger.warn(
                        "[mocknetpack] variant '{}' skipped (instrumentation error): {}",
                        variant.getName(),
                        t.toString());
            }
        });
    }

    static boolean isPropertyEnabled(Project project) {
        Object raw = project.findProperty(PROPERTY);
        return raw == null || !"false".equalsIgnoreCase(String.valueOf(raw).trim());
    }

    private static void configureVariant(
            Project project,
            MockNetPackExtension extension,
            Variant variant,
            Logger logger) {
        if (!Boolean.TRUE.equals(extension.getEnabled().getOrNull())) {
            logger.lifecycle(
                    "[mocknetpack] variant '{}' skipped: mocknetpack.enabled=false", variant.getName());
            return;
        }
        String name = variant.getName().toLowerCase();
        boolean debugLike = variant.getDebuggable() && !name.contains("preview");
        if (!debugLike) {
            // 决策：插件默认开、只对 debug 生效；release/preview 不织、不带 SDK。
            logger.info(
                    "[mocknetpack] variant '{}' skipped: debug-only instrumentation",
                    variant.getName());
            return;
        }

        long startNs = System.nanoTime();
        variant.transformClassesWith(
                MockNetPackClassVisitorFactory.class,
                InstrumentationScope.ALL,
                params -> kotlin.Unit.INSTANCE);
        variant.setAsmFramesComputationMode(
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS);
        logger.lifecycle(
                "[mocknetpack] instrumentation enabled for variant '{}' "
                        + "(weave=okhttp3.OkHttpClient.<init>(Builder), setup={}ms)",
                variant.getName(),
                (System.nanoTime() - startNs) / 1_000_000L);
    }

    /**
     * 类访问器工厂：只处理 {@code okhttp3.OkHttpClient}，且要求 SDK 类在 classpath 上
     * （类缺失 → fail-open 跳过，不织出 NoClassDefFoundError）。
     */
    public abstract static class MockNetPackClassVisitorFactory
            implements AsmClassVisitorFactory<InstrumentationParameters.None> {

        static final String OKHTTP_CLIENT = "okhttp3.OkHttpClient";
        static final String BUILDER = "okhttp3.OkHttpClient$Builder";
        static final String CTOR_DESC = "(Lokhttp3/OkHttpClient$Builder;)V";
        static final String SDK_INTERNAL = "com/mocknetpack/android/MockNetPack";

        @Override
        public boolean isInstrumentable(ClassData classData) {
            return OKHTTP_CLIENT.equals(classData.getClassName());
        }

        @Override
        public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor next) {
            int api = getInstrumentationContext().getApiVersion().getOrElse(Opcodes.ASM9);
            return new OkHttpClientClassVisitor(api, next);
        }
    }

    /** 在 super() 之后插入 try { MockNetPack.install(builder) } catch (Throwable) {}。 */
    static final class OkHttpClientClassVisitor extends ClassVisitor {

        private final int apiVersion;

        OkHttpClientClassVisitor(int apiVersion, ClassVisitor next) {
            super(Math.min(apiVersion, Opcodes.ASM9), next);
            this.apiVersion = Math.min(apiVersion, Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) {
                return null;
            }
            if (!"<init>".equals(name)
                    || !MockNetPackClassVisitorFactory.CTOR_DESC.equals(descriptor)) {
                return mv;
            }
            return new InstallInjectingVisitor(apiVersion, mv);
        }
    }

    /** 在对象 super() 调用之后插入 install 调用（此时 this 已初始化，try/catch 合法）。 */
    private static final class InstallInjectingVisitor extends MethodVisitor {

        private boolean injected;

        InstallInjectingVisitor(int apiVersion, MethodVisitor next) {
            super(apiVersion, next);
        }

        @Override
        public void visitMethodInsn(
                int opcode, String owner, String name, String descriptor, boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (injected
                    || opcode != Opcodes.INVOKESPECIAL
                    || !"java/lang/Object".equals(owner)
                    || !"<init>".equals(name)) {
                return;
            }
            injected = true;
            // fail-open：SDK 类缺失（NoClassDefFoundError）或 install 抛异常都被吞掉，
            // 业务照常构造 OkHttpClient，只是没有采集能力。
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            Label done = new Label();
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(start);
            super.visitVarInsn(Opcodes.ALOAD, 1); // builder（this=0）
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    MockNetPackClassVisitorFactory.SDK_INTERNAL,
                    "install",
                    MockNetPackClassVisitorFactory.CTOR_DESC,
                    false);
            super.visitLabel(end);
            super.visitJumpInsn(Opcodes.GOTO, done);
            super.visitLabel(handler);
            super.visitInsn(Opcodes.POP);
            super.visitLabel(done);
        }
    }
}
