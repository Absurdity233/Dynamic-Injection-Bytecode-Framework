package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.MethodHook;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;

/**
 * @MethodHook 旧版注解处理器
 */
@NativeStub
public class LegacyInjectHook implements Opcodes {
    
    public static boolean apply(HookContext ctx, ClassNode cn, MethodNode mn, Method hook, MethodHook.At at) {
        InsnList insns = new InsnList();
        boolean isStatic = (mn.access & ACC_STATIC) != 0;
        int paramIdx = isStatic ? 0 : 1;
        
        // 加载所有参数
        for (Type t : Type.getArgumentTypes(mn.desc)) {
            insns.add(new VarInsnNode(t.getOpcode(ILOAD), paramIdx));
            paramIdx += t.getSize();
        }
        
        // 调用 hook 方法
        insns.add(new MethodInsnNode(INVOKESTATIC, ctx.getHookOwner(), hook.getName(), 
                                      Type.getMethodDescriptor(hook), false));
        
        if (at == MethodHook.At.HEAD) {
            mn.instructions.insert(HookUtils.cloneInsns(insns));
            ctx.debug("@MethodHook(HEAD): " + hook.getName() + " -> " + mn.name);
            return true;
        } else if (at == MethodHook.At.RETURN) {
            boolean found = false;
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                int op = insn.getOpcode();
                if (op >= IRETURN && op <= RETURN) {
                    mn.instructions.insertBefore(insn, HookUtils.cloneInsns(insns));
                    found = true;
                }
            }
            if (found) {
                ctx.debug("@MethodHook(RETURN): " + hook.getName() + " -> " + mn.name);
            }
            return found;
        }
        return false;
    }
}
