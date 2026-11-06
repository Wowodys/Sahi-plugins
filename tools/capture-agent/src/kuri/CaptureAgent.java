package kuri;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * -javaagent that instruments:
 *   1. okhttp3.RealCall.getResponseWithInterceptorChain()Lokhttp3/Response; -
 *      every okhttp response to *.kuriplugins.com is logged as a JSON line
 *      (includes the load plan with contentKeys).
 *   2. any class whose bytes contain "KURIKENC" (Kuri's KencFile parser):
 *      static methods shaped (InputStream, X, Y) -> byte[] get their
 *      identity/keyMaterial arguments and decrypted plaintext dumped.
 *
 * Instrumented classes live in the child URLClassLoader; Helper is on the
 * parent system classpath (the -javaagent jar is appended automatically), so
 * injected INVOKESTATIC calls resolve via parent delegation. Fully reflective,
 * no compile-time deps on okhttp/k.
 */
public final class CaptureAgent implements Opcodes {

    static boolean installed;

    public static void premain(String args, Instrumentation inst) {
        // guard against double -javaagent injection (config.json vmArgs +
        // settings jvmArguments can both apply in the client fork)
        if (installed) {
            return;
        }
        installed = true;
        // Helper must be resolvable from EVERY loader: packr relaunches with a
        // custom --classpath loader that does not see the agent jar on the
        // system classpath, so append it to the bootstrap search instead.
        appendSelfToBootstrap(inst);
        inst.addTransformer(new T(), false);
        try {
            Helper.record("agent-init", "javaVersion", System.getProperty("java.version"),
                "proc", Helper.proc());
        } catch (Throwable ignore) {
        }
    }

    private static void appendSelfToBootstrap(Instrumentation inst) {
        // Only kuri/Helper goes on the bootstrap: the full agent jar there
        // would shadow CaptureAgent$T onto the bootstrap loader and split
        // class identity with the app loader (IllegalAccessError).
        try {
            java.security.CodeSource cs = CaptureAgent.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                java.io.File dir = new java.io.File(cs.getLocation().toURI()).getParentFile();
                java.io.File f = new java.io.File(dir, "capture-helper.jar");
                if (f.isFile()) {
                    inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(f));
                    return;
                }
                System.err.println("[kuri-capture] missing " + f);
            }
            System.err.println("[kuri-capture] could not locate agent jar for bootstrap append");
        } catch (Throwable t) {
            System.err.println("[kuri-capture] bootstrap append failed: " + t);
        }
    }

    static final class T implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> beingDefined,
                                ProtectionDomain pd, byte[] bytes) {
            try {
                if (bytes == null || bytes.length < 16) {
                    return null;
                }
                if ("okhttp3/RealCall".equals(className)) {
                    return transformRealCall(bytes);
                }
                if (className.startsWith("k/") && contains(bytes, "KURIKENC")) {
                    return transformKencFile(bytes);
                }
                return null;
            } catch (Throwable t) {
                Helper.error("transform:" + className, t);
                return null; // load untransformed rather than break the app
            }
        }
    }

    static boolean contains(byte[] b, String s) {
        byte[] p = s.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + p.length <= b.length; i++) {
            for (int j = 0; j < p.length; j++) {
                if (b[i + j] != p[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // okhttp3.RealCall
    // ------------------------------------------------------------------
    private static final String RRC = "getResponseWithInterceptorChain";
    private static final String RRC_DESC = "()Lokhttp3/Response;";

    static byte[] transformRealCall(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (RRC.equals(name) && RRC_DESC.equals(descriptor)) {
                    // wrapper first (keeps the original name), ...
                    MethodVisitor w = super.visitMethod(access, name, descriptor,
                        signature, exceptions);
                    emitRealCallWrapper(w);
                    // ... then the original body under the renamed method.
                    MethodVisitor o = super.visitMethod(access | ACC_SYNTHETIC,
                        RRC + "$capOrig", descriptor, signature, exceptions);
                    return o == null ? null : new MethodVisitor(ASM9, o) {
                    };
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        return cw.toByteArray();
    }

    /**
     *   r = this.$capOrig();
     *   try { Helper.onOkhttpResponse(r); } catch (Throwable) {}
     *   return r;
     * locals: 0=this 1=response 2=handler-ex
     */
    /**
     * Straight-line wrapper (Helper catches everything internally, so no
     * try/catch and therefore no StackMapTable is needed):
     *   r = this.$capOrig(); Helper.onOkhttpResponse(r); return r;
     */
    static void emitRealCallWrapper(MethodVisitor mv) {
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "okhttp3/RealCall", RRC + "$capOrig",
            RRC_DESC, false);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, "kuri/Helper", "onOkhttpResponse",
            "(Ljava/lang/Object;)V", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ------------------------------------------------------------------
    // KencFile
    // ------------------------------------------------------------------
    static byte[] transformKencFile(byte[] bytes) {
        final String cn = new ClassReader(bytes).getClassName(); // e.g. k/n52d6dca4ff92
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                Type t = Type.getType(descriptor);
                boolean candidate = (access & (ACC_STATIC | ACC_ABSTRACT | ACC_NATIVE)) == ACC_STATIC
                    && "[B".equals(t.getReturnType().getDescriptor())
                    && t.getArgumentTypes().length == 3
                    && "Ljava/io/InputStream;".equals(t.getArgumentTypes()[0].getDescriptor());
                if (!candidate) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }
                String renamed = name + "$cap";
                // wrapper (keeps original name), then renamed original body
                MethodVisitor w = super.visitMethod(access, name, descriptor,
                    signature, exceptions);
                emitKencWrapper(cn, w, descriptor, renamed);
                MethodVisitor o = super.visitMethod(access | ACC_SYNTHETIC, renamed,
                    descriptor, signature, exceptions);
                return o == null ? null : new MethodVisitor(ASM9, o) {
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    /**
     * static (InputStream a0, X a1, Y a2) -> byte[]:
     *   try { Helper.onKencArgs([a0,a1,a2]); } catch {}
     *   r = $cap(a0,a1,a2);
     *   try { Helper.onKencResult(a1, r); } catch {}
     *   return r;
     * locals: 0..2 args, 3=ex1, 4=result, 5=ex2
     */
    /**
     * static (InputStream a0, X a1, Y a2) -> byte[]:
     *   Helper.onKencArgs([a0,a1,a2]);
     *   r = $cap(a0,a1,a2);
     *   Helper.onKencResult(a1, r);
     *   return r;
     * Straight-line: Helper never throws, so no frames are required.
     */
    static void emitKencWrapper(String cn, MethodVisitor mv, String descriptor, String renamed) {
        mv.visitCode();
        mv.visitInsn(ICONST_3);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(AASTORE);
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(AASTORE);
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_2);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(AASTORE);
        mv.visitMethodInsn(INVOKESTATIC, "kuri/Helper", "onKencArgs",
            "([Ljava/lang/Object;)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, cn, renamed, descriptor, false);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, "kuri/Helper", "onKencResult",
            "(Ljava/lang/Object;[B)V", false);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
