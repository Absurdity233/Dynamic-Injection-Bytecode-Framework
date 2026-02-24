package com.heypixel.heypixelmod.cc.mizore.client.utils.bypass;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
public class ThreadScanBypass {


    private static final ThreadMXBean REAL_BEAN;
    private static final ThreadMXBean PROXY_BEAN;
    private static final Field THREAD_INFO_STACK_TRACE_FIELD;
    private static final Field THREAD_INFO_NAME_FIELD;
    private static final sun.misc.Unsafe UNSAFE;

    private static final long THREAD_INFO_STACK_TRACE_OFFSET;
    private static final long THREAD_INFO_NAME_OFFSET;
    private static volatile boolean factoryReplaced = false;

    private static volatile boolean loggedGetBeanHook = false;

    private static final String[] THREAD_NAME_KEYWORDS = {
            "mizore", "proxima", "jnic", "humbleui", "skija", "luaj",
    };

    private static final AtomicInteger FAKE_THREAD_COUNTER = new AtomicInteger(1);


    static {
        sun.misc.Unsafe tmpUnsafe = null;
        try {
            Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            tmpUnsafe = (sun.misc.Unsafe) uf.get(null);
        } catch (Throwable ignored) {
        }
        UNSAFE = tmpUnsafe;

        long stOffset = -1;
        long tnOffset = -1;
        if (tmpUnsafe != null) {
            try {
                stOffset = tmpUnsafe.objectFieldOffset(
                        ThreadInfo.class.getDeclaredField("stackTrace"));
            } catch (Throwable ignored) {}
            try {
                tnOffset = tmpUnsafe.objectFieldOffset(
                        ThreadInfo.class.getDeclaredField("threadName"));
            } catch (Throwable ignored) {}
        }
        THREAD_INFO_STACK_TRACE_OFFSET = stOffset;
        THREAD_INFO_NAME_OFFSET = tnOffset;

        Field stField = null;
        try {
            stField = ThreadInfo.class.getDeclaredField("stackTrace");
            stField.setAccessible(true);
        } catch (Throwable ignored) {
        }
        THREAD_INFO_STACK_TRACE_FIELD = stField;

        Field tnField = null;
        try {
            tnField = ThreadInfo.class.getDeclaredField("threadName");
            tnField.setAccessible(true);
        } catch (Throwable ignored) {
        }
        THREAD_INFO_NAME_FIELD = tnField;

        ThreadMXBean real = null;
        try {
            real = ManagementFactory.getThreadMXBean();
        } catch (Throwable ignored) {
        }
        REAL_BEAN = real;

        ThreadMXBean proxy = null;
        if (real != null) {
            try {
                Set<Class<?>> interfaces = new LinkedHashSet<>();
                collectInterfaces(real.getClass(), interfaces);
                interfaces.add(ThreadMXBean.class);
                try {
                    interfaces.add(Class.forName("com.sun.management.ThreadMXBean"));
                } catch (ClassNotFoundException ignored) {
                }
                interfaces.removeIf(iface -> !Modifier.isPublic(iface.getModifiers()));

                ClassLoader proxyLoader = real.getClass().getClassLoader();
                if (proxyLoader == null) proxyLoader = ClassLoader.getSystemClassLoader();

                proxy = (ThreadMXBean) Proxy.newProxyInstance(
                        proxyLoader,
                        interfaces.toArray(new Class<?>[0]),
                        new ThreadMXBeanProxyHandler(real)
                );
            } catch (Throwable t) {
                InjectionLogger.log("ThreadScan", "proxy creation failed: " + t);
                proxy = real;
            }
        }
        PROXY_BEAN = proxy != null ? proxy : real;

        if (PROXY_BEAN != null && PROXY_BEAN != REAL_BEAN) {
            try {
                replaceThreadMXBeanInFactory(PROXY_BEAN);
            } catch (Throwable t) {
                InjectionLogger.log("ThreadScan",
                        "ManagementFactory replacement failed (call-site hooks still active): " + t);
            }
        }
    }

    public static ThreadMXBean hookGetThreadMXBean() {
        try {
            if (!loggedGetBeanHook) {
                loggedGetBeanHook = true;
                boolean isProxy = PROXY_BEAN != null && Proxy.isProxyClass(PROXY_BEAN.getClass());
                InjectionLogger.log("ThreadScan", "hookGetThreadMXBean() -> "
                        + (isProxy ? "PROXY" : "REAL (proxy failed)")
                        + ", factoryReplaced=" + factoryReplaced);
            }
        } catch (Throwable ignored) {
        }
        return PROXY_BEAN != null ? PROXY_BEAN : REAL_BEAN;
    }

    public static ThreadInfo[] hookDumpAllThreads(ThreadMXBean bean,
                                                  boolean lockedMonitors,
                                                  boolean lockedSynchronizers) {
        try {
            ThreadMXBean real = unwrapProxy(bean);
            if (real == null) return new ThreadInfo[0];

            ThreadInfo[] infos = real.dumpAllThreads(lockedMonitors, lockedSynchronizers);
            if (infos != null) {
                int totalFiltered = 0;
                for (ThreadInfo info : infos) {
                    if (info != null) totalFiltered += sanitizeThreadInfo(info);
                }
                InjectionLogger.log("ThreadScan", "dumpAllThreads hook ok, "
                        + infos.length + " threads, filtered " + totalFiltered + " frames");
            }
            return infos;
        } catch (Throwable t) {
            InjectionLogger.log("ThreadScan", "hookDumpAllThreads EXCEPTION: " + t);
            return safeDumpAllThreads(bean, lockedMonitors, lockedSynchronizers);
        }
    }

    public static ThreadInfo hookGetThreadInfo(ThreadMXBean bean, long id) {
        try {
            ThreadMXBean real = unwrapProxy(bean);
            if (real == null) return null;
            ThreadInfo info = real.getThreadInfo(id);
            if (info != null) sanitizeThreadInfo(info);
            return info;
        } catch (Throwable t) {
            InjectionLogger.log("ThreadScan", "hookGetThreadInfo(J) EXCEPTION: " + t);
            return null;
        }
    }

    public static ThreadInfo[] hookGetThreadInfo(ThreadMXBean bean, long[] ids) {
        try {
            ThreadMXBean real = unwrapProxy(bean);
            if (real == null || ids == null) return null;
            ThreadInfo[] infos = real.getThreadInfo(ids);
            if (infos != null) {
                int filtered = 0;
                for (ThreadInfo info : infos) {
                    if (info != null) filtered += sanitizeThreadInfo(info);
                }
                if (filtered > 0) {
                    InjectionLogger.log("ThreadScan",
                            "getThreadInfo([J) hook ok, filtered " + filtered + " frames");
                }
            }
            return infos;
        } catch (Throwable t) {
            InjectionLogger.log("ThreadScan", "hookGetThreadInfo([J) EXCEPTION: " + t);
            return null;
        }
    }

    public static ThreadInfo hookGetThreadInfo(ThreadMXBean bean, long id, int maxDepth) {
        try {
            ThreadMXBean real = unwrapProxy(bean);
            if (real == null) return null;
            ThreadInfo info = real.getThreadInfo(id, maxDepth);
            if (info != null) sanitizeThreadInfo(info);
            return info;
        } catch (Throwable t) {
            InjectionLogger.log("ThreadScan", "hookGetThreadInfo(JI) EXCEPTION: " + t);
            return null;
        }
    }

    public static StackTraceElement[] hookGetStackTrace(Thread thread) {
        try {
            return processStackTrace(thread.getStackTrace());
        } catch (Throwable t) {
            InjectionLogger.log("ThreadScan", "hookGetStackTrace EXCEPTION: " + t);
            try {
                return thread.getStackTrace();
            } catch (Throwable ignored) {
                return new StackTraceElement[0];
            }
        }
    }

    public static Map<Thread, StackTraceElement[]> hookGetAllStackTraces() {
        try {
            Map<Thread, StackTraceElement[]> original = Thread.getAllStackTraces();
            Map<Thread, StackTraceElement[]> result = new HashMap<>(original.size());
            for (Map.Entry<Thread, StackTraceElement[]> entry : original.entrySet()) {
                result.put(entry.getKey(), processStackTrace(entry.getValue()));
            }
            return result;
        } catch (Throwable t) {
            InjectionLogger.log("ThreadScan", "hookGetAllStackTraces EXCEPTION: " + t);
            try {
                return Thread.getAllStackTraces();
            } catch (Throwable ignored) {
                return new HashMap<>();
            }
        }
    }

    public static <T> T hookStackWalkerWalk(StackWalker walker,
                                            Function<? super Stream<StackWalker.StackFrame>, ? extends T> function) {
        try {
            return walker.walk(stream -> {
                Stream<StackWalker.StackFrame> filtered = stream
                        .filter(frame -> !isHiddenClass(frame.getClassName()))
                        .map(ThreadScanBypass::wrapStackFrame);
                return function.apply(filtered);
            });
        } catch (Throwable t) {
            InjectionLogger.log("ThreadScan", "hookStackWalkerWalk EXCEPTION: " + t);
            return walker.walk(function);
        }
    }

    public static void hookStackWalkerForEach(StackWalker walker,
                                              Consumer<? super StackWalker.StackFrame> action) {
        try {
            walker.forEach(frame -> {
                if (!isHiddenClass(frame.getClassName())) {
                    action.accept(wrapStackFrame(frame));
                }
            });
        } catch (Throwable t) {
            InjectionLogger.log("ThreadScan", "hookStackWalkerForEach EXCEPTION: " + t);
            walker.forEach(action);
        }
    }
    public static StackTraceElement[] processStackTrace(StackTraceElement[] stackTrace) {
        return processStackTrace(stackTrace, null);
    }

    private static StackTraceElement[] processStackTrace(StackTraceElement[] stackTrace,
                                                         int[] modifyCountOut) {
        if (stackTrace == null || stackTrace.length == 0) return stackTrace;

        List<StackTraceElement> result = new ArrayList<>(stackTrace.length);
        int modifyCount = 0;

        for (StackTraceElement element : stackTrace) {
            if (element == null) continue;

            if (isHiddenClass(element.getClassName())) {
                modifyCount++;
                continue;
            }

            if (element.getFileName() == null) {
                modifyCount++;
                result.add(createPatchedElement(element));
            } else {
                result.add(element);
            }
        }

        if (modifyCountOut != null) modifyCountOut[0] = modifyCount;
        return result.toArray(new StackTraceElement[0]);
    }

    private static StackTraceElement createPatchedElement(StackTraceElement original) {
        return new StackTraceElement(
                original.getClassName(),
                original.getMethodName(),
                generateFakeFileName(original.getClassName()),
                original.isNativeMethod() ? -2
                        : (original.getLineNumber() > 0 ? original.getLineNumber() : 1)
        );
    }

    private static int sanitizeThreadInfo(ThreadInfo info) {
        if (info == null) return 0;
        sanitizeThreadName(info);
        if (THREAD_INFO_STACK_TRACE_FIELD != null) {
            try {
                StackTraceElement[] original = info.getStackTrace();
                int[] modifyCount = new int[1];
                StackTraceElement[] cleaned = processStackTrace(original, modifyCount);
                THREAD_INFO_STACK_TRACE_FIELD.set(info, cleaned);
                return modifyCount[0];
            } catch (Throwable t) {
            }
        }
        return sanitizeThreadInfoUnsafe(info);
    }
    private static int sanitizeThreadInfoUnsafe(ThreadInfo info) {
        if (UNSAFE == null || info == null || THREAD_INFO_STACK_TRACE_OFFSET < 0) return 0;
        try {
            StackTraceElement[] original = info.getStackTrace();
            int[] modifyCount = new int[1];
            StackTraceElement[] cleaned = processStackTrace(original, modifyCount);
            UNSAFE.putObject(info, THREAD_INFO_STACK_TRACE_OFFSET, cleaned);
            return modifyCount[0];
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void sanitizeThreadName(ThreadInfo info) {
        if (info == null) return;
        try {
            String name = info.getThreadName();
            if (name == null || !isSuspiciousThreadName(name)) return;

            String fakeName = generateFakeThreadName();
            if (THREAD_INFO_NAME_FIELD != null) {
                try {
                    THREAD_INFO_NAME_FIELD.set(info, fakeName);
                    InjectionLogger.log("ThreadScan", "sanitized thread name: \""
                            + name + "\" -> \"" + fakeName + "\"");
                    return;
                } catch (Throwable ignored) {
                }
            }
            if (UNSAFE != null && THREAD_INFO_NAME_OFFSET >= 0) {
                try {
                    UNSAFE.putObject(info, THREAD_INFO_NAME_OFFSET, fakeName);
                    InjectionLogger.log("ThreadScan", "sanitized thread name (unsafe): \""
                            + name + "\" -> \"" + fakeName + "\"");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isSuspiciousThreadName(String name) {
        String lower = name.toLowerCase();
        for (String kw : THREAD_NAME_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    private static String generateFakeThreadName() {
        int id = FAKE_THREAD_COUNTER.getAndIncrement();
        return "pool-2-thread-" + id;
    }

    private static class ThreadMXBeanProxyHandler implements InvocationHandler {
        final ThreadMXBean real;

        ThreadMXBeanProxyHandler(ThreadMXBean real) {
            this.real = real;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            if ("equals".equals(name) && method.getParameterCount() == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name) && method.getParameterCount() == 0) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(name) && method.getParameterCount() == 0) {
                return real.toString();
            }

            try {
                Object result = method.invoke(real, args);

                if (result instanceof ThreadInfo singleInfo) {
                    sanitizeThreadInfo(singleInfo);
                } else if (result instanceof ThreadInfo[] infos) {
                    for (ThreadInfo info : infos) {
                        if (info != null) sanitizeThreadInfo(info);
                    }
                }

                return result;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw cause != null ? cause : e;
            } catch (Throwable t) {
                InjectionLogger.log("ThreadScan", "proxy invoke EXCEPTION on " + name + ": " + t);
                throw t;
            }
        }
    }

    private static void replaceThreadMXBeanInFactory(ThreadMXBean proxy) {
        if (tryReplaceByFieldScan(ManagementFactory.class, proxy)) {
            factoryReplaced = true;
            return;
        }

        try {
            Class<?> helperClass = Class.forName("sun.management.ManagementFactoryHelper");
            if (tryReplaceByFieldScan(helperClass, proxy)) {
                factoryReplaced = true;
                return;
            }
        } catch (ClassNotFoundException ignored) {
        }

        try {
            Class<?> providerClass = Class.forName("sun.management.spi.PlatformMBeanProvider");
            if (tryReplaceByFieldScan(providerClass, proxy)) {
                factoryReplaced = true;
                return;
            }
        } catch (ClassNotFoundException ignored) {
        }

        if (tryReplaceByUnsafe(ManagementFactory.class, proxy)) {
            factoryReplaced = true;
            return;
        }

        InjectionLogger.log("ThreadScan",
                "all ManagementFactory replacement strategies failed — relying on call-site hooks only");
    }

    private static boolean tryReplaceByFieldScan(Class<?> targetClass, ThreadMXBean proxy) {
        try {
            for (Field f : targetClass.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object current = f.get(null);
                    if (current instanceof ThreadMXBean && !Proxy.isProxyClass(current.getClass())) {
                        f.set(null, proxy);
                        InjectionLogger.log("ThreadScan",
                                "replaced " + targetClass.getSimpleName() + "." + f.getName()
                                        + " via reflection");
                        return true;
                    }
                } catch (Throwable ignored) {

                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }


    private static boolean tryReplaceByUnsafe(Class<?> targetClass, ThreadMXBean proxy) {
        if (UNSAFE == null) return false;
        try {
            for (Field f : targetClass.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {

                    Object base = UNSAFE.staticFieldBase(f);
                    long offset = UNSAFE.staticFieldOffset(f);
                    Object current = UNSAFE.getObject(base, offset);
                    if (current instanceof ThreadMXBean && !Proxy.isProxyClass(current.getClass())) {
                        UNSAFE.putObject(base, offset, proxy);
                        InjectionLogger.log("ThreadScan",
                                "replaced " + targetClass.getSimpleName() + "." + f.getName()
                                        + " via Unsafe");
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }


    private static ThreadMXBean unwrapProxy(ThreadMXBean bean) {
        if (bean == null) return REAL_BEAN;
        try {
            if (Proxy.isProxyClass(bean.getClass())) {
                InvocationHandler handler = Proxy.getInvocationHandler(bean);
                if (handler instanceof ThreadMXBeanProxyHandler) {
                    return ((ThreadMXBeanProxyHandler) handler).real;
                }
            }
        } catch (Throwable ignored) {
        }
        return bean;
    }


    private static ThreadInfo[] safeDumpAllThreads(ThreadMXBean bean,
                                                   boolean lockedMonitors,
                                                   boolean lockedSynchronizers) {
        try {
            ThreadMXBean real = unwrapProxy(bean);
            if (real == null) real = bean;
            return real != null ? real.dumpAllThreads(lockedMonitors, lockedSynchronizers) : new ThreadInfo[0];
        } catch (Throwable ignored) {
            return new ThreadInfo[0];
        }
    }

    static boolean isHiddenClass(String className) {
        if (className == null) return false;
        return HiddenClassRegistry.shouldHide(className.replace('.', '/'));
    }
    private static StackWalker.StackFrame wrapStackFrame(StackWalker.StackFrame frame) {
        if (frame == null || frame.getFileName() != null) return frame;

        ClassLoader proxyLoader = StackWalker.StackFrame.class.getClassLoader();
        if (proxyLoader == null) proxyLoader = ClassLoader.getSystemClassLoader();

        return (StackWalker.StackFrame) Proxy.newProxyInstance(
                proxyLoader,
                new Class<?>[]{StackWalker.StackFrame.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getFileName":
                            if (method.getParameterCount() == 0)
                                return generateFakeFileName(frame.getClassName());
                            break;
                        case "toStackTraceElement":
                            if (method.getParameterCount() == 0) {
                                StackTraceElement orig = frame.toStackTraceElement();
                                if (orig.getFileName() == null) {
                                    return createPatchedElement(orig);
                                }
                                return orig;
                            }
                            break;
                    }
                    return method.invoke(frame, args);
                }
        );
    }
    private static String generateFakeFileName(String className) {
        if (className == null || className.isEmpty()) return "NativeCode.java";

        String simpleName = className;
        int lastDot = className.lastIndexOf('.');
        if (lastDot >= 0) simpleName = className.substring(lastDot + 1);

        int dollar = simpleName.indexOf('$');
        if (dollar > 0) simpleName = simpleName.substring(0, dollar);

        if (simpleName.contains("Lambda") || simpleName.startsWith("$$Lambda")) {
            String[] parts = className.split("\\$\\$Lambda");
            if (parts.length > 0 && !parts[0].isEmpty()) {
                int dot = parts[0].lastIndexOf('.');
                simpleName = dot >= 0 ? parts[0].substring(dot + 1) : parts[0];
            } else {
                simpleName = "LambdaExpression";
            }
        }

        if (simpleName.matches(".*\\d+$")) {
            simpleName = simpleName.replaceAll("\\d+$", "");
            if (simpleName.isEmpty()) simpleName = "AnonymousClass";
        }

        simpleName = simpleName.replaceAll("[^a-zA-Z0-9_]", "");
        if (simpleName.isEmpty()) simpleName = "GeneratedClass";

        return simpleName + ".java";
    }


    private static void collectInterfaces(Class<?> clazz, Set<Class<?>> result) {
        if (clazz == null) return;
        for (Class<?> iface : clazz.getInterfaces()) {
            if (result.add(iface)) {
                collectInterfaces(iface, result);
            }
        }
        collectInterfaces(clazz.getSuperclass(), result);
    }

    public static boolean isFactoryReplaced() {
        return factoryReplaced;
    }
}
