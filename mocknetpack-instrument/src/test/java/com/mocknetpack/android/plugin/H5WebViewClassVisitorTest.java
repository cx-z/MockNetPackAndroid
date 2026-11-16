package com.mocknetpack.android.plugin;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class H5WebViewClassVisitorTest {
    private static final String WEB_VIEW = "android/webkit/WebView";
    private static final String OTHER = "another/ChromeClientContainer";

    @Test
    public void installsAfterTheOriginalSetterOnlyInTheConfiguredSetupMethod() {
        assertEquals(List.of(
                "initWebView: " + WEB_VIEW + ".setWebChromeClient(Landroid/webkit/WebChromeClient;)V",
                "initWebView: " + OTHER + ".setWebChromeClient(Landroid/webkit/WebChromeClient;)V",
                "initWebView: com/mocknetpack/android/MockNetPack.installH5Capture(Landroid/webkit/WebView;Z)V",
                "anotherWebView: " + WEB_VIEW + ".setWebChromeClient(Landroid/webkit/WebChromeClient;)V"
        ), collectCalls(WEB_VIEW, OTHER));
    }

    @Test
    public void skipsUnrelatedOrAmbiguousWebViewSetters() {
        assertEquals(0, collectCalls(OTHER).stream().filter(call -> call.contains("installH5Capture")).count());
        assertEquals(0, collectCalls(WEB_VIEW, WEB_VIEW).stream()
                .filter(call -> call.contains("installH5Capture")).count());
    }

    private static List<String> collectCalls(String... setterOwners) {
        ClassWriter input = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        input.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Browser", null, "java/lang/Object", null);
        writeMethodWithChromeClient(input, "initWebView", setterOwners);
        writeMethodWithChromeClient(input, "anotherWebView", WEB_VIEW);
        input.visitEnd();

        ClassReader reader = new ClassReader(input.toByteArray());
        ClassWriter output = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        reader.accept(new H5WebViewClassVisitor(Opcodes.ASM9, output, "Browser", "initWebView",
                WEB_VIEW::equals),
                ClassReader.EXPAND_FRAMES);

        List<String> calls = new ArrayList<>();
        new ClassReader(output.toByteArray()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String method, String desc, String sig, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                            int opcode, String owner, String name, String descriptor, boolean itf) {
                        calls.add(method + ": " + owner + "." + name + descriptor);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);

        return calls;
    }

    private static void writeMethodWithChromeClient(ClassWriter output, String name, String... owners) {
        MethodVisitor method = output.visitMethod(Opcodes.ACC_PRIVATE, name, "()V", null, null);
        method.visitCode();
        for (String owner : owners) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "setWebChromeClient",
                    "(Landroid/webkit/WebChromeClient;)V", false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}
