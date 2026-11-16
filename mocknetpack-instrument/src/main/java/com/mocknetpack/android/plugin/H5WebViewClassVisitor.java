package com.mocknetpack.android.plugin;

import java.util.function.Predicate;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** 在 WebView 装配结束时安装 H5 采集，此时原 Client 和 JSBridge 已就绪。 */
final class H5WebViewClassVisitor extends ClassVisitor {

    private static final Type WEB_VIEW = Type.getObjectType("android/webkit/WebView");
    private static final Logger LOGGER = Logging.getLogger(H5WebViewClassVisitor.class);
    private final String setupClass;
    private final String setupMethod;
    private final Predicate<String> isWebView;
    private boolean foundSetupMethod;

    H5WebViewClassVisitor(int api, ClassVisitor next, String setupClass, String setupMethod,
            Predicate<String> isWebView) {
        super(Math.min(api, Opcodes.ASM9), next);
        this.setupClass = setupClass;
        this.setupMethod = setupMethod;
        this.isWebView = isWebView;
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (next == null || !setupMethod.equals(name) || !"()V".equals(descriptor)) {
            return next;
        }
        foundSetupMethod = true;
        // 先核对整个装配方法，避免多个 WebView setter 时猜测目标实例。
        return new MethodNode(api, access, name, descriptor, signature, exceptions) {
            @Override
            public void visitEnd() {
                int count = 0;
                String owner = null;
                for (var instruction : instructions) {
                    if (instruction instanceof MethodInsnNode call
                            && isChromeSetter(call.getOpcode(), call.name, call.desc)
                            && isWebView.test(call.owner)) {
                        count++;
                        owner = call.owner;
                    }
                }
                if (count != 1) {
                    LOGGER.warn("[mocknetpack] H5 target {}#{} skipped: expected one WebView Chrome setter, found {}",
                            setupClass, setupMethod, count);
                    accept(next);
                    return;
                }
                accept(new InstallAtSetupReturn(api, next, access, name, descriptor, owner));
                LOGGER.info("[mocknetpack] H5 hook woven at {}#{}", setupClass, setupMethod);
            }
        };
    }

    @Override
    public void visitEnd() {
        if (!foundSetupMethod) {
            LOGGER.warn("[mocknetpack] H5 target {}#{} skipped: setup method ()V was not found",
                    setupClass, setupMethod);
        }
        super.visitEnd();
    }

    private static boolean isChromeSetter(int opcode, String name, String descriptor) {
        return opcode == Opcodes.INVOKEVIRTUAL && "setWebChromeClient".equals(name)
                && "(Landroid/webkit/WebChromeClient;)V".equals(descriptor);
    }

    private static final class InstallAtSetupReturn extends AdviceAdapter {

        private int webViewLocal;
        private final String webViewOwner;

        InstallAtSetupReturn(int api, MethodVisitor next, int access, String name, String descriptor,
                String webViewOwner) {
            super(api, next, access, name, descriptor);
            this.webViewOwner = webViewOwner;
        }

        @Override
        protected void onMethodEnter() {
            webViewLocal = newLocal(WEB_VIEW);
            mv.visitInsn(ACONST_NULL);
            storeLocal(webViewLocal);
        }

        @Override
        public void visitMethodInsn(
                int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (!isChromeSetter(opcode, name, descriptor) || !webViewOwner.equals(owner)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }
            // 保留 setter 的 WebView 接收者，照常调用原 setter。
            dup2();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            pop();
            storeLocal(webViewLocal);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != RETURN) {
                return;
            }
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            Label done = new Label();
            visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            loadLocal(webViewLocal);
            ifNull(done);
            mark(start);
            loadLocal(webViewLocal);
            push(true);
            mv.visitMethodInsn(INVOKESTATIC, "com/mocknetpack/android/MockNetPack",
                    "installH5Capture", "(Landroid/webkit/WebView;Z)V", false);
            mark(end);
            goTo(done);
            mark(handler);
            pop();
            mark(done);
        }
    }
}
