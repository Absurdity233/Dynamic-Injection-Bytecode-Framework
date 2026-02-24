package com.heypixel.heypixelmod.cc.mizore.client.transform;

import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.*;
import com.heypixel.heypixelmod.cc.mizore.client.transform.hook.*;
import com.heypixel.heypixelmod.cc.mizore.client.transform.injection.selector.InjectionPointSelector;
import com.heypixel.heypixelmod.cc.mizore.client.transform.remapper.McpRemapper;
import com.heypixel.heypixelmod.cc.mizore.client.utils.asm.AsmUtils;
import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@NativeStub
public class MixinProcessor implements ITransformer, Opcodes {

    private static final int CACHE_MAX_SIZE = 256;
    private static final long CACHE_EXPIRE_MS = 30 * 60 * 1000;
    private static final int CACHE_EVICT_BATCH = 32;
    private static final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final AtomicLong hits = new AtomicLong(0);
    private static final AtomicLong misses = new AtomicLong(0);

    private static volatile boolean debugEnabled = false;
    private static volatile boolean warningsEnabled = true;

    private static final InjectionPointSelector SHARED_SELECTOR = new InjectionPointSelector();
    private static volatile boolean remapperInitialized = false;
    private static final ThreadLocal<Integer> lastAppliedCount = ThreadLocal.withInitial(() -> 0);

    private final Class<?> mixinClass;
    private final String targetClassName;
    private final String deobfTargetClassName;
    private final int priority;
    private final boolean classRemap;
    private final HookContext hookContext;
    private final Map<String, List<HookEntry>> hooks;
    private final Map<String, List<HookEntry>> legacyHooks;
    private final List<ShadowField> shadowFields;
    private final List<ShadowMethod> shadowMethods;
    private final List<AccessorEntry> accessors;
    private final List<InvokerEntry> invokers;

    public static void setDebugEnabled(boolean enabled) { debugEnabled = enabled; }
    public static void setWarningsEnabled(boolean enabled) { warningsEnabled = enabled; }
    public static boolean isDebugEnabled() { return debugEnabled; }

    private void debug(String msg) {
        if (debugEnabled) System.out.println("[Mixin:" + mixinClass.getSimpleName() + "] " + msg);
    }

    private void warn(String msg) {
        if (warningsEnabled) System.err.println("[Mixin:" + mixinClass.getSimpleName() + "] WARN: " + msg);
    }

    private void error(String msg, Throwable t) {
        System.err.println("[Mixin:" + mixinClass.getSimpleName() + "] ERROR: " + msg);
        if (debugEnabled && t != null) t.printStackTrace();
    }

    private static class CacheKey {
        final String className;
        final int bytecodeHash;
        final int bytecodeLength;

        CacheKey(String className, byte[] bytecode) {
            this.className = className;
            this.bytecodeHash = Arrays.hashCode(bytecode);
            this.bytecodeLength = bytecode.length;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey other)) return false;
            return bytecodeHash == other.bytecodeHash
                    && bytecodeLength == other.bytecodeLength
                    && Objects.equals(className, other.className);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Objects.hashCode(className) + bytecodeHash) + bytecodeLength;
        }
    }

    private static class CacheEntry {
        final byte[] data;
        volatile long lastAccess;
        volatile int accessCount;

        CacheEntry(byte[] data) {
            this.data = data;
            this.lastAccess = System.currentTimeMillis();
            this.accessCount = 1;
        }

        void touch() {
            this.lastAccess = System.currentTimeMillis();
            this.accessCount++;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - lastAccess > CACHE_EXPIRE_MS;
        }
    }

    private record ShadowField(Field field, String targetName, boolean isFinal, boolean isMutable) {}
    private record ShadowMethod(Method method, String targetName, String targetDesc) {}
    private record AccessorEntry(Method method, String targetField, boolean isGetter) {}
    private record InvokerEntry(Method method, String targetMethod, String targetDesc) {}
    private record HookEntry(Method method, Annotation annotation, Slice slice) {
        HookEntry(Method method, Annotation annotation) {
            this(method, annotation, null);
        }
    }

    public MixinProcessor(Class<?> mixinClass) {
        this.mixinClass = mixinClass;

        Mixin mixin = mixinClass.getAnnotation(Mixin.class);
        ClassHook classHook = mixinClass.getAnnotation(ClassHook.class);

        if (mixin == null && classHook == null) {
            throw new IllegalArgumentException("Class " + mixinClass.getName() + " is not annotated with @Mixin or @ClassHook");
        }

        ensureRemapperInitialized();

        String rawName;
        if (mixin != null) {
            if (mixin.target() != Void.class) {
                rawName = mixin.target().getName().replace('.', '/');
            } else if (mixin.value() != null && !mixin.value().isEmpty()) {
                rawName = mixin.value().replace('.', '/');
            } else {
                throw new IllegalArgumentException("@Mixin on " + mixinClass.getName() + " must specify either target() or value()");
            }
            this.priority = mixin.priority();
            this.classRemap = mixin.remap();
        } else {
            rawName = classHook.value().replace('.', '/');
            this.priority = 1000;
            this.classRemap = true;
        }

        this.deobfTargetClassName = rawName;
        this.targetClassName = classRemap ? McpRemapper.getInstance().remapClass(rawName) : rawName;
        this.hookContext = new HookContext(mixinClass, deobfTargetClassName, classRemap, SHARED_SELECTOR,
                () -> debugEnabled, () -> warningsEnabled);

        this.hooks = new HashMap<>();
        this.legacyHooks = new HashMap<>();
        this.shadowFields = new ArrayList<>();
        this.shadowMethods = new ArrayList<>();
        this.accessors = new ArrayList<>();
        this.invokers = new ArrayList<>();

        scanFields();
        scanMethods();

        List<String> validationErrors = MixinValidator.validateMixinClass(mixinClass);
        if (!validationErrors.isEmpty()) {
            String msg = "Mixin validation failed for " + mixinClass.getSimpleName() + ": " + String.join("; ", validationErrors);
            error(msg, null);
            throw new IllegalArgumentException(msg);
        }

        debug("Registered mixin for " + deobfTargetClassName + " -> " + targetClassName);
        debug("  Hooks: " + hooks.size() + ", Accessors: " + accessors.size() + ", Invokers: " + invokers.size());
    }

    private static void ensureRemapperInitialized() {
        if (!remapperInitialized) {
            synchronized (MixinProcessor.class) {
                if (!remapperInitialized) {
                    McpRemapper.getInstance().init();
                    remapperInitialized = true;
                }
            }
        }
    }

    private void scanFields() {
        McpRemapper remapper = McpRemapper.getInstance();
        for (Field field : mixinClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Shadow.class)) {
                Shadow shadow = field.getAnnotation(Shadow.class);
                String targetName = shadow.aliases().length > 0 ? shadow.aliases()[0] : field.getName();
                boolean remap = classRemap && shadow.remap();
                String mappedName = remap ? remapper.remapField(deobfTargetClassName, targetName) : targetName;
                boolean isFinal = field.isAnnotationPresent(Final.class);
                boolean isMutable = field.isAnnotationPresent(Mutable.class);
                shadowFields.add(new ShadowField(field, mappedName, isFinal, isMutable));
            }
        }
    }

    private void scanMethods() {
        for (Method method : mixinClass.getDeclaredMethods()) {
            processShadow(method);
            processAccessor(method);
            processInvoker(method);
            processAnnotations(method);
        }
    }

    private void processShadow(Method method) {
        if (!method.isAnnotationPresent(Shadow.class)) return;
        Shadow shadow = method.getAnnotation(Shadow.class);
        String targetName = shadow.aliases().length > 0 ? shadow.aliases()[0] : method.getName();
        String desc = Type.getMethodDescriptor(method);
        boolean remap = classRemap && shadow.remap();
        McpRemapper remapper = McpRemapper.getInstance();
        String mappedName = remap ? remapper.remapMethod(deobfTargetClassName, targetName, desc) : targetName;
        String mappedDesc = remap ? McpRemapper.remapDescStatic(desc, true) : desc;
        shadowMethods.add(new ShadowMethod(method, mappedName, mappedDesc));
    }

    private void processAccessor(Method method) {
        if (!method.isAnnotationPresent(Accessor.class)) return;
        Accessor accessor = method.getAnnotation(Accessor.class);

        String methodName = method.getName();
        String fieldName = accessor.value();

        if (fieldName.isEmpty()) {
            if (methodName.startsWith("get") || methodName.startsWith("is")) {
                fieldName = methodName.startsWith("get") ? methodName.substring(3) : methodName.substring(2);
                fieldName = Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1);
            } else if (methodName.startsWith("set")) {
                fieldName = methodName.substring(3);
                fieldName = Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1);
            }
        }

        boolean remap = classRemap && accessor.remap();
        String mappedField = remap ? McpRemapper.getInstance().remapField(deobfTargetClassName, fieldName) : fieldName;
        boolean isGetter = method.getParameterCount() == 0;
        accessors.add(new AccessorEntry(method, mappedField, isGetter));
    }

    private void processInvoker(Method method) {
        if (!method.isAnnotationPresent(Invoker.class)) return;
        Invoker invoker = method.getAnnotation(Invoker.class);

        String methodName = method.getName();
        String targetMethod = invoker.value();

        if (targetMethod.isEmpty()) {
            if (methodName.startsWith("invoke") || methodName.startsWith("call")) {
                targetMethod = methodName.startsWith("invoke") ? methodName.substring(6) : methodName.substring(4);
                targetMethod = Character.toLowerCase(targetMethod.charAt(0)) + targetMethod.substring(1);
            }
        }

        String desc = Type.getMethodDescriptor(method);
        boolean remap = classRemap && invoker.remap();
        McpRemapper remapper = McpRemapper.getInstance();
        String mappedMethod = remap ? remapper.remapMethod(deobfTargetClassName, targetMethod, desc) : targetMethod;
        String mappedDesc = remap ? McpRemapper.remapDescStatic(desc, true) : desc;
        invokers.add(new InvokerEntry(method, mappedMethod, mappedDesc));
    }

    private void processAnnotations(Method method) {
        Slice slice = method.getAnnotation(Slice.class);

        if (method.isAnnotationPresent(Inject.class)) {
            Inject inject = method.getAnnotation(Inject.class);
            for (String target : inject.method()) {
                String[] parts = parseMethodSignature(target);
                addHook(parts[0], parts[1], new HookEntry(method, inject, slice));
            }
        }
        if (method.isAnnotationPresent(Overwrite.class)) {
            Overwrite overwrite = method.getAnnotation(Overwrite.class);
            addHook(overwrite.method(), overwrite.desc(), new HookEntry(method, overwrite, slice));
        }
        if (method.isAnnotationPresent(Redirect.class)) {
            Redirect redirect = method.getAnnotation(Redirect.class);
            addHook(redirect.method(), redirect.desc(), new HookEntry(method, redirect, slice));
        }
        if (method.isAnnotationPresent(ModifyConstant.class)) {
            ModifyConstant mc = method.getAnnotation(ModifyConstant.class);
            addHook(mc.method(), mc.desc(), new HookEntry(method, mc, slice));
        }
        if (method.isAnnotationPresent(ModifyArg.class)) {
            ModifyArg ma = method.getAnnotation(ModifyArg.class);
            for (String target : ma.method()) {
                String[] parts = parseMethodSignature(target);
                addHook(parts[0], parts[1], new HookEntry(method, ma, slice));
            }
        }
        if (method.isAnnotationPresent(ModifyArgs.class)) {
            ModifyArgs ma = method.getAnnotation(ModifyArgs.class);
            for (String target : ma.method()) {
                String[] parts = parseMethodSignature(target);
                addHook(parts[0], parts[1], new HookEntry(method, ma, slice));
            }
        }
        if (method.isAnnotationPresent(ModifyVariable.class)) {
            ModifyVariable mv = method.getAnnotation(ModifyVariable.class);
            for (String target : mv.method()) {
                String[] parts = parseMethodSignature(target);
                addHook(parts[0], parts[1], new HookEntry(method, mv, slice));
            }
        }
        if (method.isAnnotationPresent(ModifyExpressionValue.class)) {
            ModifyExpressionValue mev = method.getAnnotation(ModifyExpressionValue.class);
            for (String target : mev.method()) {
                String[] parts = parseMethodSignature(target);
                addHook(parts[0], parts[1], new HookEntry(method, mev, slice));
            }
        }
        if (method.isAnnotationPresent(ModifyReturnValue.class)) {
            ModifyReturnValue mrv = method.getAnnotation(ModifyReturnValue.class);
            for (String target : mrv.method()) {
                String[] parts = parseMethodSignature(target);
                addHook(parts[0], parts[1], new HookEntry(method, mrv, slice));
            }
        }
        if (method.isAnnotationPresent(WrapOperation.class)) {
            WrapOperation wo = method.getAnnotation(WrapOperation.class);
            for (String target : wo.method()) {
                String[] parts = parseMethodSignature(target);
                addHook(parts[0], parts[1], new HookEntry(method, wo, slice));
            }
        }
        if (method.isAnnotationPresent(WrapWithCondition.class)) {
            WrapWithCondition wwc = method.getAnnotation(WrapWithCondition.class);
            for (String target : wwc.method()) {
                String[] parts = parseMethodSignature(target);
                addHook(parts[0], parts[1], new HookEntry(method, wwc, slice));
            }
        }
        if (method.isAnnotationPresent(MethodHook.class)) {
            MethodHook mh = method.getAnnotation(MethodHook.class);
            String[] parts = parseMethodSignature(mh.method());
            addLegacyHook(parts[0], parts[1], new HookEntry(method, mh));
        }
    }

    private String[] parseMethodSignature(String sig) {
        int idx = sig.indexOf('(');
        if (idx == -1) return new String[]{sig, ""};
        return new String[]{sig.substring(0, idx), sig.substring(idx)};
    }

    private void addHook(String name, String desc, HookEntry entry) {
        boolean remap = shouldRemap(entry.annotation);
        McpRemapper remapper = McpRemapper.getInstance();
        String mappedName;
        String mappedDesc;

        if (desc == null || desc.isEmpty()) {
            mappedName = remap ? remapper.remapMethod(deobfTargetClassName, name, null) : name;
            mappedDesc = "";
        } else {
            mappedName = remap ? remapper.remapMethod(deobfTargetClassName, name, desc) : name;
            mappedDesc = remap ? McpRemapper.remapDescStatic(desc, true) : desc;
        }
        hooks.computeIfAbsent(mappedName + mappedDesc, k -> new ArrayList<>()).add(entry);
    }

    private void addLegacyHook(String name, String desc, HookEntry entry) {
        McpRemapper remapper = McpRemapper.getInstance();
        String mappedName = remapper.remapMethod(deobfTargetClassName, name, desc);
        String mappedDesc = McpRemapper.remapDescStatic(desc, true);
        legacyHooks.computeIfAbsent(mappedName + mappedDesc, k -> new ArrayList<>()).add(entry);
    }

    private boolean shouldRemap(Annotation ann) {
        if (!classRemap) return false;
        if (ann instanceof Inject i) return i.remap();
        if (ann instanceof Redirect r) return r.remap();
        if (ann instanceof ModifyConstant) return true;
        if (ann instanceof ModifyArg ma) return ma.remap();
        if (ann instanceof ModifyArgs ma) return ma.remap();
        if (ann instanceof ModifyVariable mv) return mv.remap();
        if (ann instanceof ModifyExpressionValue mev) return mev.remap();
        if (ann instanceof ModifyReturnValue mrv) return mrv.remap();
        if (ann instanceof WrapOperation wo) return wo.remap();
        if (ann instanceof WrapWithCondition wwc) return wwc.remap();
        return true;
    }

    @Override
    public String getTargetClass() {
        return targetClassName;
    }

    /** Exposes the mixin class for status reporting (e.g. which mixin applied). */
    public Class<?> getMixinClass() {
        return mixinClass;
    }

    public int getPriority() {
        return priority;
    }
    
    public int getHookCount() {
        int count = 0;
        for (List<HookEntry> entries : hooks.values()) {
            count += entries.size();
        }
        for (List<HookEntry> entries : legacyHooks.values()) {
            count += entries.size();
        }
        return count;
    }

    @Override
    public byte[] transform(byte[] basicClass, ClassLoader classLoader) {
        if (basicClass == null) return null;

        CacheKey key = new CacheKey(targetClassName, basicClass);
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            cached.touch();
            hits.incrementAndGet();
            lastAppliedCount.set(0);
            return cached.data;
        }
        misses.incrementAndGet();

        try {
            ClassNode cn = AsmUtils.toClassNode(basicClass);
            boolean modified = false;
            int appliedCount = 0;

            Map<String, FieldNode> fieldByName = cn.fields.stream().collect(Collectors.toMap(f -> f.name, f -> f, (a, b) -> a));
            Map<String, MethodNode> methodByKey = cn.methods.stream().collect(Collectors.toMap(m -> m.name + m.desc, m -> m, (a, b) -> a));

            modified |= applyAccessors(cn, fieldByName);
            modified |= applyInvokers(cn, methodByKey);
            modified |= applyShadows(cn, fieldByName, methodByKey);

            for (MethodNode mn : cn.methods) {
                String fullKey = mn.name + mn.desc;
                List<HookEntry> entries = hooks.get(fullKey);
                if (entries != null) {
                    for (HookEntry entry : entries) {
                        boolean result = applyHook(cn, mn, entry);
                        if (result) appliedCount++;
                        modified |= result;
                    }
                }
                List<HookEntry> nameOnlyEntries = hooks.get(mn.name);
                if (nameOnlyEntries != null) {
                    for (HookEntry entry : nameOnlyEntries) {
                        boolean result = applyHook(cn, mn, entry);
                        if (result) appliedCount++;
                        modified |= result;
                    }
                }
                List<HookEntry> legacyEntries = legacyHooks.get(fullKey);
                if (legacyEntries != null) {
                    for (HookEntry entry : legacyEntries) {
                        boolean result = applyLegacyHook(cn, mn, entry);
                        if (result) appliedCount++;
                        modified |= result;
                    }
                }
            }

            lastAppliedCount.set(appliedCount);

            if (modified) {
                debug("Transformed " + cn.name + " (" + appliedCount + " injections applied)");

                if (cn.sourceFile == null || cn.sourceFile.isEmpty()) {
                    String simpleName = cn.name;
                    int slash = simpleName.lastIndexOf('/');
                    if (slash >= 0 && slash < simpleName.length() - 1) {
                        simpleName = simpleName.substring(slash + 1);
                    }
                    cn.sourceFile = simpleName + ".java";
                }

                byte[] result = AsmUtils.toBytes(cn, classLoader);
                putCache(key, result);
                return result;
            }
        } catch (Throwable t) {
            lastAppliedCount.set(0);
            error("Transform failed for " + targetClassName, t);
        }
        return basicClass;
    }

    private boolean applyAccessors(ClassNode cn, Map<String, FieldNode> fieldByName) {
        if (accessors.isEmpty()) return false;
        boolean modified = false;

        for (AccessorEntry acc : accessors) {
            FieldNode targetField = fieldByName.get(acc.targetField);
            if (targetField == null) {
                warn("@Accessor target field not found: " + acc.targetField + " in " + cn.name);
                continue;
            }

            MethodNode method = new MethodNode(
                ACC_PUBLIC | ACC_SYNTHETIC,
                acc.method.getName(),
                Type.getMethodDescriptor(acc.method),
                null, null
            );

            InsnList insns = new InsnList();
            boolean isStatic = (targetField.access & ACC_STATIC) != 0;

            if (acc.isGetter) {
                if (!isStatic) insns.add(new VarInsnNode(ALOAD, 0));
                insns.add(new FieldInsnNode(isStatic ? GETSTATIC : GETFIELD, cn.name, targetField.name, targetField.desc));
                Type retType = Type.getType(targetField.desc);
                insns.add(new InsnNode(retType.getOpcode(IRETURN)));
            } else {
                if (!isStatic) insns.add(new VarInsnNode(ALOAD, 0));
                Type fieldType = Type.getType(targetField.desc);
                insns.add(new VarInsnNode(fieldType.getOpcode(ILOAD), isStatic ? 0 : 1));
                insns.add(new FieldInsnNode(isStatic ? PUTSTATIC : PUTFIELD, cn.name, targetField.name, targetField.desc));
                insns.add(new InsnNode(RETURN));
            }

            method.instructions = insns;
            cn.methods.add(method);
            modified = true;
        }

        return modified;
    }

    private boolean applyInvokers(ClassNode cn, Map<String, MethodNode> methodByKey) {
        if (invokers.isEmpty()) return false;
        boolean modified = false;

        for (InvokerEntry inv : invokers) {
            MethodNode targetMethod = methodByKey.get(inv.targetMethod + inv.targetDesc);
            if (targetMethod == null) {
                warn("@Invoker target method not found: " + inv.targetMethod + inv.targetDesc + " in " + cn.name);
                continue;
            }

            MethodNode method = new MethodNode(
                ACC_PUBLIC | ACC_SYNTHETIC,
                inv.method.getName(),
                Type.getMethodDescriptor(inv.method),
                null, null
            );

            InsnList insns = new InsnList();
            boolean isStatic = (targetMethod.access & ACC_STATIC) != 0;

            if (!isStatic) insns.add(new VarInsnNode(ALOAD, 0));

            int paramIdx = isStatic ? 0 : 1;
            for (Type t : Type.getArgumentTypes(targetMethod.desc)) {
                insns.add(new VarInsnNode(t.getOpcode(ILOAD), paramIdx));
                paramIdx += t.getSize();
            }

            int invokeOp = isStatic ? INVOKESTATIC : ((targetMethod.access & ACC_PRIVATE) != 0 ? INVOKESPECIAL : INVOKEVIRTUAL);
            insns.add(new MethodInsnNode(invokeOp, cn.name, targetMethod.name, targetMethod.desc, false));

            Type retType = Type.getReturnType(targetMethod.desc);
            insns.add(new InsnNode(retType.getOpcode(IRETURN)));

            method.instructions = insns;
            cn.methods.add(method);
            modified = true;
        }

        return modified;
    }

    private boolean applyShadows(ClassNode cn, Map<String, FieldNode> fieldByName, Map<String, MethodNode> methodByKey) {
        if (shadowFields.isEmpty() && shadowMethods.isEmpty()) return false;
        boolean modified = false;

        for (ShadowField sf : shadowFields) {
            FieldNode targetField = fieldByName.get(sf.targetName());
            if (targetField == null) {
                warn("@Shadow field not found: " + sf.targetName() + " in " + cn.name);
                continue;
            }

            if (sf.isMutable() && (targetField.access & ACC_FINAL) != 0) {
                targetField.access &= ~ACC_FINAL;
            }

            boolean isStatic = (targetField.access & ACC_STATIC) != 0;
            Type fieldType = Type.getType(targetField.desc);

            String getterName = "mizore$get$" + sf.targetName();
            StringBuilder getterDescBuilder = new StringBuilder("(");
            if (!isStatic) getterDescBuilder.append("L").append(cn.name).append(";");
            getterDescBuilder.append(")").append(targetField.desc);

            MethodNode getter = new MethodNode(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                    getterName, getterDescBuilder.toString(), null, null);
            InsnList getInsns = new InsnList();
            if (!isStatic) getInsns.add(new VarInsnNode(ALOAD, 0));
            getInsns.add(new FieldInsnNode(isStatic ? GETSTATIC : GETFIELD, cn.name, targetField.name, targetField.desc));
            getInsns.add(new InsnNode(fieldType.getOpcode(IRETURN)));
            getter.instructions = getInsns;
            cn.methods.add(getter);
            modified = true;

            boolean canWrite = !sf.isFinal() || sf.isMutable();
            if (canWrite) {
                String setterName = "mizore$set$" + sf.targetName();
                StringBuilder setterDescBuilder = new StringBuilder("(");
                if (!isStatic) setterDescBuilder.append("L").append(cn.name).append(";");
                setterDescBuilder.append(targetField.desc).append(")V");

                MethodNode setter = new MethodNode(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                        setterName, setterDescBuilder.toString(), null, null);
                InsnList setInsns = new InsnList();
                int valueIdx = isStatic ? 0 : 1;
                if (!isStatic) setInsns.add(new VarInsnNode(ALOAD, 0));
                setInsns.add(new VarInsnNode(fieldType.getOpcode(ILOAD), valueIdx));
                setInsns.add(new FieldInsnNode(isStatic ? PUTSTATIC : PUTFIELD, cn.name, targetField.name, targetField.desc));
                setInsns.add(new InsnNode(RETURN));
                setter.instructions = setInsns;
                cn.methods.add(setter);
            }

            debug("@Shadow: Generated accessors for " + sf.targetName() + " in " + cn.name +
                    (canWrite ? " (read/write)" : " (read-only)"));
        }

        for (ShadowMethod sm : shadowMethods) {
            MethodNode targetMethod = methodByKey.get(sm.targetName() + sm.targetDesc());
            if (targetMethod == null) {
                warn("@Shadow method not found: " + sm.targetName() + sm.targetDesc() + " in " + cn.name);
                continue;
            }

            boolean isStatic = (targetMethod.access & ACC_STATIC) != 0;
            Type retType = Type.getReturnType(targetMethod.desc);
            Type[] argTypes = Type.getArgumentTypes(targetMethod.desc);

            StringBuilder delegatorDescBuilder = new StringBuilder("(");
            if (!isStatic) delegatorDescBuilder.append("L").append(cn.name).append(";");
            for (Type t : argTypes) delegatorDescBuilder.append(t.getDescriptor());
            delegatorDescBuilder.append(")").append(retType.getDescriptor());

            String delegatorName = "mizore$call$" + sm.targetName();
            MethodNode delegator = new MethodNode(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                    delegatorName, delegatorDescBuilder.toString(), null, null);
            InsnList delInsns = new InsnList();
            int paramIdx = 0;
            if (!isStatic) {
                delInsns.add(new VarInsnNode(ALOAD, 0));
                paramIdx = 1;
            }
            for (Type t : argTypes) {
                delInsns.add(new VarInsnNode(t.getOpcode(ILOAD), paramIdx));
                paramIdx += t.getSize();
            }
            int invokeOp = isStatic ? INVOKESTATIC :
                    ((targetMethod.access & ACC_PRIVATE) != 0 ? INVOKESPECIAL : INVOKEVIRTUAL);
            delInsns.add(new MethodInsnNode(invokeOp, cn.name, targetMethod.name, targetMethod.desc, false));
            if (retType.getSort() == Type.VOID) {
                delInsns.add(new InsnNode(RETURN));
            } else {
                delInsns.add(new InsnNode(retType.getOpcode(IRETURN)));
            }
            delegator.instructions = delInsns;
            cn.methods.add(delegator);
            modified = true;

            debug("@Shadow: Generated delegator for " + sm.targetName() + " in " + cn.name);
        }

        return modified;
    }

    private boolean applyHook(ClassNode cn, MethodNode mn, HookEntry entry) {
        Annotation ann = entry.annotation;
        Method method = entry.method;
        Slice slice = entry.slice;

        if (ann instanceof Inject inject) return InjectHook.apply(hookContext, cn, mn, method, inject, slice);
        if (ann instanceof Overwrite) return OverwriteHook.apply(hookContext, cn, mn, method);
        if (ann instanceof Redirect redirect) return RedirectHook.apply(hookContext, cn, mn, method, redirect, slice);
        if (ann instanceof ModifyConstant mc) return ModifyConstantHook.apply(hookContext, cn, mn, method, mc, slice);
        if (ann instanceof ModifyArg ma) return ModifyArgHook.apply(hookContext, cn, mn, method, ma, slice);
        if (ann instanceof ModifyArgs ma) return ModifyArgsHook.apply(hookContext, cn, mn, method, ma, slice);
        if (ann instanceof ModifyVariable mv) return ModifyVariableHook.apply(hookContext, cn, mn, method, mv, slice);
        if (ann instanceof ModifyExpressionValue mev) return ModifyExpressionValueHook.apply(hookContext, cn, mn, method, mev, slice);
        if (ann instanceof ModifyReturnValue mrv) return ModifyReturnValueHook.apply(hookContext, cn, mn, method, mrv, slice);
        if (ann instanceof WrapOperation wo) return WrapOperationHook.apply(hookContext, cn, mn, method, wo, slice);
        if (ann instanceof WrapWithCondition wwc) return WrapWithConditionHook.apply(hookContext, cn, mn, method, wwc, slice);

        return false;
    }

    private boolean applyLegacyHook(ClassNode cn, MethodNode mn, HookEntry entry) {
        if (entry.annotation instanceof MethodHook mh) {
            return LegacyInjectHook.apply(hookContext, cn, mn, entry.method, mh.at());
        }
        return false;
    }

    private static void putCache(CacheKey key, byte[] data) {
        if (cache.size() >= CACHE_MAX_SIZE) evictCache();
        cache.put(key, new CacheEntry(data));
    }

    private static void evictCache() {
        cache.entrySet().removeIf(e -> e.getValue().isExpired());
        if (cache.size() >= CACHE_MAX_SIZE) {
            int toRemove = Math.min(CACHE_EVICT_BATCH, cache.size() / 4);
            for (int i = 0; i < toRemove; i++) {
                CacheKey victim = null;
                int minAccess = Integer.MAX_VALUE;
                for (Map.Entry<CacheKey, CacheEntry> e : cache.entrySet()) {
                    int c = e.getValue().accessCount;
                    if (c < minAccess) {
                        minAccess = c;
                        victim = e.getKey();
                    }
                }
                if (victim != null) cache.remove(victim);
            }
        }
    }

    public static void clearCache() {
        cache.clear();
        hits.set(0);
        misses.set(0);
    }

    public static String getCacheStats() {
        long h = hits.get(), m = misses.get(), t = h + m;
        double rate = t > 0 ? (h * 100.0 / t) : 0;
        return String.format("Cache: size=%d, hits=%d, misses=%d, rate=%.1f%%", cache.size(), h, m, rate);
    }

    public static int getAndClearLastAppliedCount() {
        int v = lastAppliedCount.get();
        lastAppliedCount.remove();
        return v;
    }
}
