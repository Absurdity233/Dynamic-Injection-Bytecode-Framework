package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.At;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Slice;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.WrapWithCondition;
import com.heypixel.heypixelmod.cc.mizore.client.transform.injection.struct.Target;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@NativeStub
public class WrapWithConditionHook implements Opcodes {
    
    public static boolean apply(HookContext ctx, ClassNode cn, MethodNode mn, Method hook, 
                                WrapWithCondition wwc, Slice slice) {
        At at = wwc.at();
        if (!"INVOKE".equals(at.value())) {
            ctx.warn("@WrapWithCondition requires @At(\"INVOKE\"), got: " + at.value());
            return false;
        }
        
        Target t = Target.parse(at.target());
        if (t == null) {
            ctx.warn("@WrapWithCondition: Invalid target: " + at.target());
            return false;
        }
        
        boolean remap = ctx.isClassRemap() && wwc.remap() && at.remap();
        String targetOwner = remap ? ctx.remapClass(t.owner) : t.owner;
        String targetName = remap ? ctx.remapMethod(t.owner, t.name, t.desc) : t.name;
        String targetDesc = remap ? ctx.remapDesc(t.desc) : t.desc;
        
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
        
        boolean found = false;
        for (AbstractInsnNode insn : candidates) {
            MethodInsnNode min = (MethodInsnNode) insn;
            boolean isStaticCall = (min.getOpcode() == INVOKESTATIC);
            Type[] args = Type.getArgumentTypes(targetDesc);
            Type retType = Type.getReturnType(targetDesc);
            
            int totalSlots = isStaticCall ? 0 : 1;
            for (Type arg : args) totalSlots += arg.getSize();
            
            int localBase = mn.maxLocals;
            
            // Allocate local slots: args first (in parameter order), then instance
            int[] argLocals = new int[args.length];
            int instanceLocal = -1;
            
            for (int i = 0; i < args.length; i++) {
                argLocals[i] = localBase;
                localBase += args[i].getSize();
            }
            if (!isStaticCall) {
                instanceLocal = localBase;
                localBase++;
            }
            
            InsnList before = new InsnList();
            
            // Pop from stack top to bottom: argN-1, argN-2, ..., arg0, [instance]
            for (int i = args.length - 1; i >= 0; i--) {
                before.add(new VarInsnNode(args[i].getOpcode(ISTORE), argLocals[i]));
            }
            if (!isStaticCall) {
                before.add(new VarInsnNode(ASTORE, instanceLocal));
            }
            
            // Reload for condition check hook: [instance,] arg0, arg1, ..., argN-1
            if (!isStaticCall) {
                before.add(new VarInsnNode(ALOAD, instanceLocal));
            }
            for (int i = 0; i < args.length; i++) {
                before.add(new VarInsnNode(args[i].getOpcode(ILOAD), argLocals[i]));
            }
            before.add(new MethodInsnNode(INVOKESTATIC, hookOwner, hook.getName(), hookDesc, false));
            
            LabelNode skipLabel = new LabelNode();
            LabelNode endLabel = new LabelNode();
            before.add(new JumpInsnNode(IFEQ, skipLabel));
            
            // Condition true: reload for original call
            if (!isStaticCall) {
                before.add(new VarInsnNode(ALOAD, instanceLocal));
            }
            for (int i = 0; i < args.length; i++) {
                before.add(new VarInsnNode(args[i].getOpcode(ILOAD), argLocals[i]));
            }
            
            mn.instructions.insertBefore(insn, before);
            
            InsnList after = new InsnList();
            after.add(new JumpInsnNode(GOTO, endLabel));
            after.add(skipLabel);
            if (retType.getSort() != Type.VOID) {
                HookUtils.addDefaultValue(after, retType);
            }
            after.add(endLabel);
            mn.instructions.insert(insn, after);
            
            mn.maxLocals = localBase;
            found = true;
        }
        
        if (found) {
            ctx.debug("@WrapWithCondition: " + hook.getName() + " -> " + candidates.size() + " calls in " + mn.name);
        }
        return found;
    }
}
