package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.At;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Slice;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.WrapOperation;
import com.heypixel.heypixelmod.cc.mizore.client.transform.callback.Operation;
import com.heypixel.heypixelmod.cc.mizore.client.transform.injection.struct.Target;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@NativeStub
public class WrapOperationHook implements Opcodes {

    private static final String OPERATION_INTERNAL = Type.getInternalName(Operation.class);
    private static final String OPERATION_DESC = Type.getDescriptor(Operation.class);
    
    public static boolean apply(HookContext ctx, ClassNode cn, MethodNode mn, Method hook, 
                                WrapOperation wo, Slice slice) {
        At at = wo.at();
        if (!"INVOKE".equals(at.value())) {
            ctx.warn("@WrapOperation requires @At(\"INVOKE\"), got: " + at.value());
            return false;
        }
        
        Target t = Target.parse(at.target());
        if (t == null) {
            ctx.warn("@WrapOperation: Invalid target: " + at.target());
            return false;
        }
        
        boolean remap = ctx.isClassRemap() && wo.remap() && at.remap();
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
        if (candidates.isEmpty()) {
            ctx.warn("@WrapOperation: Target call not found: " + targetOwner + "." + targetName + " in " + mn.name);
            return false;
        }
        
        int wrapIdx = 0;
        boolean found = false;
        
        for (AbstractInsnNode insn : candidates) {
            MethodInsnNode min = (MethodInsnNode) insn;
            boolean isStaticCall = (min.getOpcode() == INVOKESTATIC);
            Type[] args = Type.getArgumentTypes(targetDesc);
            Type retType = Type.getReturnType(targetDesc);
            
            String trampolineName = "mizore$wrap$" + hook.getName() + "$" + wrapIdx++;
            generateTrampoline(cn, trampolineName, min, isStaticCall, args, retType);
            
            int localBase = mn.maxLocals;
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
            int opLocal = localBase;
            localBase++;
            
            InsnList before = new InsnList();
            
            for (int i = args.length - 1; i >= 0; i--) {
                before.add(new VarInsnNode(args[i].getOpcode(ISTORE), argLocals[i]));
            }
            if (!isStaticCall) {
                before.add(new VarInsnNode(ASTORE, instanceLocal));
            }
            
            // Build trampoline args array to capture in lambda: [instance?, arg0, arg1, ...]
            int captureCount = args.length + (isStaticCall ? 0 : 1);
            before.add(intInsn(captureCount));
            before.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
            
            int arrayIdx = 0;
            if (!isStaticCall) {
                before.add(new InsnNode(DUP));
                before.add(intInsn(arrayIdx++));
                before.add(new VarInsnNode(ALOAD, instanceLocal));
                before.add(new InsnNode(AASTORE));
            }
            for (int i = 0; i < args.length; i++) {
                before.add(new InsnNode(DUP));
                before.add(intInsn(arrayIdx++));
                before.add(new VarInsnNode(args[i].getOpcode(ILOAD), argLocals[i]));
                HookUtils.addBoxing(before, args[i]);
                before.add(new InsnNode(AASTORE));
            }
            
            int capturedArgsLocal = localBase++;
            before.add(new VarInsnNode(ASTORE, capturedArgsLocal));
            
            // Create Operation lambda via trampoline
            before.add(new VarInsnNode(ALOAD, capturedArgsLocal));
            before.add(new LdcInsnNode(cn.name));
            before.add(new LdcInsnNode(trampolineName));
            before.add(new LdcInsnNode(getTrampolineDesc(isStaticCall, args, retType)));
            before.add(new MethodInsnNode(INVOKESTATIC, hookOwner.replace('.', '/'),
                    "mizore$createOperation", 
                    "([Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)" + OPERATION_DESC,
                    false));
            
            // Actually, simpler approach: create an anonymous inner class pattern via a static helper
            // Rewrite: use a simpler approach - store captured args and create Operation inline
            before.clear();
            
            // Simpler approach: save args, reload for hook call with Operation.of(() -> originalCall)
            // Since we can't easily create lambdas in bytecode, use a trampoline static method approach
            
            for (int i = args.length - 1; i >= 0; i--) {
                before.add(new VarInsnNode(args[i].getOpcode(ISTORE), argLocals[i]));
            }
            if (!isStaticCall) {
                before.add(new VarInsnNode(ASTORE, instanceLocal));
            }
            
            // Reload args for hook: [instance,] arg0, arg1, ..., argN-1, Operation
            if (!isStaticCall) {
                before.add(new VarInsnNode(ALOAD, instanceLocal));
            }
            for (int i = 0; i < args.length; i++) {
                before.add(new VarInsnNode(args[i].getOpcode(ILOAD), argLocals[i]));
            }
            
            // Push null Operation placeholder (hook must handle null = call original)
            // The hook method's last param is Operation - push a functional impl
            // Create Operation via capturing args in an Object[] and invoking trampoline
            
            // Build captured args array
            before.add(intInsn(captureCount));
            before.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
            arrayIdx = 0;
            if (!isStaticCall) {
                before.add(new InsnNode(DUP));
                before.add(intInsn(arrayIdx++));
                before.add(new VarInsnNode(ALOAD, instanceLocal));
                before.add(new InsnNode(AASTORE));
            }
            for (int i = 0; i < args.length; i++) {
                before.add(new InsnNode(DUP));
                before.add(intInsn(arrayIdx++));
                before.add(new VarInsnNode(args[i].getOpcode(ILOAD), argLocals[i]));
                HookUtils.addBoxing(before, args[i]);
                before.add(new InsnNode(AASTORE));
            }
            
            // Create the Operation: WrapOperationHook.createOp(capturedArgs, owner, name, desc)
            before.add(new LdcInsnNode(min.owner));
            before.add(new LdcInsnNode(min.name));
            before.add(new LdcInsnNode(min.desc));
            before.add(new InsnNode(isStaticCall ? ICONST_1 : ICONST_0));
            before.add(new MethodInsnNode(INVOKESTATIC,
                    Type.getInternalName(WrapOperationHook.class),
                    "createOp",
                    "([Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)" + OPERATION_DESC,
                    false));
            
            mn.instructions.insertBefore(insn, before);
            mn.instructions.set(insn, new MethodInsnNode(INVOKESTATIC, hookOwner, hook.getName(), hookDesc, false));
            
            mn.maxLocals = localBase;
            found = true;
        }
        
        if (found) {
            ctx.debug("@WrapOperation: " + hook.getName() + " -> " + candidates.size() + " calls in " + mn.name);
        }
        return found;
    }
    
    private static void generateTrampoline(ClassNode cn, String name, MethodInsnNode original,
                                           boolean isStaticCall, Type[] args, Type retType) {
        StringBuilder desc = new StringBuilder("(");
        if (!isStaticCall) desc.append("Ljava/lang/Object;");
        for (Type arg : args) desc.append(arg.getDescriptor());
        desc.append(")");
        desc.append(retType.getSort() == Type.VOID ? "Ljava/lang/Object;" : retType.getDescriptor());
        
        MethodNode trampoline = new MethodNode(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                name, desc.toString(), null, null);
        
        InsnList insns = new InsnList();
        int paramIdx = 0;
        
        if (!isStaticCall) {
            insns.add(new VarInsnNode(ALOAD, paramIdx++));
            insns.add(new TypeInsnNode(CHECKCAST, original.owner));
        }
        for (Type arg : args) {
            insns.add(new VarInsnNode(arg.getOpcode(ILOAD), paramIdx));
            paramIdx += arg.getSize();
        }
        
        insns.add(new MethodInsnNode(original.getOpcode(), original.owner, original.name, original.desc, original.itf));
        
        if (retType.getSort() == Type.VOID) {
            insns.add(new InsnNode(ACONST_NULL));
            insns.add(new InsnNode(ARETURN));
        } else {
            insns.add(new InsnNode(retType.getOpcode(IRETURN)));
        }
        
        trampoline.instructions = insns;
        trampoline.maxLocals = paramIdx;
        trampoline.maxStack = Math.max(paramIdx, 4);
        cn.methods.add(trampoline);
    }
    
    /**
     * Runtime helper: creates an Operation that reflectively calls the trampoline with captured args.
     * Injected into target class bytecode as INVOKESTATIC.
     */
    @SuppressWarnings("unchecked")
    public static <R> Operation<R> createOp(Object[] capturedArgs, String owner, String name, String desc, boolean isStatic) {
        return (callArgs) -> {
            try {
                Class<?> ownerClass = Class.forName(owner.replace('/', '.'), false, 
                        Thread.currentThread().getContextClassLoader());
                
                Type[] argTypes = Type.getArgumentTypes(desc);
                Type retType = Type.getReturnType(desc);
                Class<?>[] paramClasses = new Class<?>[argTypes.length];
                for (int i = 0; i < argTypes.length; i++) {
                    paramClasses[i] = typeToClass(argTypes[i]);
                }
                
                java.lang.reflect.Method method = ownerClass.getDeclaredMethod(name, paramClasses);
                method.setAccessible(true);
                
                Object target = isStatic ? null : capturedArgs[0];
                Object[] methodArgs;
                if (callArgs != null && callArgs.length > 0) {
                    methodArgs = callArgs;
                } else {
                    int offset = isStatic ? 0 : 1;
                    methodArgs = new Object[capturedArgs.length - offset];
                    System.arraycopy(capturedArgs, offset, methodArgs, 0, methodArgs.length);
                }
                
                Object result = method.invoke(target, methodArgs);
                return (R) result;
            } catch (Exception e) {
                throw new RuntimeException("WrapOperation call failed: " + owner + "." + name + desc, e);
            }
        };
    }
    
    private static Class<?> typeToClass(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> boolean.class;
            case Type.BYTE -> byte.class;
            case Type.CHAR -> char.class;
            case Type.SHORT -> short.class;
            case Type.INT -> int.class;
            case Type.LONG -> long.class;
            case Type.FLOAT -> float.class;
            case Type.DOUBLE -> double.class;
            case Type.VOID -> void.class;
            default -> {
                try {
                    yield Class.forName(type.getClassName(), false, Thread.currentThread().getContextClassLoader());
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }
    
    private static String getTrampolineDesc(boolean isStaticCall, Type[] args, Type retType) {
        StringBuilder sb = new StringBuilder("(");
        if (!isStaticCall) sb.append("Ljava/lang/Object;");
        for (Type arg : args) sb.append(arg.getDescriptor());
        sb.append(")");
        sb.append(retType.getSort() == Type.VOID ? "Ljava/lang/Object;" : retType.getDescriptor());
        return sb.toString();
    }
    
    private static AbstractInsnNode intInsn(int value) {
        if (value >= -1 && value <= 5) return new InsnNode(ICONST_0 + value);
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, value);
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, value);
        return new LdcInsnNode(value);
    }
}
