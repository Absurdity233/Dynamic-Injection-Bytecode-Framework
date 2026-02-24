package com.heypixel.heypixelmod.cc.mizore.client.utils.bypass;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;

import java.io.File;
import java.util.Locale;
public final class DllScanBypass {

    private DllScanBypass() {}
    private static final String[] HIDDEN_PATTERNS = {
            "winmm.dll",
            "mizoreagent.dll",
            "mizorecore.dll",
            "loader.dll",
            "loader-*.dll",
            "mc-core.dll",
            "mc-core-*.dll",
            "lib*.tmp",
            "proxima_native.dll",
            "skija.dll",
    };

    private static final String SYSTEM32 = "system32";
    private static final String SYSWOW64 = "syswow64";

    private static volatile boolean loggedFirst = false;


    public static boolean hookFileExists(File file) {
        try {
            if (shouldHideDll(file)) {
                if (!loggedFirst) {
                    loggedFirst = true;
                    InjectionLogger.log("DllScan", "File.exists() hook active — first hidden: "
                            + file.getAbsolutePath());
                }
                return false;
            }
        } catch (Throwable ignored) {
        }
        return file.exists();
    }
    public static boolean hookFileIsFile(File file) {
        try {
            if (shouldHideDll(file)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return file.isFile();
    }


    private static boolean shouldHideDll(File file) {
        if (file == null) return false;
        try {
            String path = file.getAbsolutePath().toLowerCase(Locale.ROOT);
            String fileName = extractFileName(path);
            if (fileName.isEmpty()) return false;

            for (String pattern : HIDDEN_PATTERNS) {
                if (matchWildcard(pattern, fileName)) {
                    if ("winmm.dll".equals(pattern)) {
                        if (path.contains(SYSTEM32) || path.contains(SYSWOW64)) {
                            return false;
                        }
                    }
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }


    private static String extractFileName(String path) {
        int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSep >= 0 ? path.substring(lastSep + 1) : path;
    }

    private static boolean matchWildcard(String pattern, String str) {
        int pi = 0, si = 0;
        int starPi = -1, starSi = -1;

        while (si < str.length()) {
            if (pi < pattern.length() && pattern.charAt(pi) == '*') {
                starPi = pi++;
                starSi = si;
                continue;
            }
            if (pi < pattern.length() && pattern.charAt(pi) == str.charAt(si)) {
                pi++;
                si++;
                continue;
            }
            if (starPi >= 0) {
                pi = starPi + 1;
                si = ++starSi;
                continue;
            }
            return false;
        }

        while (pi < pattern.length() && pattern.charAt(pi) == '*') {
            pi++;
        }
        return pi == pattern.length();
    }
}
