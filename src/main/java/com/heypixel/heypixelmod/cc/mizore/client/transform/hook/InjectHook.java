package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.At;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Inject;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Local;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Slice;
import com.heypixel.heypixelmod.cc.mizore.client.transform.callback.CallbackInfo;
import com.heypixel.heypixelmod.cc.mizore.client.transform.callback.CallbackInfoReturnable;
import com.heypixel.heypixelmod.cc.mizore.client.transform.MixinValidator;
import com.heypixel.heypixelmod.cc.mizore.client.transform.callback.LocalRef;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

@NativeStub
public class InjectHook implements Opcodes {

    private static final String LOCAL_REF_INTERNAL = Type.getInternalName(LocalRef.class);

    public static boolean apply(HookContext ctx, ClassNode cn, MethodNode mn, Method hook, Inject inject, Slice slice) {
        Type returnType = Type.getReturnType(mn.desc);
        boolean hasReturn = returnType.getSort() != Type.VOID;
        String sigErr = MixinValidator.validateInjectSignature(hook, mn.desc, hasReturn);
        if (sigErr != null) {
            ctx.warn(sigErr);
            return false;
        }
        At at = inject.at();
        boolean cancellable = inject.cancellable();
        List<AbstractInsnNode> targets = ctx.findInjectionPoints(mn, at);
        targets = ctx.applySlice(mn, targets, slice);

        if (targets.isEmpty()) {
            ctx.warn("@Inject: No injection point found for " + hook.getName() + " at " + at.value() +
                     (at.target().isEmpty() ? "" : "(" + at.target() + ")") + " in " + mn.name + mn.desc);
            return false;
        }
        ctx.debug("@Inject: " + hook.getName() + " -> " + mn.name + " at " + at.value() + " (" + targets.size() + " points)");

        String ciClass = hasReturn ? Type.getInternalName(CallbackInfoReturnable.class) : Type.getInternalName(CallbackInfo.class);

        boolean isStatic = (mn.access & ACC_STATIC) != 0;
        String hookOwner = ctx.getHookOwner();
        String hookName = hook.getName();
        String hookDesc = Type.getMethodDescriptor(hook);

        String point = at.value();
        boolean isReturnPoint = "RETURN".equals(point) || "TAIL".equals(point);

        int ciLocal = mn.maxLocals;
        mn.maxLocals++;
        int retValLocal = -1;
        if (hasReturn && isReturnPoint) {
            retValLocal = mn.maxLocals;
            mn.maxLocals += returnType.getSize();
        }

        List<LocalCapture> localCaptures = analyzeLocalCaptures(hook, mn, isStatic, ctx);

        int refTempBase = mn.maxLocals;
        int refCount = 0;
        for (LocalCapture cap : localCaptures) {
            if (cap.isRef) refCount++;
        }
        mn.maxLocals += refCount;

        if ("HEAD".equals(point)) {
            InsnList insns = buildInjectInsns(mn, hook, ciClass, ciLocal, cancellable, hasReturn, returnType,
                                               hookOwner, hookName, hookDesc, isStatic, localCaptures,
                                               isReturnPoint, retValLocal, refTempBase);
            mn.instructions.insert(insns);
        } else {
            for (AbstractInsnNode target : targets) {
                InsnList insns = buildInjectInsns(mn, hook, ciClass, ciLocal, cancellable, hasReturn, returnType,
                                                   hookOwner, hookName, hookDesc, isStatic, localCaptures,
                                                   isReturnPoint, retValLocal, refTempBase);
                if (isReturnPoint) {
                    mn.instructions.insertBefore(target, insns);
                } else {
                    mn.instructions.insert(target, insns);
                }
            }
        }
        return true;
    }

    private static InsnList buildInjectInsns(MethodNode mn, Method hook, String ciClass, int ciLocal,
                                              boolean cancellable, boolean hasReturn, Type returnType,
                                              String hookOwner, String hookName, String hookDesc,
                                              boolean isStatic, List<LocalCapture> localCaptures,
                                              boolean isReturnPoint, int retValLocal, int refTempBase) {
        InsnList insns = new InsnList();
        LabelNode skipLabel = new LabelNode();

        if (hasReturn && isReturnPoint) {
            insns.add(new VarInsnNode(returnType.getOpcode(ISTORE), retValLocal));
            insns.add(new TypeInsnNode(NEW, ciClass));
            insns.add(new InsnNode(DUP));
            insns.add(new LdcInsnNode(hookName));
            insns.add(new InsnNode(cancellable ? ICONST_1 : ICONST_0));
            insns.add(new VarInsnNode(returnType.getOpcode(ILOAD), retValLocal));
            HookUtils.addBoxing(insns, returnType);
            insns.add(new MethodInsnNode(INVOKESPECIAL, ciClass, "<init>",
                    "(Ljava/lang/String;ZLjava/lang/Object;)V", false));
            insns.add(new VarInsnNode(ASTORE, ciLocal));
        } else {
            insns.add(new TypeInsnNode(NEW, ciClass));
            insns.add(new InsnNode(DUP));
            insns.add(new LdcInsnNode(hookName));
            insns.add(new InsnNode(cancellable ? ICONST_1 : ICONST_0));
            insns.add(new MethodInsnNode(INVOKESPECIAL, ciClass, "<init>", "(Ljava/lang/String;Z)V", false));
            insns.add(new VarInsnNode(ASTORE, ciLocal));
        }

        if (!isStatic) {
            insns.add(new VarInsnNode(ALOAD, 0));
        }

        int paramIdx = isStatic ? 0 : 1;
        for (Type t : Type.getArgumentTypes(mn.desc)) {
            insns.add(new VarInsnNode(t.getOpcode(ILOAD), paramIdx));
            paramIdx += t.getSize();
        }

        int refTemp = refTempBase;
        for (LocalCapture cap : localCaptures) {
            if (cap.isRef) {
                cap.tempLocal = refTemp++;
                insns.add(new TypeInsnNode(NEW, LOCAL_REF_INTERNAL));
                insns.add(new InsnNode(DUP));
                insns.add(new VarInsnNode(cap.type.getOpcode(ILOAD), cap.localIndex));
                HookUtils.addBoxing(insns, cap.type);
                insns.add(new MethodInsnNode(INVOKESPECIAL, LOCAL_REF_INTERNAL, "<init>", "(Ljava/lang/Object;)V", false));
                insns.add(new VarInsnNode(ASTORE, cap.tempLocal));
            }
        }

        for (LocalCapture cap : localCaptures) {
            if (cap.isRef) {
                insns.add(new VarInsnNode(ALOAD, cap.tempLocal));
            } else {
                insns.add(new VarInsnNode(cap.type.getOpcode(ILOAD), cap.localIndex));
            }
        }

        insns.add(new VarInsnNode(ALOAD, ciLocal));
        insns.add(new MethodInsnNode(INVOKESTATIC, hookOwner, hookName, hookDesc, false));

        for (LocalCapture cap : localCaptures) {
            if (cap.isRef) {
                insns.add(new VarInsnNode(ALOAD, cap.tempLocal));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, LOCAL_REF_INTERNAL, "get", "()Ljava/lang/Object;", false));
                HookUtils.addUnboxing(insns, cap.type);
                insns.add(new VarInsnNode(cap.type.getOpcode(ISTORE), cap.localIndex));
            }
        }

        if (cancellable) {
            insns.add(new VarInsnNode(ALOAD, ciLocal));
            insns.add(new MethodInsnNode(INVOKEVIRTUAL, ciClass, "isCancelled", "()Z", false));
            insns.add(new JumpInsnNode(IFEQ, skipLabel));

            if (hasReturn) {
                insns.add(new VarInsnNode(ALOAD, ciLocal));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, ciClass, "getReturnValue", "()Ljava/lang/Object;", false));
                HookUtils.addUnboxing(insns, returnType);
                insns.add(new InsnNode(returnType.getOpcode(IRETURN)));
            } else {
                insns.add(new InsnNode(RETURN));
            }
            insns.add(skipLabel);
        }

        if (hasReturn && isReturnPoint) {
            if (cancellable) {
                insns.add(new VarInsnNode(ALOAD, ciLocal));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, ciClass, "getReturnValue", "()Ljava/lang/Object;", false));
                HookUtils.addUnboxing(insns, returnType);
            } else {
                insns.add(new VarInsnNode(returnType.getOpcode(ILOAD), retValLocal));
            }
        }

        return insns;
    }

    private static final class LocalCapture {
        final int localIndex;
        final Type type;
        final boolean isRef;
        int tempLocal;

        LocalCapture(int localIndex, Type type, boolean isRef) {
            this.localIndex = localIndex;
            this.type = type;
            this.isRef = isRef;
            this.tempLocal = -1;
        }
    }

    private static List<LocalCapture> analyzeLocalCaptures(Method hook, MethodNode mn, boolean isStatic, HookContext ctx) {
        List<LocalCapture> captures = new ArrayList<>();
        Parameter[] params = hook.getParameters();
        for (int i = 0; i < params.length; i++) {
            Local local = params[i].getAnnotation(Local.class);
            if (local == null) continue;

            Class<?> paramType = params[i].getType();
            boolean isRef = paramType == LocalRef.class;
            Type actualType = isRef ? null : Type.getType(paramType);

            int idx = -1;
            if (local.index() >= 0) {
                idx = local.index();
                if (isRef) {
                    actualType = resolveLocalType(mn, idx);
                }
            } else if (!local.name().isEmpty()) {
                idx = findLocalByName(mn, local.name());
                if (idx == -1) {
                    ctx.warn("@Local name '" + local.name() + "' not found in " + hook.getName());
                    continue;
                }
                if (isRef) {
                    actualType = resolveLocalType(mn, idx);
                }
            } else if (local.ordinal() >= 0) {
                if (!isRef && actualType != null) {
                    idx = findLocalByOrdinal(mn, actualType, local.ordinal(), isStatic);
                } else {
                    ctx.warn("@Local ordinal with LocalRef requires index or name in " + hook.getName());
                    continue;
                }
                if (idx == -1) {
                    ctx.warn("@Local ordinal " + local.ordinal() + " for type " + actualType + " not found in " + hook.getName());
                    continue;
                }
            } else {
                continue;
            }

            if (idx < 0) continue;

            if (isRef) {
                if (actualType == null) {
                    ctx.warn("@Local(LocalRef) could not resolve type for local " + idx + " in " + hook.getName());
                    continue;
                }
                captures.add(new LocalCapture(idx, actualType, true));
            } else {
                captures.add(new LocalCapture(idx, actualType, false));
            }
        }
        return captures;
    }

    private static int findLocalByName(MethodNode mn, String name) {
        if (mn.localVariables == null) return -1;
        for (LocalVariableNode lvn : mn.localVariables) {
            if (lvn.name.equals(name)) return lvn.index;
        }
        return -1;
    }

    private static int findLocalByOrdinal(MethodNode mn, Type type, int ordinal, boolean isStatic) {
        if (mn.localVariables == null) return -1;
        int paramSlots = isStatic ? 0 : 1;
        for (Type t : Type.getArgumentTypes(mn.desc)) {
            paramSlots += t.getSize();
        }
        int count = 0;
        for (LocalVariableNode lvn : mn.localVariables) {
            if (lvn.index < paramSlots) continue;
            if (Type.getType(lvn.desc).equals(type)) {
                if (count == ordinal) return lvn.index;
                count++;
            }
        }
        return -1;
    }

    private static Type resolveLocalType(MethodNode mn, int index) {
        if (mn.localVariables != null) {
            for (LocalVariableNode lvn : mn.localVariables) {
                if (lvn.index == index) return Type.getType(lvn.desc);
            }
        }
        return null;
    }
}
