package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.ModifyConstant;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Slice;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@NativeStub
public class ModifyConstantHook implements Opcodes {

    public static boolean apply(HookContext ctx, ClassNode cn, MethodNode mn, Method hook, ModifyConstant mc, Slice slice) {
        int ordinal = mc.ordinal();
        Object targetValue = getTargetConstant(mc);

        String hookOwner = ctx.getHookOwner();
        String hookDesc = Type.getMethodDescriptor(hook);

        List<AbstractInsnNode> allInsns = new ArrayList<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            Object value = getConstantValue(insn);
            if (value != null && matchesConstant(value, targetValue)) {
                allInsns.add(insn);
            }
        }

        allInsns = ctx.applySlice(mn, allInsns, slice);
        if (allInsns.isEmpty()) {
            ctx.warn("@ModifyConstant: No constant " + targetValue + " found in " + mn.name);
            return false;
        }

        int idx = 0;
        boolean found = false;
        for (AbstractInsnNode insn : allInsns) {
            if (ordinal == -1 || idx == ordinal) {
                mn.instructions.insert(insn, new MethodInsnNode(INVOKESTATIC, hookOwner, hook.getName(), hookDesc, false));
                found = true;
                if (ordinal != -1) {
                    ctx.debug("@ModifyConstant: " + hook.getName() + " -> constant " + targetValue + " in " + mn.name);
                    return true;
                }
            }
            idx++;
        }
        if (found) ctx.debug("@ModifyConstant: " + hook.getName() + " -> " + allInsns.size() + " constants in " + mn.name);
        return found;
    }

    private static Object getTargetConstant(ModifyConstant mc) {
        String type = mc.constType();
        if (!type.isEmpty()) {
            return switch (type) {
                case "int" -> mc.intValue();
                case "float" -> mc.floatValue();
                case "double" -> mc.doubleValue();
                case "string" -> mc.stringValue();
                default -> mc.intValue();
            };
        }
        if (mc.stringValue() != null && !mc.stringValue().isEmpty()) return mc.stringValue();
        if (Float.compare(mc.floatValue(), 0.0f) != 0) return mc.floatValue();
        if (Double.compare(mc.doubleValue(), 0.0d) != 0) return mc.doubleValue();
        if (mc.intValue() != 0) return mc.intValue();
        return 0;
    }

    private static boolean matchesConstant(Object actual, Object expected) {
        return expected.equals(actual);
    }

    private static Object getConstantValue(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode) return ((LdcInsnNode) insn).cst;
        int op = insn.getOpcode();
        if (op >= ICONST_M1 && op <= ICONST_5) return op - ICONST_0;
        if (op == LCONST_0) return 0L;
        if (op == LCONST_1) return 1L;
        if (op == FCONST_0) return 0.0f;
        if (op == FCONST_1) return 1.0f;
        if (op == FCONST_2) return 2.0f;
        if (op == DCONST_0) return 0.0d;
        if (op == DCONST_1) return 1.0d;
        if (insn instanceof IntInsnNode) return ((IntInsnNode) insn).operand;
        return null;
    }
}
