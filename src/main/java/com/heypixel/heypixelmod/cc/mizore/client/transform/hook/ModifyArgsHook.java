package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.At;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.ModifyArgs;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Slice;
import com.heypixel.heypixelmod.cc.mizore.client.transform.callback.Args;
import com.heypixel.heypixelmod.cc.mizore.client.transform.injection.struct.Target;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@NativeStub
public class ModifyArgsHook implements Opcodes {

    private static final String ARGS_INTERNAL = Type.getInternalName(Args.class);
    private static final String ARGS_DESC = Type.getDescriptor(Args.class);
    
    public static boolean apply(HookContext ctx, ClassNode cn, MethodNode mn, Method hook, ModifyArgs ma, Slice slice) {
        At at = ma.at();
        if (!"INVOKE".equals(at.value())) {
            ctx.warn("@ModifyArgs requires @At(\"INVOKE\"), got: " + at.value());
            return false;
        }
        
        Target t = Target.parse(at.target());
        if (t == null) {
            ctx.warn("@ModifyArgs: Invalid target: " + at.target());
            return false;
        }
        
        boolean remap = ctx.isClassRemap() && ma.remap() && at.remap();
        String targetOwner = remap ? ctx.remapClass(t.owner) : t.owner;
        String targetName = remap ? ctx.remapMethod(t.owner, t.name, t.desc) : t.name;
        String targetDesc = remap ? ctx.remapDesc(t.desc) : t.desc;
        
        Type[] targetArgs = Type.getArgumentTypes(targetDesc);
        
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
            ctx.warn("@ModifyArgs: Target call not found: " + targetOwner + "." + targetName + " in " + mn.name);
            return false;
        }
        
        for (AbstractInsnNode insn : candidates) {
            MethodInsnNode min = (MethodInsnNode) insn;
            boolean isStaticCall = (min.getOpcode() == INVOKESTATIC);
            int localBase = mn.maxLocals;
            
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
            int argsLocal = localBase;
            localBase++;
            
            InsnList before = new InsnList();
            
            // 弹出所有参数
            for (int i = targetArgs.length - 1; i >= 0; i--) {
                before.add(new VarInsnNode(targetArgs[i].getOpcode(ISTORE), argLocals[i]));
            }
            if (!isStaticCall) {
                before.add(new VarInsnNode(ASTORE, instanceLocal));
            }
            
            // 创建 Object[] 数组并装箱所有参数
            before.add(intInsn(targetArgs.length));
            before.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
            for (int i = 0; i < targetArgs.length; i++) {
                before.add(new InsnNode(DUP));
                before.add(intInsn(i));
                before.add(new VarInsnNode(targetArgs[i].getOpcode(ILOAD), argLocals[i]));
                HookUtils.addBoxing(before, targetArgs[i]);
                before.add(new InsnNode(AASTORE));
            }
            
            // new Args(array)
            before.add(new TypeInsnNode(NEW, ARGS_INTERNAL));
            before.add(new InsnNode(DUP_X1));
            before.add(new InsnNode(SWAP));
            before.add(new MethodInsnNode(INVOKESPECIAL, ARGS_INTERNAL, "<init>", "([Ljava/lang/Object;)V", false));
            before.add(new VarInsnNode(ASTORE, argsLocal));
            
            // 调用 hook(Args)
            before.add(new VarInsnNode(ALOAD, argsLocal));
            before.add(new MethodInsnNode(INVOKESTATIC, hookOwner, hook.getName(), hookDesc, false));
            
            // 从 Args 中拆箱取出修改后的参数
            if (!isStaticCall) {
                before.add(new VarInsnNode(ALOAD, instanceLocal));
            }
            for (int i = 0; i < targetArgs.length; i++) {
                before.add(new VarInsnNode(ALOAD, argsLocal));
                before.add(intInsn(i));
                before.add(new MethodInsnNode(INVOKEVIRTUAL, ARGS_INTERNAL, "get", "(I)Ljava/lang/Object;", false));
                HookUtils.addUnboxing(before, targetArgs[i]);
            }
            
            mn.instructions.insertBefore(insn, before);
            mn.maxLocals = localBase;
        }
        
        ctx.debug("@ModifyArgs: " + hook.getName() + " -> " + candidates.size() + " calls in " + mn.name);
        return true;
    }
    
    private static AbstractInsnNode intInsn(int value) {
        if (value >= -1 && value <= 5) return new InsnNode(ICONST_0 + value);
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, value);
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, value);
        return new LdcInsnNode(value);
    }
}
