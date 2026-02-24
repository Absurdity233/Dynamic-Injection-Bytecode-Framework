package com.heypixel.heypixelmod.cc.mizore.client.transform.impl.jvm;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.ITransformer;
import com.heypixel.heypixelmod.cc.mizore.client.utils.asm.AsmUtils;
import com.heypixel.heypixelmod.cc.mizore.client.utils.bypass.InjectionLogger;
import com.heypixel.heypixelmod.cc.mizore.client.utils.bypass.JmapExecBypass;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;

public final class JmapDynamicTransformer implements ITransformer, Opcodes {

    private static final String SELF_INTERNAL = JmapDynamicTransformer.class.getName().replace('.', '/');
    private static final String BYPASS_INTERNAL = JmapExecBypass.class.getName().replace('.', '/');
    private static final String HEYPIXEL_PREFIX = "com/heypixel/";
    private static final String MIZORE_PREFIX = "com/heypixel/heypixelmod/cc/mizore";

    private final Set<String> knownTargets;

    private static volatile int applyCount = 0;

    public static int getApplyCount() { return applyCount; }
    public static void resetApplyCount() { applyCount = 0; }
    // old 把 old  old 把old
    public JmapDynamicTransformer() {
        this.knownTargets = Collections.emptySet();
    }

    public JmapDynamicTransformer(Set<String> knownTargets) {
        this.knownTargets = knownTargets != null ? Set.copyOf(knownTargets) : Collections.emptySet();
    }

    @Override
    public String getTargetClass() { return null; }

    @Override
    public Set<String> getTargetClasses() { return knownTargets; }

    @Override
    public boolean mightApply(byte[] classBytes) {
        return ITransformer.containsUtf8(classBytes, "java/lang/Runtime")
            || ITransformer.containsUtf8(classBytes, "java/lang/ProcessBuilder");
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

        ClassNode cn = AsmUtils.toClassNode(classBuffer);
        if (!isTarget(cn.name)) return null;
        if (SELF_INTERNAL.equals(cn.name) || BYPASS_INTERNAL.equals(cn.name)) return null;

        boolean modified = false;
        List<String> hooked = new ArrayList<>();

        for (MethodNode mn : cn.methods) {
            ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();
                if (!(insn instanceof MethodInsnNode min)) continue;

                if (min.getOpcode() == INVOKEVIRTUAL
                        && "java/lang/Runtime".equals(min.owner)
                        && "exec".equals(min.name)) {

                    if ("(Ljava/lang/String;)Ljava/lang/Process;".equals(min.desc)) {
                        min.setOpcode(INVOKESTATIC);
                        min.owner = BYPASS_INTERNAL;
                        min.name = "hookRuntimeExec";
                        min.desc = "(Ljava/lang/Runtime;Ljava/lang/String;)Ljava/lang/Process;";
                        modified = true;
                        hooked.add(mn.name + "->exec(String)");
                        continue;
                    }
                    if ("([Ljava/lang/String;)Ljava/lang/Process;".equals(min.desc)) {
                        min.setOpcode(INVOKESTATIC);
                        min.owner = BYPASS_INTERNAL;
                        min.name = "hookRuntimeExec";
                        min.desc = "(Ljava/lang/Runtime;[Ljava/lang/String;)Ljava/lang/Process;";
                        modified = true;
                        hooked.add(mn.name + "->exec(String[])");
                        continue;
                    }
                    if ("([Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;".equals(min.desc)) {
                        min.setOpcode(INVOKESTATIC);
                        min.owner = BYPASS_INTERNAL;
                        min.name = "hookRuntimeExec";
                        min.desc = "(Ljava/lang/Runtime;[Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;";
                        modified = true;
                        hooked.add(mn.name + "->exec(String[],String[])");
                        continue;
                    }
                    if ("([Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;".equals(min.desc)) {
                        min.setOpcode(INVOKESTATIC);
                        min.owner = BYPASS_INTERNAL;
                        min.name = "hookRuntimeExecWithDir";
                        min.desc = "(Ljava/lang/Runtime;[Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;";
                        modified = true;
                        hooked.add(mn.name + "->exec(String[],String[],File)");
                        continue;
                    }
                }

                if (min.getOpcode() == INVOKEVIRTUAL
                        && "java/lang/ProcessBuilder".equals(min.owner)
                        && "start".equals(min.name)
                        && "()Ljava/lang/Process;".equals(min.desc)) {
                    min.setOpcode(INVOKESTATIC);
                    min.owner = BYPASS_INTERNAL;
                    min.name = "hookProcessBuilderStart";
                    min.desc = "(Ljava/lang/ProcessBuilder;)Ljava/lang/Process;";
                    modified = true;
                    hooked.add(mn.name + "->ProcessBuilder.start()");
                }
            }
        }

        if (!modified) return null;
        applyCount++;
        InjectionLogger.log("JmapBypass", "hook applied: " + cn.name + " | " + String.join(", ", hooked));
        return AsmUtils.toBytes(cn, loader);
    }
}
