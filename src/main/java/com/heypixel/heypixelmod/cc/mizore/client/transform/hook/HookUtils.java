package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.HashMap;
import java.util.Map;

@NativeStub
public class HookUtils implements Opcodes {
    public static void addUnboxing(InsnList insns, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN -> {
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Boolean"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
            }
            case Type.BYTE -> {
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Byte"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false));
            }
            case Type.CHAR -> {
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Character"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false));
            }
            case Type.SHORT -> {
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Short"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false));
            }
            case Type.INT -> {
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Integer"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
            }
            case Type.LONG -> {
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
            }
            case Type.FLOAT -> {
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Float"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false));
            }
            case Type.DOUBLE -> {
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Double"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false));
            }
            default -> insns.add(new TypeInsnNode(CHECKCAST, type.getInternalName()));
        }
    }

    public static void addDefaultValue(InsnList insns, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> insns.add(new InsnNode(ICONST_0));
            case Type.LONG -> insns.add(new InsnNode(LCONST_0));
            case Type.FLOAT -> insns.add(new InsnNode(FCONST_0));
            case Type.DOUBLE -> insns.add(new InsnNode(DCONST_0));
            default -> insns.add(new InsnNode(ACONST_NULL));
        }
    }

    public static InsnList cloneInsns(InsnList source) {
        InsnList clone = new InsnList();
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn : source) {
            if (insn instanceof LabelNode) {
                labels.put((LabelNode) insn, new LabelNode());
            }
        }
        for (AbstractInsnNode insn : source) {
            clone.add(insn.clone(labels));
        }
        return clone;
    }

    public static void loadMethodArgs(InsnList insns, MethodNode mn, boolean isStatic) {
        int paramIdx = isStatic ? 0 : 1;
        for (Type t : Type.getArgumentTypes(mn.desc)) {
            insns.add(new VarInsnNode(t.getOpcode(ILOAD), paramIdx));
            paramIdx += t.getSize();
        }
    }

    public static void loadMethodArgs(InsnList insns, String desc, boolean isStatic) {
        int paramIdx = isStatic ? 0 : 1;
        for (Type t : Type.getArgumentTypes(desc)) {
            insns.add(new VarInsnNode(t.getOpcode(ILOAD), paramIdx));
            paramIdx += t.getSize();
        }
    }

    public static void addReturnInsn(InsnList insns, Type returnType) {
        insns.add(new InsnNode(returnType.getOpcode(IRETURN)));
    }

    public static void addBoxing(InsnList insns, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN -> insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
            case Type.BYTE -> insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
            case Type.CHAR -> insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
            case Type.SHORT -> insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
            case Type.INT -> insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
            case Type.LONG -> insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
            case Type.FLOAT -> insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
            case Type.DOUBLE -> insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
            default -> { /* object types don't need boxing */ }
        }
    }
}
