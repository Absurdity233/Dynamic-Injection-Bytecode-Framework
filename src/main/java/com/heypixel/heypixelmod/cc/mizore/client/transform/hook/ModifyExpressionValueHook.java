package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.At;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.ModifyExpressionValue;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Slice;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.List;

/**
 * @ModifyExpressionValue 注解处理器
 */
@NativeStub
public class ModifyExpressionValueHook implements Opcodes {
    
    public static boolean apply(HookContext ctx, ClassNode cn, MethodNode mn, Method hook, 
                                ModifyExpressionValue mev, Slice slice) {
        At at = mev.at();
        List<AbstractInsnNode> targets = ctx.findInjectionPoints(mn, at);
        targets = ctx.applySlice(mn, targets, slice);
        
        if (targets.isEmpty()) {
            ctx.warn("@ModifyExpressionValue: No injection point found at " + at.value() + " in " + mn.name);
            return false;
        }
        
        String hookOwner = ctx.getHookOwner();
        String hookDesc = Type.getMethodDescriptor(hook);
        
        for (AbstractInsnNode target : targets) {
            mn.instructions.insert(target, new MethodInsnNode(INVOKESTATIC, hookOwner, hook.getName(), hookDesc, false));
        }
        
        ctx.debug("@ModifyExpressionValue: " + hook.getName() + " -> " + targets.size() + " points in " + mn.name);
        return true;
    }
}
