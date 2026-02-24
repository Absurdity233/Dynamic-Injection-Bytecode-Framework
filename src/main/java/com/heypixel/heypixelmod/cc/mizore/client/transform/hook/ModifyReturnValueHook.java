package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.At;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.ModifyReturnValue;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Slice;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.List;

@NativeStub
public class ModifyReturnValueHook implements Opcodes {

    public static boolean apply(HookContext ctx, ClassNode cn, MethodNode mn, Method hook,
                                ModifyReturnValue mrv, Slice slice) {
        At at = mrv.at();
        List<AbstractInsnNode> targets = ctx.findInjectionPoints(mn, at);
        targets = ctx.applySlice(mn, targets, slice);

        if (targets.isEmpty()) {
            ctx.warn("@ModifyReturnValue: No injection point found at " + at.value() + " in " + mn.name);
            return false;
        }

        String hookOwner = ctx.getHookOwner();
        String hookDesc = Type.getMethodDescriptor(hook);
        Type returnType = Type.getReturnType(mn.desc);
        boolean isStatic = (mn.access & ACC_STATIC) != 0;
        Type[] hookParams = Type.getArgumentTypes(hookDesc);
        boolean needsThis = !isStatic && hookParams.length > 1;
        int tempLocal = -1;
        if (needsThis) {
            tempLocal = mn.maxLocals;
            mn.maxLocals += returnType.getSize();
        }

        for (AbstractInsnNode target : targets) {
            InsnList insns = new InsnList();
            if (needsThis) {
                insns.add(new VarInsnNode(returnType.getOpcode(ISTORE), tempLocal));
                insns.add(new VarInsnNode(ALOAD, 0));
                insns.add(new VarInsnNode(returnType.getOpcode(ILOAD), tempLocal));
            }
            insns.add(new MethodInsnNode(INVOKESTATIC, hookOwner, hook.getName(), hookDesc, false));
            mn.instructions.insertBefore(target, insns);
        }

        ctx.debug("@ModifyReturnValue: " + hook.getName() + " -> " + targets.size() + " points in " + mn.name);
        return true;
    }
}
