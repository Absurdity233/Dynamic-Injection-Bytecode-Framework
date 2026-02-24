package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Redirect;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Slice;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@NativeStub
public class RedirectHook implements Opcodes {

    public static boolean apply(HookContext ctx, ClassNode cn, MethodNode mn, Method hook, Redirect redirect, Slice slice) {
        boolean remap = ctx.isClassRemap() && redirect.remap();
        String ownerRaw = redirect.targetOwner().replace('.', '/');
        String targetOwner = remap ? ctx.remapClass(ownerRaw) : ownerRaw;
        String targetName = remap ? ctx.remapMethod(ownerRaw, redirect.targetMethod(), redirect.targetDesc()) : redirect.targetMethod();
        String targetDesc = remap ? ctx.remapDesc(redirect.targetDesc()) : redirect.targetDesc();

        String hookOwner = ctx.getHookOwner();
        String hookDesc = Type.getMethodDescriptor(hook);
        int ordinal = redirect.ordinal();

        List<AbstractInsnNode> candidates = new ArrayList<>();
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof MethodInsnNode min) {
                if (min.owner.equals(targetOwner) && min.name.equals(targetName) && min.desc.equals(targetDesc)) {
                    candidates.add(insn);
                }
            }
        }

        candidates = ctx.applySlice(mn, candidates, slice);

        if (candidates.isEmpty()) {
            ctx.warn("@Redirect: Target not found: " + targetOwner + "." + targetName + targetDesc + " in " + mn.name);
            return false;
        }

        int idx = 0;
        boolean found = false;
        for (AbstractInsnNode insn : candidates) {
            if (ordinal == -1 || idx == ordinal) {
                mn.instructions.set(insn, new MethodInsnNode(INVOKESTATIC, hookOwner, hook.getName(), hookDesc, false));
                found = true;
                if (ordinal != -1) break;
            }
            idx++;
        }

        if (found) {
            ctx.debug("@Redirect: " + hook.getName() + " -> " + targetName + " in " + mn.name);
        }
        return found;
    }
}
