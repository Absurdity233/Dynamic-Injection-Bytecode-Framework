package com.heypixel.heypixelmod.cc.mizore.client.transform.impl.jvm;

import com.heypixel.heypixelmod.cc.mizore.client.transform.ITransformer;
import com.heypixel.heypixelmod.cc.mizore.client.utils.asm.AsmUtils;
import com.heypixel.heypixelmod.cc.mizore.client.utils.bypass.InjectionLogger;
import com.heypixel.heypixelmod.cc.mizore.client.utils.bypass.JarScanBypass;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;

public final class JarScanDynamicTransformer implements ITransformer, Opcodes {

    private static final String BYPASS_INTERNAL = JarScanBypass.class.getName().replace('.', '/');
    private static final String HEYPIXEL_PREFIX = "com/heypixel/";
    private static final String MIZORE_PREFIX = "com/heypixel/heypixelmod/cc/mizore";

    private final Set<String> knownTargets;

    private static volatile int applyCount = 0;

    public static int getApplyCount() { return applyCount; }
    public static void resetApplyCount() { applyCount = 0; }

    // old 把 old  old 把old
    public JarScanDynamicTransformer() {
        this.knownTargets = Collections.emptySet();
    }

    public JarScanDynamicTransformer(Set<String> knownTargets) {
        this.knownTargets = knownTargets != null ? Set.copyOf(knownTargets) : Collections.emptySet();
    }

    @Override
    public String getTargetClass() { return null; }

    @Override
    public Set<String> getTargetClasses() { return knownTargets; }

    @Override
    public boolean mightApply(byte[] classBytes) {
        return ITransformer.containsUtf8(classBytes, "java/util/jar/JarFile")
            || ITransformer.containsUtf8(classBytes, "java/util/zip/ZipFile");
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
            ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();
                if (!(insn instanceof MethodInsnNode min)) continue;

                if (min.getOpcode() == INVOKEVIRTUAL && "entries".equals(min.name)
                        && "()Ljava/util/Enumeration;".equals(min.desc)
                        && ("java/util/zip/ZipFile".equals(min.owner) || "java/util/jar/JarFile".equals(min.owner))) {
                    min.setOpcode(INVOKESTATIC);
                    min.owner = BYPASS_INTERNAL;
                    min.name = "filterEntries";
                    min.desc = "(Ljava/util/zip/ZipFile;)Ljava/util/Enumeration;";
                    modified = true;
                    hooked.add(mn.name + "->entries()");
                    continue;
                }

                if (min.getOpcode() == INVOKEVIRTUAL && "java/util/jar/JarFile".equals(min.owner)
                        && "stream".equals(min.name) && "()Ljava/util/stream/Stream;".equals(min.desc)) {
                    min.setOpcode(INVOKESTATIC);
                    min.owner = BYPASS_INTERNAL;
                    min.name = "filterStream";
                    min.desc = "(Ljava/util/jar/JarFile;)Ljava/util/stream/Stream;";
                    modified = true;
                    hooked.add(mn.name + "->JarFile.stream()");
                    continue;
                }

                if (min.getOpcode() == INVOKEVIRTUAL && "getEntry".equals(min.name)
                        && "(Ljava/lang/String;)Ljava/util/zip/ZipEntry;".equals(min.desc)
                        && ("java/util/zip/ZipFile".equals(min.owner) || "java/util/jar/JarFile".equals(min.owner))) {
                    min.setOpcode(INVOKESTATIC);
                    min.owner = BYPASS_INTERNAL;
                    min.name = "filterGetEntry";
                    min.desc = "(Ljava/util/zip/ZipFile;Ljava/lang/String;)Ljava/util/zip/ZipEntry;";
                    modified = true;
                    hooked.add(mn.name + "->getEntry()");
                    continue;
                }

                if (min.getOpcode() == INVOKEVIRTUAL && "java/util/jar/JarFile".equals(min.owner)
                        && "getJarEntry".equals(min.name)
                        && "(Ljava/lang/String;)Ljava/util/jar/JarEntry;".equals(min.desc)) {
                    min.setOpcode(INVOKESTATIC);
                    min.owner = BYPASS_INTERNAL;
                    min.name = "filterGetJarEntry";
                    min.desc = "(Ljava/util/jar/JarFile;Ljava/lang/String;)Ljava/util/jar/JarEntry;";
                    modified = true;
                    hooked.add(mn.name + "->getJarEntry()");
                    continue;
                }
            }
        }

        if (!modified) return null;
        applyCount++;
        InjectionLogger.log("JarScanBypass", "hook applied: " + cn.name + " | " + String.join(", ", hooked));
        return AsmUtils.toBytes(cn, loader);
    }
}
