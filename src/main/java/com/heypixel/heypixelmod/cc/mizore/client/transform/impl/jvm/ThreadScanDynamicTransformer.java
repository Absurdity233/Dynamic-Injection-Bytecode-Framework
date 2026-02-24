package com.heypixel.heypixelmod.cc.mizore.client.transform.impl.jvm;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.ITransformer;
import com.heypixel.heypixelmod.cc.mizore.client.utils.asm.AsmUtils;
import com.heypixel.heypixelmod.cc.mizore.client.utils.bypass.InjectionLogger;
import com.heypixel.heypixelmod.cc.mizore.client.utils.bypass.ThreadScanBypass;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;

public final class ThreadScanDynamicTransformer implements ITransformer, Opcodes {

    private static final String HOOK_INTERNAL = ThreadScanBypass.class.getName().replace('.', '/');
    private static final String HEYPIXEL_PREFIX = "com/heypixel/";
    private static final String MIZORE_PREFIX = "com/heypixel/heypixelmod/cc/mizore";

    private final Set<String> knownTargets;

    private static volatile int applyCount = 0;

    public static int getApplyCount() { return applyCount; }
    public static void resetApplyCount() { applyCount = 0; }

    // old 把 old  old 把old
    public ThreadScanDynamicTransformer() {
        this.knownTargets = Collections.emptySet();
    }

    public ThreadScanDynamicTransformer(Set<String> knownTargets) {
        this.knownTargets = knownTargets != null ? Set.copyOf(knownTargets) : Collections.emptySet();
    }

    @Override
    public String getTargetClass() { return null; }

    @Override
    public Set<String> getTargetClasses() { return knownTargets; }

    @Override
    public boolean mightApply(byte[] classBytes) {
        return ITransformer.containsUtf8(classBytes, "ManagementFactory")
            || ITransformer.containsUtf8(classBytes, "ThreadMXBean")
            || ITransformer.containsUtf8(classBytes, "getStackTrace")
            || ITransformer.containsUtf8(classBytes, "getAllStackTraces")
            || ITransformer.containsUtf8(classBytes, "StackWalker");
    }

    private static boolean isTarget(String name) {
        if (name == null) return false;
        if (!name.startsWith(HEYPIXEL_PREFIX)) return false;
        if (name.startsWith(MIZORE_PREFIX)) return false;
        return true;
    }

    @Override
    public byte[] transform(byte[] classBuffer, ClassLoader loader) {
        if (classBuffer == null) return null;

        try {
            String className = new ClassReader(classBuffer).getClassName();
            if (!isTarget(className)) return null;

            ClassNode cn = AsmUtils.toClassNode(classBuffer);
            if (HOOK_INTERNAL.equals(cn.name)) return null;

            boolean modified = false;
            List<String> hooked = new ArrayList<>();

            for (MethodNode mn : cn.methods) {
                ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
                while (it.hasNext()) {
                    AbstractInsnNode insn = it.next();
                    if (!(insn instanceof MethodInsnNode min)) continue;

                    // ManagementFactory.getThreadMXBean() -> proxy
                    if (min.getOpcode() == INVOKESTATIC
                            && "java/lang/management/ManagementFactory".equals(min.owner)
                            && "getThreadMXBean".equals(min.name)
                            && "()Ljava/lang/management/ThreadMXBean;".equals(min.desc)) {
                        min.owner = HOOK_INTERNAL;
                        min.name = "hookGetThreadMXBean";
                        modified = true;
                        hooked.add(mn.name + "->getThreadMXBean");
                        continue;
                    }

                    // ThreadMXBean.dumpAllThreads(Z,Z) (INVOKEINTERFACE)
                    if ((min.getOpcode() == INVOKEINTERFACE || min.getOpcode() == INVOKEVIRTUAL)
                            && "java/lang/management/ThreadMXBean".equals(min.owner)
                            && "dumpAllThreads".equals(min.name)
                            && "(ZZ)[Ljava/lang/management/ThreadInfo;".equals(min.desc)) {
                        min.setOpcode(INVOKESTATIC);
                        min.owner = HOOK_INTERNAL;
                        min.name = "hookDumpAllThreads";
                        min.desc = "(Ljava/lang/management/ThreadMXBean;ZZ)[Ljava/lang/management/ThreadInfo;";
                        min.itf = false;
                        modified = true;
                        hooked.add(mn.name + "->dumpAllThreads");
                        continue;
                    }

                    // ThreadMXBean.getThreadInfo (INVOKEINTERFACE)
                    if ((min.getOpcode() == INVOKEINTERFACE || min.getOpcode() == INVOKEVIRTUAL)
                            && "java/lang/management/ThreadMXBean".equals(min.owner)
                            && "getThreadInfo".equals(min.name)) {
                        String hooked0 = tryHookGetThreadInfo(min, mn.name,
                                "(J)Ljava/lang/management/ThreadInfo;",
                                "(Ljava/lang/management/ThreadMXBean;J)Ljava/lang/management/ThreadInfo;",
                                "J", "hookGetThreadInfo");
                        if (hooked0 != null) { modified = true; hooked.add(hooked0); continue; }

                        hooked0 = tryHookGetThreadInfo(min, mn.name,
                                "([J)[Ljava/lang/management/ThreadInfo;",
                                "(Ljava/lang/management/ThreadMXBean;[J)[Ljava/lang/management/ThreadInfo;",
                                "[J", "hookGetThreadInfo");
                        if (hooked0 != null) { modified = true; hooked.add(hooked0); continue; }

                        hooked0 = tryHookGetThreadInfo(min, mn.name,
                                "(JI)Ljava/lang/management/ThreadInfo;",
                                "(Ljava/lang/management/ThreadMXBean;JI)Ljava/lang/management/ThreadInfo;",
                                "JI", "hookGetThreadInfo");
                        if (hooked0 != null) { modified = true; hooked.add(hooked0); continue; }
                    }

                    // Thread.getStackTrace()
                    if (min.getOpcode() == INVOKEVIRTUAL && "java/lang/Thread".equals(min.owner)
                            && "getStackTrace".equals(min.name)
                            && "()[Ljava/lang/StackTraceElement;".equals(min.desc)) {
                        min.setOpcode(INVOKESTATIC); min.owner = HOOK_INTERNAL;
                        min.name = "hookGetStackTrace";
                        min.desc = "(Ljava/lang/Thread;)[Ljava/lang/StackTraceElement;";
                        modified = true;
                        hooked.add(mn.name + "->Thread.getStackTrace"); continue;
                    }

                    // Thread.getAllStackTraces()
                    if (min.getOpcode() == INVOKESTATIC && "java/lang/Thread".equals(min.owner)
                            && "getAllStackTraces".equals(min.name) && "()Ljava/util/Map;".equals(min.desc)) {
                        min.owner = HOOK_INTERNAL;
                        min.name = "hookGetAllStackTraces";
                        modified = true;
                        hooked.add(mn.name + "->Thread.getAllStackTraces"); continue;
                    }

                    // StackWalker.walk(Function)
                    if (min.getOpcode() == INVOKEVIRTUAL && "java/lang/StackWalker".equals(min.owner)
                            && "walk".equals(min.name)
                            && "(Ljava/util/function/Function;)Ljava/lang/Object;".equals(min.desc)) {
                        min.setOpcode(INVOKESTATIC); min.owner = HOOK_INTERNAL;
                        min.name = "hookStackWalkerWalk";
                        min.desc = "(Ljava/lang/StackWalker;Ljava/util/function/Function;)Ljava/lang/Object;";
                        modified = true;
                        hooked.add(mn.name + "->StackWalker.walk"); continue;
                    }

                    // StackWalker.forEach(Consumer)
                    if (min.getOpcode() == INVOKEVIRTUAL && "java/lang/StackWalker".equals(min.owner)
                            && "forEach".equals(min.name)
                            && "(Ljava/util/function/Consumer;)V".equals(min.desc)) {
                        min.setOpcode(INVOKESTATIC); min.owner = HOOK_INTERNAL;
                        min.name = "hookStackWalkerForEach";
                        min.desc = "(Ljava/lang/StackWalker;Ljava/util/function/Consumer;)V";
                        modified = true;
                        hooked.add(mn.name + "->StackWalker.forEach");
                    }
                }
            }

            if (!modified) return null;
            applyCount++;
            InjectionLogger.log("ThreadScanBypass", "hook applied: " + cn.name + " | " + String.join(", ", hooked));
            return AsmUtils.toBytes(cn, loader);

        } catch (Throwable t) {
            InjectionLogger.log("ThreadScanBypass", "transform FAILED for class bytes (len="
                    + classBuffer.length + "): " + t);
            return null;
        }
    }

    private String tryHookGetThreadInfo(MethodInsnNode min, String methodName,
                                        String matchDesc, String newDesc, String paramTag, String hookName) {
        if (!matchDesc.equals(min.desc)) return null;
        min.setOpcode(INVOKESTATIC);
        min.owner = HOOK_INTERNAL;
        min.name = hookName;
        min.desc = newDesc;
        min.itf = false;
        return methodName + "->getThreadInfo(" + paramTag + ")";
    }
}
