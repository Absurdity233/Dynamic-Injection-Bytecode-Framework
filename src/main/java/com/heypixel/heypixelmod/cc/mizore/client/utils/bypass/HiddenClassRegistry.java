package com.heypixel.heypixelmod.cc.mizore.client.utils.bypass;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
public final class HiddenClassRegistry {

    private static final Set<String> FULL_NAMES = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final CopyOnWriteArrayList<String> PREFIXES = new CopyOnWriteArrayList<>();

    static {
        PREFIXES.add("io/github/humbleui/");
        PREFIXES.add("com/sun/jna/");
        PREFIXES.add("org/luaj/vm2/");
        PREFIXES.add("org/java_websocket/");
        PREFIXES.add("org/objectweb/asm/");
        PREFIXES.add("com/heypixel/heypixelmod/cc/mizore/");
        PREFIXES.add("dev/jnic/");
        PREFIXES.add("com/proxima/");
    }

    private HiddenClassRegistry() {}

    public static void addPrefix(String internalPrefix) {
        if (internalPrefix != null && !internalPrefix.isEmpty()) {
            String normalized = internalPrefix.endsWith("/") ? internalPrefix : internalPrefix + "/";
            if (!PREFIXES.contains(normalized)) {
                PREFIXES.addIfAbsent(normalized);
            }
        }
    }

    public static void addFullName(String internalName) {
        if (internalName != null && !internalName.isEmpty()) {
            FULL_NAMES.add(internalName);
        }
    }

    public static boolean shouldHide(String internalName) {
        if (internalName == null) return false;
        if (FULL_NAMES.contains(internalName)) return true;
        for (String p : PREFIXES) {
            if (internalName.startsWith(p)) return true;
        }
        return false;
    }
}

