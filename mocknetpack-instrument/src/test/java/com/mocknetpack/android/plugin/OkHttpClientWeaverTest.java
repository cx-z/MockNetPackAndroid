package com.mocknetpack.android.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 织入逻辑单测：用 ASM 现场生成一个假的 {@code okhttp3/OkHttpClient}，跑一遍
 * {@link MockNetPackInstrumentPlugin.OkHttpClientClassVisitor}，再读回字节码断言：
 * - {@code <init>(Builder)} 里有且只有一处 INVOKESTATIC MockNetPack.install；
 * - 其它构造 / 方法不受影响。
 */
public class OkHttpClientWeaverTest {

    private static final String OWNER = "okhttp3/OkHttpClient";
    private static final String BUILDER = "Lokhttp3/OkHttpClient$Builder;";

    /** 生成假 OkHttpClient：(Builder) 构造、() 构造、一个普通方法。 */
    private static byte[] fakeClient() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                OWNER,
                null,
                "java/lang/Object",
                null);
        cw.visitField(Opcodes.ACC_PRIVATE, "tag", "Ljava/lang/String;", null, null).visitEnd();

        MethodVisitor ctor =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + BUILDER + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor noArg = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        noArg.visitCode();
        noArg.visitVarInsn(Opcodes.ALOAD, 0);
        noArg.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        noArg.visitInsn(Opcodes.RETURN);
        noArg.visitMaxs(0, 0);
        noArg.visitEnd();

        MethodVisitor ping = cw.visitMethod(Opcodes.ACC_PUBLIC, "ping", "()V", null, null);
        ping.visitCode();
        ping.visitInsn(Opcodes.RETURN);
        ping.visitMaxs(0, 0);
        ping.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] instrumented;

    @Before
    public void weave() {
        ClassReader reader = new ClassReader(fakeClient());
        // 与 AGP 一致：ClassWriter 作为链尾，visitor 只是中间层。
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        reader.accept(
                new MockNetPackInstrumentPlugin.OkHttpClientClassVisitor(Opcodes.ASM9, writer),
                ClassReader.EXPAND_FRAMES);
        instrumented = writer.toByteArray();
        assertTrue("产出的字节码不应为空", instrumented.length > 0);
    }

    @Test
    public void builderConstructorCallsInstall() {
        Counter counter = new Counter();
        new ClassReader(instrumented).accept(counter, 0);
        assertEquals("只应织入 (Builder) 构造", 1, counter.installInBuilderCtor);
        assertEquals("不应改动无参构造", 0, counter.installInOtherMethods);
    }

    @Test
    public void installCallShapeMatchesSdkSignature() {
        Counter counter = new Counter();
        new ClassReader(instrumented).accept(counter, 0);
        assertEquals("com/mocknetpack/android/MockNetPack", counter.owner);
        assertEquals("install", counter.name);
        assertEquals("(Lokhttp3/OkHttpClient$Builder;)V", counter.desc);
    }

    /** 统计 INVOKESTATIC MockNetPack.install 出现位置。 */
    private static final class Counter extends ClassVisitor {
        int installInBuilderCtor;
        int installInOtherMethods;
        String owner;
        String name;
        String desc;
        private String currentMethod;

        Counter() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String sig, String[] exceptions) {
            currentMethod = name + descriptor;
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(
                        int opcode, String o, String n, String d, boolean itf) {
                    if (opcode == Opcodes.INVOKESTATIC
                            && "com/mocknetpack/android/MockNetPack".equals(o)
                            && "install".equals(n)) {
                        owner = o;
                        Counter.this.name = n;
                        desc = d;
                        if (("<init>(" + BUILDER + ")V").equals(currentMethod)) {
                            installInBuilderCtor++;
                        } else {
                            installInOtherMethods++;
                        }
                    }
                }
            };
        }
    }
}
