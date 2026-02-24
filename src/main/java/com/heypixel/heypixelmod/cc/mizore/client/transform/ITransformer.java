package com.heypixel.heypixelmod.cc.mizore.client.transform;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;

import java.util.Collections;
import java.util.Set;

@NativeStub

public interface ITransformer {
    String getTargetClass();
    byte[] transform(byte[] classBuffer, ClassLoader loader);

    default boolean mightApply(byte[] classBytes) {
        return true;
    }

    default Set<String> getTargetClasses() {
        String single = getTargetClass();
        if (single != null) return Set.of(single);
        return Collections.emptySet();
    }

    static boolean containsUtf8(byte[] data, String target) {
        if (data == null || target == null || target.isEmpty()) return false;
        byte[] pattern = target.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int pLen = pattern.length;
        int dLen = data.length;
        if (dLen < pLen) return false;
        int limit = dLen - pLen;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < pLen; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}