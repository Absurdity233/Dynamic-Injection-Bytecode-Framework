package com.heypixel.heypixelmod.cc.mizore.client.transform.injection.struct;

public class Target {
    public final String owner;
    public final String name;
    public final String desc;
    public final int opcode;

    public Target(String owner, String name, String desc) {
        this(owner, name, desc, -1);
    }

    public Target(String owner, String name, String desc, int opcode) {
        this.owner = owner;
        this.name = name;
        this.desc = desc;
        this.opcode = opcode;
    }

    public static Target parse(String target) {
        if (target == null || target.isEmpty()) return null;
        try {
            int ownerEnd = target.indexOf(';');
            if (ownerEnd == -1) return null;
            String owner = target.substring(1, ownerEnd);
            String rest = target.substring(ownerEnd + 1);
            int nameEnd = rest.indexOf('(');
            if (nameEnd == -1) nameEnd = rest.indexOf(':');
            if (nameEnd == -1) return null;
            String name = rest.substring(0, nameEnd);
            String desc = rest.substring(nameEnd);
            if (desc.startsWith(":")) desc = desc.substring(1);
            return new Target(owner, name, desc);
        } catch (Exception e) {
            return null;
        }
    }

    public static Target parseField(String target) {
        if (target == null || target.isEmpty()) return null;
        try {
            int ownerEnd = target.indexOf(';');
            if (ownerEnd == -1) return null;
            String owner = target.substring(1, ownerEnd);
            String rest = target.substring(ownerEnd + 1);
            int colonIndex = rest.indexOf(':');
            String name, desc;
            if (colonIndex != -1) {
                name = rest.substring(0, colonIndex);
                desc = rest.substring(colonIndex + 1);
            } else {
                name = rest;
                desc = "";
            }
            return new Target(owner, name, desc);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "L" + owner + ";" + name + desc;
    }
}
