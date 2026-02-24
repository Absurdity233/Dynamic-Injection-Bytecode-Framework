package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.At;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.ModifyArg;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Slice;
import com.heypixel.heypixelmod.cc.mizore.client.transform.injection.struct.Target;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@NativeStub
public class ModifyArgHook implements Opcodes {
    
    public static boolean apply(HookContext ctx, ClassNode cn, MethodNode mn, Method hook, ModifyArg ma, Slice slice) {
        At at = ma.at();
        if (!"INVOKE".equals(at.value())) {
            ctx.warn("@ModifyArg requires @At(\"INVOKE\"), got: " + at.value());
            return false;
        }
        
        Target t = Target.parse(at.target());
        if (t == null) {
            ctx.warn("@ModifyArg: Invalid target: " + at.target());
            return false;
        }
        
        int argIndex = ma.index();
        if (argIndex < 0) {
            ctx.warn("@ModifyArg: index must be >= 0, got: " + argIndex);
            return false;
        }
        
        boolean remap = ctx.isClassRemap() && ma.remap() && at.remap();
        String targetOwner = remap ? ctx.remapClass(t.owner) : t.owner;
        String targetName = remap ? ctx.remapMethod(t.owner, t.name, t.desc) : t.name;
        String targetDesc = remap ? ctx.remapDesc(t.desc) : t.desc;
        
        Type[] targetArgs = Type.getArgumentTypes(targetDesc);
        if (argIndex >= targetArgs.length) {
            ctx.warn("@ModifyArg: index " + argIndex + " out of bounds for " + targetName + targetDesc + " (" + targetArgs.length + " args)");
            return false;
        }
        
        String hookOwner = ctx.getHookOwner();
        String hookDesc = Type.getMethodDescriptor(hook);
        
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
            ctx.warn("@ModifyArg: Target call not found: " + targetOwner + "." + targetName + " in " + mn.name);
            return false;
        }
        
        int ordinal = at.ordinal();
        int idx = 0;
        boolean found = false;
        
        for (AbstractInsnNode insn : candidates) {
            if (ordinal != -1 && idx != ordinal) {
                idx++;
                continue;
            }
            
            MethodInsnNode min = (MethodInsnNode) insn;
            boolean isStaticCall = (min.getOpcode() == INVOKESTATIC);
            int localBase = mn.maxLocals;
            
            // 保存所有参数到局部变量（从栈顶反向弹出）
            int[] argLocals = new int[targetArgs.length];
            for (int i = 0; i < targetArgs.length; i++) {
                argLocals[i] = localBase;
                localBase += targetArgs[i].getSize();
            }
            int instanceLocal = -1;
            if (!isStaticCall) {
                instanceLocal = localBase;
                localBase++;
            }
            
            InsnList before = new InsnList();
            
            for (int i = targetArgs.length - 1; i >= 0; i--) {
                before.add(new VarInsnNode(targetArgs[i].getOpcode(ISTORE), argLocals[i]));
            }
            if (!isStaticCall) {
                before.add(new VarInsnNode(ASTORE, instanceLocal));
            }
            
            // 调用 hook 修改指定参数
            before.add(new VarInsnNode(targetArgs[argIndex].getOpcode(ILOAD), argLocals[argIndex]));
            before.add(new MethodInsnNode(INVOKESTATIC, hookOwner, hook.getName(), hookDesc, false));
            before.add(new VarInsnNode(targetArgs[argIndex].getOpcode(ISTORE), argLocals[argIndex]));
            
            // 重新加载所有参数（正序）
            if (!isStaticCall) {
                before.add(new VarInsnNode(ALOAD, instanceLocal));
            }
            for (int i = 0; i < targetArgs.length; i++) {
                before.add(new VarInsnNode(targetArgs[i].getOpcode(ILOAD), argLocals[i]));
            }
            
            mn.instructions.insertBefore(insn, before);
            mn.maxLocals = localBase;
            found = true;
            
            if (ordinal != -1) {
                ctx.debug("@ModifyArg: " + hook.getName() + " -> arg[" + argIndex + "] of " + targetName + " in " + mn.name);
                return true;
            }
            idx++;
        }
        
        if (found) {
            ctx.debug("@ModifyArg: " + hook.getName() + " -> arg[" + argIndex + "] of " + targetName + " (" + candidates.size() + " sites) in " + mn.name);
        }
        return found;
    }
}
