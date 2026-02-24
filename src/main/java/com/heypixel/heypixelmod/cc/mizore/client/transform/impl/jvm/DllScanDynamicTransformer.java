package com.heypixel.heypixelmod.cc.mizore.client.transform.impl.jvm;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.ITransformer;
import com.heypixel.heypixelmod.cc.mizore.client.utils.asm.AsmUtils;
import com.heypixel.heypixelmod.cc.mizore.client.utils.bypass.InjectionLogger;
import com.heypixel.heypixelmod.cc.mizore.client.utils.bypass.DllScanBypass;
import org.checkerframework.checker.units.qual.N;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;

public final class DllScanDynamicTransformer implements ITransformer, Opcodes {

    private static final String BYPASS_INTERNAL = DllScanBypass.class.getName().replace('.', '/');
    private static final String HEYPIXEL_PREFIX = "com/heypixel/";
    private static final String MIZORE_PREFIX = "com/heypixel/heypixelmod/cc/mizore";

    private static final String[] DLL_SCAN_SIGNATURES = {
            "EnumProcessModules",
            "EnumProcessModulesEx",
            "GetModuleFileNameExW",
            "GetModuleFileNameExA",
            "GetModuleFileNameW",
            "GetModuleBaseNameW",
            "GetModuleBaseNameA",
    };

    private final Set<String> knownTargets;

    private static volatile int applyCount = 0;

    public static int getApplyCount() { return applyCount; }
    public static void resetApplyCount() { applyCount = 0; }

    // old 把 old  old 把old
    public DllScanDynamicTransformer() {
        this.knownTargets = Collections.emptySet();
    }

    public DllScanDynamicTransformer(Set<String> knownTargets) {
        this.knownTargets = knownTargets != null ? Set.copyOf(knownTargets) : Collections.emptySet();
    }

    @Override
    public String getTargetClass() { return null; }

    @Override
    public Set<String> getTargetClasses() { return knownTargets; }

    @Override
    public boolean mightApply(byte[] classBytes) {
        return ITransformer.containsUtf8(classBytes, "java/io/File");
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
        if (BYPASS_INTERNAL.equals(cn.name)) return null;

        boolean modified = false;
        List<String> hooked = new ArrayList<>();

        for (MethodNode mn : cn.methods) {

            if (!hasDllScanSignature(mn)) continue;

            ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();
                if (!(insn instanceof MethodInsnNode min)) continue;

                if (min.getOpcode() == INVOKEVIRTUAL
                        && "java/io/File".equals(min.owner)
                        && "exists".equals(min.name)
                        && "()Z".equals(min.desc)) {
                    min.setOpcode(INVOKESTATIC);
                    min.owner = BYPASS_INTERNAL;
                    min.name = "hookFileExists";
                    min.desc = "(Ljava/io/File;)Z";
                    modified = true;
                    hooked.add(mn.name + "->File.exists()");
                    continue;
                }

                if (min.getOpcode() == INVOKEVIRTUAL
                        && "java/io/File".equals(min.owner)
                        && "isFile".equals(min.name)
                        && "()Z".equals(min.desc)) {
                    min.setOpcode(INVOKESTATIC);
                    min.owner = BYPASS_INTERNAL;
                    min.name = "hookFileIsFile";
                    min.desc = "(Ljava/io/File;)Z";
                    modified = true;
                    hooked.add(mn.name + "->File.isFile()");
                }
            }
        }

        if (!modified) return null;
        applyCount++;
        InjectionLogger.log("DllScanBypass", "hook applied: " + cn.name + " | " + String.join(", ", hooked));
        return AsmUtils.toBytes(cn, loader);
    }

    private static boolean hasDllScanSignature(MethodNode mn) {
        for (AbstractInsnNode insn : mn.instructions) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            int op = min.getOpcode();
            if (op != INVOKEINTERFACE && op != INVOKEVIRTUAL) continue;

            for (String sig : DLL_SCAN_SIGNATURES) {
                if (sig.equals(min.name)) return true;
            }
        }
        return false;
    }
}
