package com.heypixel.heypixelmod.cc.mizore.client.transform;

import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.ClassHook;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Mixin;
import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.impl.jvm.DllScanDynamicTransformer;
import com.heypixel.heypixelmod.cc.mizore.client.transform.impl.jvm.JarScanDynamicTransformer;
import com.heypixel.heypixelmod.cc.mizore.client.transform.impl.jvm.JmapDynamicTransformer;
import com.heypixel.heypixelmod.cc.mizore.client.transform.impl.jvm.ThreadScanDynamicTransformer;
import com.heypixel.heypixelmod.cc.mizore.client.utils.bypass.InjectionLogger;
import com.heypixel.heypixelmod.cc.mizore.client.transform.impl.mixins.core.*;
import com.heypixel.heypixelmod.cc.mizore.client.transform.impl.mixins.entity.*;
import com.heypixel.heypixelmod.cc.mizore.client.transform.impl.mixins.gui.*;
import com.heypixel.heypixelmod.cc.mizore.client.transform.impl.mixins.input.*;
import com.heypixel.heypixelmod.cc.mizore.client.transform.impl.mixins.item.*;
import com.heypixel.heypixelmod.cc.mizore.client.transform.impl.mixins.network.*;
import com.heypixel.heypixelmod.cc.mizore.client.transform.impl.mixins.render.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@NativeStub
public class EventTransformer implements ClassFileTransformer {
    private final Map<String, List<ITransformer>> transformers = new ConcurrentHashMap<>();
    private final Set<Class<?>> registeredMixins = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    private final Map<String, TransformStatus> transformStatusMap = new ConcurrentHashMap<>();
    private final Map<String, List<Class<?>>> targetToMixinsMap = new ConcurrentHashMap<>();

    private final AtomicInteger transformCount = new AtomicInteger(0);
    private final AtomicLong totalTransformTime = new AtomicLong(0);
    private volatile List<ITransformer> dynamicTransformers = Collections.emptyList();
    
    public enum TransformResult {
        PENDING,
        SUCCESS,
        FAILED,
        SKIPPED
    }
    
    public static class TransformStatus {
        public final String mixinClassName;
        public final String targetClassName;
        public volatile TransformResult result = TransformResult.PENDING;
        public volatile String errorMessage;
        public volatile long transformTimeNanos;
        public volatile int hookCount;
        
        public TransformStatus(String mixinClassName, String targetClassName) {
            this.mixinClassName = mixinClassName;
            this.targetClassName = targetClassName;
        }
        
        @Override
        public String toString() {
            String status = switch (result) {
                case SUCCESS -> "SUCCESS";
                case FAILED -> "FAILED: " + errorMessage;
                case PENDING -> "PENDING";
                case SKIPPED -> "SKIPPED";
            };
            return String.format("[%s] %s -> %s (%.2fms, %d hooks)", 
                status, mixinClassName, targetClassName, 
                transformTimeNanos / 1_000_000.0, hookCount);
        }
    }

    private static final Class<?>[] MIXIN_CLASSES = {
            // core
            MinecraftHooks.class,
            MultiPlayerGameModeHooks.class,
            TimerHooks.class,
            WebBlockHooks.class,

            // entity
            AbstractClientPlayerHooks.class,
            EntityHooks.class,
            LivingEntityHooks.class,
            LocalPlayerHooks.class,
            PlayerHooks.class,

            // gui
            BossHealthOverlayHooks.class,
            ChatComponentHooks.class,
            ChatComponentRenderHooks.class,
            DebugScreenOverlayHooks.class,
            GuiHooks.class,
            PlayerTabOverlayHooks.class,
            ScreenHooks.class,

            // input
            KeyboardHooks.class,
            KeyboardInputHooks.class,
            MouseHandlerHooks.class,

            // item
            BucketItemHooks.class,
            ItemHooks.class,

            // network
            ClientPacketListenerHooks.class,
            ConnectionHooks.class,
            NetworkFiltersHooks.class,

            // render
            EntityRendererHooks.class,
            ItemInHandLayerHooks.class,
            ItemInHandRendererHooks.class,
            LevelRendererHooks.class,
            LightTextureHooks.class,
            LivingEntityRendererHooks.class,
            OverlayHooks.class,
            RenderHooks.class,
    };


    public EventTransformer() {
        this(null);
    }
    public EventTransformer(Set<String> bypassTargets) {
        registerAllMixins();

        boolean hasTargets = bypassTargets != null && !bypassTargets.isEmpty();
        register(new JmapDynamicTransformer(hasTargets ? bypassTargets : null));
        register(new JarScanDynamicTransformer(hasTargets ? bypassTargets : null));
        register(new ThreadScanDynamicTransformer(hasTargets ? bypassTargets : null));
        register(new DllScanDynamicTransformer(hasTargets ? bypassTargets : null));
    }

    private void registerAllMixins() {
        for (Class<?> mixinClass : MIXIN_CLASSES) {
            registerMixin(mixinClass);
        }
    }

    private void register(ITransformer transformer) {
        Set<String> targets = transformer.getTargetClasses();
        if (targets != null && !targets.isEmpty()) {
            for (String target : targets) {
                transformers.computeIfAbsent(target, k -> new CopyOnWriteArrayList<>()).add(transformer);
            }
        } else if (transformer.getTargetClass() == null) {
            String dynamicKey = "__DYNAMIC__" + transformer.getClass().getName();
            transformers.computeIfAbsent(dynamicKey, k -> new CopyOnWriteArrayList<>()).add(transformer);
            rebuildDynamicTransformerList();
        } else {
            transformers.computeIfAbsent(transformer.getTargetClass(), k -> new CopyOnWriteArrayList<>()).add(transformer);
        }
    }

    private void rebuildDynamicTransformerList() {
        List<ITransformer> list = new ArrayList<>();
        for (List<ITransformer> bucket : transformers.values()) {
            for (ITransformer t : bucket) {
                if (t.getTargetClass() == null) list.add(t);
            }
        }
        dynamicTransformers = Collections.unmodifiableList(list);
    }

    private void registerMixin(Class<?> mixinClass) {
        if (registeredMixins.contains(mixinClass)) {
            return;
        }

        boolean hasMixin = mixinClass.isAnnotationPresent(Mixin.class);
        boolean hasClassHook = mixinClass.isAnnotationPresent(ClassHook.class);

        if (!hasMixin && !hasClassHook) {
            return;
        }

        try {
            MixinProcessor processor = new MixinProcessor(mixinClass);
            String targetClass = processor.getTargetClass();
            transformers.computeIfAbsent(targetClass, k -> new CopyOnWriteArrayList<>()).add(processor);
            registeredMixins.add(mixinClass);
            
            String mixinName = mixinClass.getSimpleName();
            TransformStatus status = new TransformStatus(mixinName, targetClass);
            status.hookCount = processor.getHookCount();
            transformStatusMap.put(mixinName, status);
            targetToMixinsMap.computeIfAbsent(targetClass, k -> new CopyOnWriteArrayList<>()).add(mixinClass);
            
            sortTransformers(targetClass);
        } catch (Exception e) {
            String mixinName = mixinClass.getSimpleName();
            TransformStatus status = new TransformStatus(mixinName, "UNKNOWN");
            status.result = TransformResult.FAILED;
            status.errorMessage = e.getMessage();
            transformStatusMap.put(mixinName, status);
            e.printStackTrace();
        }
    }

    private void sortTransformers(String targetClass) {
        List<ITransformer> list = transformers.get(targetClass);
        if (list == null || list.size() <= 1) return;

        if (list instanceof CopyOnWriteArrayList<ITransformer> cow) {
            List<ITransformer> sorted = new ArrayList<>(cow);
            sorted.sort(Comparator.comparingInt(this::getPriority));
            cow.clear();
            cow.addAll(sorted);
        }
    }

    private int getPriority(ITransformer transformer) {
        if (transformer instanceof MixinProcessor) {
            return ((MixinProcessor) transformer).getPriority();
        }
        return 1000;
    }

    public boolean registerDynamicMixin(Class<?> mixinClass) {
        if (registeredMixins.contains(mixinClass)) {
            return false;
        }
        registerMixin(mixinClass);
        return registeredMixins.contains(mixinClass);
    }

    public int getRegisteredMixinCount() {
        return registeredMixins.size();
    }


    public boolean hasDynamicTransformers() {
        return !dynamicTransformers.isEmpty();
    }

    public Set<String> getTargetClassNames() {
        return transformers.keySet();
    }

    public boolean isTargetClassName(String className) {
        if (className == null) return false;
        String name = className.replace('.', '/');
        if (transformers.containsKey(name)) return true;
        if (name.startsWith("com/heypixel/heypixelmod/cc/mizore")) return false;
        if (!dynamicTransformers.isEmpty() && isApplicationOrTargetJvmClass(name)) return true;
        return false;
    }

    private final Set<String> loggedHeypixelClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static boolean isApplicationOrTargetJvmClass(String internalName) {
        return internalName.startsWith("net/") || internalName.startsWith("com/");
    }


    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;

        String name = className.replace('.', '/');

        if (name.startsWith("com/heypixel/") && !name.startsWith("com/heypixel/heypixelmod/cc/mizore")
                && loggedHeypixelClasses.add(name)) {
            try {
                InjectionLogger.log("ClassLoadHook",
                        "com/heypixel class loaded/retransformed: " + name
                                + (classBeingRedefined != null ? " [RETRANSFORM]" : " [FIRST_LOAD]"));
            } catch (Throwable ignored) {}
        }

        byte[] currentBytes = classfileBuffer;
        boolean anyModified = false;

        List<ITransformer> list = transformers.get(name);
        if (list != null && !list.isEmpty()) {
            long startTime = System.nanoTime();
            try {
                byte[] result = currentBytes;
                boolean modified = false;

                for (ITransformer transformer : list) {
                    long hookStart = System.nanoTime();
                    byte[] transformed = transformer.transform(result, loader);
                    if (transformer instanceof MixinProcessor mp) {
                        if (transformed != null && transformed != result) {
                            result = transformed;
                            modified = true;
                            updateMixinStatus(mp, System.nanoTime() - hookStart, TransformResult.SUCCESS, null);
                        } else if (MixinProcessor.getAndClearLastAppliedCount() == 0) {
                            updateMixinStatus(mp, System.nanoTime() - hookStart, TransformResult.SKIPPED, "no hooks applied");
                        } else {
                            MixinProcessor.getAndClearLastAppliedCount();
                        }
                    } else if (transformed != null && transformed != result) {
                        result = transformed;
                        modified = true;
                    }
                }

                if (modified) {
                    long elapsed = System.nanoTime() - startTime;
                    transformCount.incrementAndGet();
                    totalTransformTime.addAndGet(elapsed);
                    currentBytes = result;
                    anyModified = true;
                }
            } catch (Throwable t) {
                String errMsg = (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                List<Class<?>> mixinClasses = targetToMixinsMap.get(name);
                if (mixinClasses != null) {
                    for (Class<?> mixinClass : mixinClasses) {
                        TransformStatus status = transformStatusMap.get(mixinClass.getSimpleName());
                        if (status != null && status.result == TransformResult.PENDING) {
                            status.result = TransformResult.FAILED;
                            status.errorMessage = errMsg;
                        }
                    }
                }
                t.printStackTrace();
            }
        }
        
        List<ITransformer> dynList = dynamicTransformers;
        if (!dynList.isEmpty()) {
            for (ITransformer dynTransformer : dynList) {
                if (!dynTransformer.mightApply(currentBytes)) continue;

                long startTime = System.nanoTime();
                try {
                    byte[] transformed = dynTransformer.transform(currentBytes, loader);
                    if (transformed != null && transformed != currentBytes) {
                        currentBytes = transformed;
                        anyModified = true;
                        long elapsed = System.nanoTime() - startTime;
                        transformCount.incrementAndGet();
                        totalTransformTime.addAndGet(elapsed);
                    }
                } catch (Throwable t) {
                    try {
                        InjectionLogger.log("DynamicTransform",
                                "EXCEPTION in " + dynTransformer.getClass().getSimpleName()
                                        + " for class " + name + ": " + t);
                    } catch (Throwable ignored) {}
                }
            }
        }
        
        return anyModified ? currentBytes : null;
    }
    
    private void updateMixinStatus(MixinProcessor mp, long elapsedNanos, TransformResult result, String error) {
        String mixinName = mp.getMixinClass().getSimpleName();
        TransformStatus status = transformStatusMap.get(mixinName);
        if (status != null && status.result == TransformResult.PENDING) {
            status.result = result;
            status.transformTimeNanos = elapsedNanos;
            status.hookCount = mp.getHookCount();
            if (error != null) status.errorMessage = error;
        }
    }

    public String getStats() {
        int count = transformCount.get();
        long totalTime = totalTransformTime.get();
        return String.format("Transforms: %d, Total time: %.2fms, Avg: %.2fms",
                count,
                totalTime / 1_000_000.0,
                count > 0 ? (totalTime / 1_000_000.0) / count : 0);
    }
    
    public Map<String, TransformStatus> getTransformStatusMap() {
        return Collections.unmodifiableMap(transformStatusMap);
    }
    
    public TransformStatus getTransformStatus(String mixinName) {
        return transformStatusMap.get(mixinName);
    }
    
    public String getTransformReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Mixin Transform Report ===\n");

        int success = 0, failed = 0, pending = 0;
        
        Map<String, List<TransformStatus>> grouped = new LinkedHashMap<>();
        grouped.put("core", new ArrayList<>());
        grouped.put("entity", new ArrayList<>());
        grouped.put("gui", new ArrayList<>());
        grouped.put("input", new ArrayList<>());
        grouped.put("item", new ArrayList<>());
        grouped.put("network", new ArrayList<>());
        grouped.put("render", new ArrayList<>());
        
        for (TransformStatus status : transformStatusMap.values()) {
            String group = categorizeByMixinName(status.mixinClassName);
            grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(status);
            
            switch (status.result) {
                case SUCCESS -> success++;
                case FAILED -> failed++;
                case PENDING -> pending++;
                case SKIPPED -> {}
            }
        }
        
        for (Map.Entry<String, List<TransformStatus>> entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            sb.append("\n[").append(entry.getKey().toUpperCase()).append("]\n");
            for (TransformStatus status : entry.getValue()) {
                sb.append("  ").append(status.toString()).append("\n");
            }
        }
        
        sb.append("\n=== Summary ===\n");
        sb.append(String.format("Total: %d | Success: %d | Failed: %d | Pending: %d\n",
            transformStatusMap.size(), success, failed, pending));
        sb.append(getStats()).append("\n");
        sb.append(MixinProcessor.getCacheStats()).append("\n");
        
        return sb.toString();
    }
    
    private String categorizeByMixinName(String mixinName) {
        if (mixinName.contains("Minecraft") || mixinName.contains("Timer") || 
            mixinName.contains("MultiPlayer") || mixinName.contains("Web")) {
            return "core";
        } else if (mixinName.contains("Entity") && !mixinName.contains("Renderer")) {
            return "entity";
        } else if (mixinName.contains("Packet") || mixinName.contains("Connection") || 
                   mixinName.equals("ChatHooks")) {
            return "network";
        } else if (mixinName.contains("Gui") || mixinName.contains("Screen") || 
                   mixinName.contains("ChatComponent") ||
                   mixinName.contains("Boss") || mixinName.contains("Tab") || 
                   mixinName.contains("Debug")) {
            return "gui";
        } else if (mixinName.contains("Keyboard") || mixinName.contains("Mouse") || 
                   mixinName.contains("Input")) {
            return "input";
        } else if (mixinName.contains("Item") || mixinName.contains("Bucket")) {
            return "item";
        } else if (mixinName.contains("Render") || mixinName.contains("Light") || 
                   mixinName.contains("Level") || mixinName.contains("Overlay")) {
            return "render";
        }
        return "other";
    }
    
    public void printTransformReport() {
        System.out.println(getTransformReport());
    }
}
