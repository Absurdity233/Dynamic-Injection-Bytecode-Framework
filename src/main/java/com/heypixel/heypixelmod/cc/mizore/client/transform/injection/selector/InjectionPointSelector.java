package com.heypixel.heypixelmod.cc.mizore.client.transform.injection.selector;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.At;
import com.heypixel.heypixelmod.cc.mizore.client.transform.injection.struct.Target;
import com.heypixel.heypixelmod.cc.mizore.client.transform.remapper.McpRemapper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@NativeStub
public class InjectionPointSelector implements Opcodes {

    public List<AbstractInsnNode> find(MethodNode method, At at) {
        return find(method, at, null);
    }

    public List<AbstractInsnNode> find(MethodNode method, At at, String deobfOwner) {
        String point = at.value();
        int ordinal = at.ordinal();
        At.Shift shift = at.shift();
        int by = at.by();
        boolean remap = at.remap();

        List<AbstractInsnNode> result = findRaw(method, point, at.target(), at.opcode(), ordinal, deobfOwner, remap);

        if (shift != At.Shift.NONE && !result.isEmpty()) {
            result = applyShift(method, result, shift, by);
        }

        return result;
    }

    private List<AbstractInsnNode> findRaw(MethodNode method, String point, String target,
                                           int opcode, int ordinal, String deobfOwner, boolean remap) {
        return switch (point) {
            case "HEAD" -> findHead(method);
            case "RETURN" -> selectByOrdinal(findReturn(method), ordinal);
            case "TAIL" -> findTail(method);
            case "INVOKE", "INVOKE_ASSIGN" -> findInvoke(method, target, ordinal, deobfOwner, point.equals("INVOKE_ASSIGN"), remap);
            case "FIELD" -> findField(method, target, opcode, ordinal, deobfOwner, remap);
            case "NEW" -> findNew(method, target, ordinal, remap);
            case "CONSTANT" -> findConstant(method, target, ordinal);
            case "JUMP" -> findJump(method, opcode, ordinal);
            case "LOAD" -> findVarInsn(method, true, opcode, ordinal);
            case "STORE" -> findVarInsn(method, false, opcode, ordinal);
            default -> Collections.emptyList();
        };
    }

    private List<AbstractInsnNode> findHead(MethodNode method) {
        AbstractInsnNode first = method.instructions.getFirst();
        while (first != null && first.getOpcode() == -1) {
            first = first.getNext();
        }
        return first != null ? List.of(first) : Collections.emptyList();
    }

    private List<AbstractInsnNode> findReturn(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (isReturnInsn(insn)) {
                result.add(insn);
            }
        }
        return result;
    }

    private List<AbstractInsnNode> findTail(MethodNode method) {
        AbstractInsnNode last = method.instructions.getLast();
        while (last != null && !isReturnInsn(last)) {
            last = last.getPrevious();
        }
        return last != null ? List.of(last) : Collections.emptyList();
    }

    private List<AbstractInsnNode> findInvoke(MethodNode method, String target, int ordinal,
                                              String deobfOwner, boolean assignOnly, boolean remap) {
        Target t = Target.parse(target);
        if (t == null) return Collections.emptyList();

        McpRemapper remapper = McpRemapper.getInstance();
        String owner = remap ? remapper.remapClass(t.owner) : t.owner;
        String name = remap ? remapper.remapMethod(t.owner, t.name, t.desc) : t.name;
        String desc = remap ? McpRemapper.remapDescStatic(t.desc, true) : t.desc;

        List<AbstractInsnNode> result = new ArrayList<>();
        int idx = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode min) {
                if (matches(min.owner, owner) && matches(min.name, name) && matches(min.desc, desc)) {
                    if (assignOnly) {
                        AbstractInsnNode next = insn.getNext();
                        while (next != null && next.getOpcode() == -1) next = next.getNext();
                        if (next == null || !isStoreInsn(next)) continue;
                    }
                    if (ordinal == -1 || idx == ordinal) {
                        result.add(insn);
                        if (ordinal != -1) return result;
                    }
                    idx++;
                }
            }
        }
        return result;
    }

    private List<AbstractInsnNode> findField(MethodNode method, String target, int opcode,
                                             int ordinal, String deobfOwner, boolean remap) {
        Target t = Target.parseField(target);
        if (t == null) return Collections.emptyList();

        McpRemapper remapper = McpRemapper.getInstance();
        String owner = remap ? remapper.remapClass(t.owner) : t.owner;
        String name = remap ? remapper.remapField(t.owner, t.name) : t.name;

        List<AbstractInsnNode> result = new ArrayList<>();
        int idx = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode fin) {
                if (matches(fin.owner, owner) && matches(fin.name, name)) {
                    if (opcode == -1 || fin.getOpcode() == opcode) {
                        if (ordinal == -1 || idx == ordinal) {
                            result.add(insn);
                            if (ordinal != -1) return result;
                        }
                        idx++;
                    }
                }
            }
        }
        return result;
    }

    private List<AbstractInsnNode> findNew(MethodNode method, String target, int ordinal, boolean remap) {
        if (target == null || target.isEmpty()) return Collections.emptyList();

        String owner = target.replace('.', '/');
        if (remap) {
            owner = McpRemapper.getInstance().remapClass(owner);
        }
        if (owner.startsWith("L") && owner.endsWith(";")) {
            owner = owner.substring(1, owner.length() - 1);
        }

        List<AbstractInsnNode> result = new ArrayList<>();
        int idx = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode tin && insn.getOpcode() == NEW) {
                if (matches(tin.desc, owner)) {
                    if (ordinal == -1 || idx == ordinal) {
                        result.add(insn);
                        if (ordinal != -1) return result;
                    }
                    idx++;
                }
            }
        }
        return result;
    }

    private List<AbstractInsnNode> findConstant(MethodNode method, String target, int ordinal) {
        Object constValue = parseConstant(target);

        List<AbstractInsnNode> result = new ArrayList<>();
        int idx = 0;
        for (AbstractInsnNode insn : method.instructions) {
            Object value = getConstantValue(insn);
            if (value != null && (constValue == null || constValue.equals(value))) {
                if (ordinal == -1 || idx == ordinal) {
                    result.add(insn);
                    if (ordinal != -1) return result;
                }
                idx++;
            }
        }
        return result;
    }

    private List<AbstractInsnNode> findJump(MethodNode method, int opcode, int ordinal) {
        List<AbstractInsnNode> result = new ArrayList<>();
        int idx = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof JumpInsnNode) {
                if (opcode == -1 || insn.getOpcode() == opcode) {
                    if (ordinal == -1 || idx == ordinal) {
                        result.add(insn);
                        if (ordinal != -1) return result;
                    }
                    idx++;
                }
            }
        }
        return result;
    }

    private List<AbstractInsnNode> findVarInsn(MethodNode method, boolean load, int opcode, int ordinal) {
        List<AbstractInsnNode> result = new ArrayList<>();
        int idx = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode) {
                int op = insn.getOpcode();
                boolean isLoad = (op >= ILOAD && op <= ALOAD);
                if (load == isLoad) {
                    if (opcode == -1 || op == opcode) {
                        if (ordinal == -1 || idx == ordinal) {
                            result.add(insn);
                            if (ordinal != -1) return result;
                        }
                        idx++;
                    }
                }
            }
        }
        return result;
    }

    private static List<AbstractInsnNode> selectByOrdinal(List<AbstractInsnNode> result, int ordinal) {
        if (ordinal >= 0 && ordinal < result.size()) {
            return List.of(result.get(ordinal));
        }
        return result;
    }

    private List<AbstractInsnNode> applyShift(MethodNode method, List<AbstractInsnNode> nodes, At.Shift shift, int by) {
        List<AbstractInsnNode> result = new ArrayList<>(nodes.size());
        for (AbstractInsnNode node : nodes) {
            AbstractInsnNode shifted = node;
            switch (shift) {
                case BEFORE:
                    shifted = skipNonCode(node.getPrevious(), false);
                    break;
                case AFTER:
                    shifted = skipNonCode(node.getNext(), true);
                    break;
                case BY:
                    boolean forward = by > 0;
                    for (int i = 0; i < Math.abs(by) && shifted != null; i++) {
                        shifted = skipNonCode(forward ? shifted.getNext() : shifted.getPrevious(), forward);
                    }
                    break;
                case NONE:
                default:
                    break;
            }
            if (shifted != null) result.add(shifted);
        }
        return result;
    }

    private static AbstractInsnNode skipNonCode(AbstractInsnNode node, boolean forward) {
        while (node != null && node.getOpcode() == -1) {
            node = forward ? node.getNext() : node.getPrevious();
        }
        return node;
    }

    private boolean isReturnInsn(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op >= IRETURN && op <= RETURN;
    }

    private boolean isStoreInsn(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return (op >= ISTORE && op <= ASTORE) || op == PUTSTATIC || op == PUTFIELD;
    }

    private boolean matches(String actual, String expected) {
        if (expected == null || expected.isEmpty()) return true;
        return expected.equals(actual);
    }

    private Object parseConstant(String target) {
        if (target == null || target.isEmpty()) return null;
        try {
            if (target.endsWith("F") || target.endsWith("f")) {
                return Float.parseFloat(target.substring(0, target.length() - 1));
            }
            if (target.endsWith("D") || target.endsWith("d")) {
                return Double.parseDouble(target.substring(0, target.length() - 1));
            }
            if (target.endsWith("L") || target.endsWith("l")) {
                return Long.parseLong(target.substring(0, target.length() - 1));
            }
            if (target.startsWith("\"") && target.endsWith("\"")) {
                return target.substring(1, target.length() - 1);
            }
            return Integer.parseInt(target);
        } catch (NumberFormatException e) {
            return target;
        }
    }

    private Object getConstantValue(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode ldc) {
            return ldc.cst;
        }
        int op = insn.getOpcode();
        if (op >= ICONST_M1 && op <= ICONST_5) {
            return op - ICONST_0;
        }
        if (op == LCONST_0) return 0L;
        if (op == LCONST_1) return 1L;
        if (op == FCONST_0) return 0.0f;
        if (op == FCONST_1) return 1.0f;
        if (op == FCONST_2) return 2.0f;
        if (op == DCONST_0) return 0.0d;
        if (op == DCONST_1) return 1.0d;
        if (insn instanceof IntInsnNode intInsn) {
            return intInsn.operand;
        }
        return null;
    }
}
