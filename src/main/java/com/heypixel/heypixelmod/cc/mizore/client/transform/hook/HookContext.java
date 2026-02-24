package com.heypixel.heypixelmod.cc.mizore.client.transform.hook;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.At;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.Slice;
import com.heypixel.heypixelmod.cc.mizore.client.transform.injection.selector.InjectionPointSelector;
import com.heypixel.heypixelmod.cc.mizore.client.transform.remapper.McpRemapper;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

@NativeStub
public class HookContext {
    
    private final Class<?> mixinClass;
    private final String deobfTargetClassName;
    private final boolean classRemap;
    private final InjectionPointSelector selector;
    private final BooleanSupplier debugSupplier;
    private final BooleanSupplier warningsSupplier;
    
    public HookContext(Class<?> mixinClass, String deobfTargetClassName, boolean classRemap, 
                       InjectionPointSelector selector, BooleanSupplier debugSupplier, BooleanSupplier warningsSupplier) {
        this.mixinClass = mixinClass;
        this.deobfTargetClassName = deobfTargetClassName;
        this.classRemap = classRemap;
        this.selector = selector;
        this.debugSupplier = debugSupplier;
        this.warningsSupplier = warningsSupplier;
    }
    
    public String getHookOwner() {
        return mixinClass.getName().replace('.', '/');
    }
    
    public Class<?> getMixinClass() {
        return mixinClass;
    }
    
    public String getDeobfTargetClassName() {
        return deobfTargetClassName;
    }
    
    public boolean isClassRemap() {
        return classRemap;
    }
    
    public InjectionPointSelector getSelector() {
        return selector;
    }
    
    public List<AbstractInsnNode> findInjectionPoints(MethodNode mn, At at) {
        return selector.find(mn, at, deobfTargetClassName);
    }
    
    public List<AbstractInsnNode> applySlice(MethodNode mn, List<AbstractInsnNode> targets, Slice slice) {
        if (slice == null) return targets;
        
        At from = slice.from();
        At to = slice.to();
        
        List<AbstractInsnNode> fromPoints = selector.find(mn, from, deobfTargetClassName);
        List<AbstractInsnNode> toPoints = selector.find(mn, to, deobfTargetClassName);
        
        if (fromPoints.isEmpty() || toPoints.isEmpty()) {
            if (warningsSupplier.getAsBoolean()) {
                warn("@Slice: from/to injection point not found (from=" + from.value() + ", to=" + to.value() + "), no targets");
            }
            return new ArrayList<>();
        }
        
        int fromIdx = mn.instructions.indexOf(fromPoints.get(0));
        int toIdx = mn.instructions.indexOf(toPoints.get(toPoints.size() - 1));
        
        List<AbstractInsnNode> filtered = new ArrayList<>();
        for (AbstractInsnNode node : targets) {
            int idx = mn.instructions.indexOf(node);
            if (idx >= fromIdx && idx <= toIdx) {
                filtered.add(node);
            }
        }
        return filtered;
    }
    
    public String remapClass(String className) {
        return classRemap ? McpRemapper.getInstance().remapClass(className) : className;
    }
    
    public String remapMethod(String owner, String name, String desc) {
        return classRemap ? McpRemapper.getInstance().remapMethod(owner, name, desc) : name;
    }
    
    public String remapDesc(String desc) {
        return McpRemapper.remapDescStatic(desc, classRemap);
    }
    
    public void debug(String msg) {
        if (debugSupplier.getAsBoolean()) {
            System.out.println("[Mixin:" + mixinClass.getSimpleName() + "] " + msg);
        }
    }
    
    public void warn(String msg) {
        if (warningsSupplier.getAsBoolean()) {
            System.err.println("[Mixin:" + mixinClass.getSimpleName() + "] WARN: " + msg);
        }
    }
    
    public void error(String msg, Throwable t) {
        System.err.println("[Mixin:" + mixinClass.getSimpleName() + "] ERROR: " + msg);
        if (debugSupplier.getAsBoolean() && t != null) {
            t.printStackTrace();
        }
    }
}
