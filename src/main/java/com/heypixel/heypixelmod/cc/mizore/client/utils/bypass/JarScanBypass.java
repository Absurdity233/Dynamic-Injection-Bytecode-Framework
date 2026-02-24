package com.heypixel.heypixelmod.cc.mizore.client.utils.bypass;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;

import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class JarScanBypass {

    private JarScanBypass() {}

    public static Enumeration<? extends ZipEntry> filterEntries(ZipFile zipFile) {
        Enumeration<? extends ZipEntry> orig = zipFile.entries();
        String jarPath = zipFile.getName();
        InjectionLogger.log("JarScan", "filterEntries hook ok, JAR: " + (jarPath != null && jarPath.length() > 80 ? "..." + jarPath.substring(jarPath.length() - 60) : jarPath));
        return new FilteredZipEntryEnumeration(orig, jarPath);
    }

    public static Stream<JarEntry> filterStream(JarFile jarFile) {
        String jarPath = jarFile.getName();
        InjectionLogger.log("JarScan", "filterStream hook ok, JAR: " + (jarPath != null && jarPath.length() > 80 ? "..." + jarPath.substring(jarPath.length() - 60) : jarPath));
        return jarFile.stream().filter(e -> !shouldHideEntry(e));
    }

    public static ZipEntry filterGetEntry(ZipFile zipFile, String name) {
        if (shouldHideName(name)) {
            InjectionLogger.log("JarScan", "filterGetEntry hook ok, filtered: " + name);
            return null;
        }
        return zipFile.getEntry(name);
    }

    public static JarEntry filterGetJarEntry(JarFile jarFile, String name) {
        if (shouldHideName(name)) {
            InjectionLogger.log("JarScan", "filterGetJarEntry hook ok, filtered: " + name);
            return null;
        }
        return jarFile.getJarEntry(name);
    }

    private static boolean shouldHideEntry(ZipEntry entry) {
        return shouldHideName(entry.getName());
    }

    private static boolean shouldHideName(String name) {
        if (name == null || !name.endsWith(".class")) return false;
        String internal = name.replace(".class", "").replace(".", "/");
        return HiddenClassRegistry.shouldHide(internal);
    }

    private static class FilteredZipEntryEnumeration implements Enumeration<ZipEntry> {
        private final Enumeration<? extends ZipEntry> delegate;
        private ZipEntry next;
        private int filteredCount;
        private boolean loggedExhausted;

        FilteredZipEntryEnumeration(Enumeration<? extends ZipEntry> delegate, String jarPath) {
            this.delegate = delegate;
            this.filteredCount = 0;
            this.loggedExhausted = false;
            advance();
        }

        private void advance() {
            while (delegate.hasMoreElements()) {
                ZipEntry e = delegate.nextElement();
                if (!shouldHideEntry(e)) {
                    next = e;
                    return;
                }
                filteredCount++;
            }
            if (filteredCount > 0 && !loggedExhausted) {
                loggedExhausted = true;
                InjectionLogger.log("JarScan", "filterEntries: hid " + filteredCount + " entries from JAR");
            }
            next = null;
        }

        @Override
        public boolean hasMoreElements() {
            return next != null;
        }

        @Override
        public ZipEntry nextElement() {
            ZipEntry r = next;
            advance();
            return r;
        }
    }
}
