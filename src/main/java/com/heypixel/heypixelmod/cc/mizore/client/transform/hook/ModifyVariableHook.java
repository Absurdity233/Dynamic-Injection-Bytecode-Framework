package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.At;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.ModifyVariable;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Slice;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.List;

/**
 * @ModifyVariable 注解处理器
 */
@NativeStub
public class ModifyVariableHook implements Opcodes {
    
    public static boolean apply(HookContext ctx, ClassNode cn, MethodNode mn, Method hook, ModifyVariable mv, Slice slice) {
        At at = mv.at();
        int varIndex = mv.index();
        if (varIndex < 0) {
            ctx.warn("@ModifyVariable requires index >= 0");
            return false;
        }
        
        List<AbstractInsnNode> targets = ctx.findInjectionPoints(mn, at);
        targets = ctx.applySlice(mn, targets, slice);
        if (targets.isEmpty()) {
            ctx.warn("@ModifyVariable: No injection point found at " + at.value() + " in " + mn.name);
            return false;
        }
        
        String hookOwner = ctx.getHookOwner();
        String hookDesc = Type.getMethodDescriptor(hook);
        Type[] hookArgs = Type.getArgumentTypes(hookDesc);
        if (hookArgs.length == 0) {
            return false;
        }
        
        Type varType = hookArgs[0];
        
        for (AbstractInsnNode target : targets) {
            InsnList insns = new InsnList();
            insns.add(new VarInsnNode(varType.getOpcode(ILOAD), varIndex));
            insns.add(new MethodInsnNode(INVOKESTATIC, hookOwner, hook.getName(), hookDesc, false));
            insns.add(new VarInsnNode(varType.getOpcode(ISTORE), varIndex));
            mn.instructions.insert(target, insns);
        }
        
        ctx.debug("@ModifyVariable: " + hook.getName() + " -> var " + varIndex + " in " + mn.name);
        return true;
    }
}
