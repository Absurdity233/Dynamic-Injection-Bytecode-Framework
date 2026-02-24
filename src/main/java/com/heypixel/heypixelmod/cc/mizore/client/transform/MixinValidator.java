package com.heypixel.heypixelmod.cc.mizore.client.transform;

import com.heypixel.heypixelmod.cc.mizore.annotations.NativeStub;
import com.heypixel.heypixelmod.cc.mizore.client.transform.annotation.*;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;


@NativeStub
public final class MixinValidator {

    public static List<String> validateMixinClass(Class<?> mixinClass) {
        List<String> errors = new ArrayList<>();

        if (!mixinClass.isAnnotationPresent(Mixin.class) && !mixinClass.isAnnotationPresent(ClassHook.class)) {
            errors.add("Class must be annotated with @Mixin or @ClassHook");
            return errors;
        }

        for (Method m : mixinClass.getDeclaredMethods()) {
            if (isHookMethod(m)) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    errors.add("Hook method must be static: " + m.getName() + Type.getMethodDescriptor(m));
                }
                if (!Modifier.isPublic(m.getModifiers())) {
                    errors.add("Hook method must be public: " + m.getName());
                }
            }
        }

        for (Field f : mixinClass.getDeclaredFields()) {
            if (f.isAnnotationPresent(Final.class) && !f.isAnnotationPresent(Shadow.class)) {
                errors.add("@Final can only be used on @Shadow fields: " + f.getName());
            }
            if (f.isAnnotationPresent(Mutable.class) && !f.isAnnotationPresent(Shadow.class)) {
                errors.add("@Mutable can only be used on @Shadow fields: " + f.getName());
            }
        }

        return errors;
    }

    private static boolean isHookMethod(Method m) {
        return m.isAnnotationPresent(Inject.class)
                || m.isAnnotationPresent(Overwrite.class)
                || m.isAnnotationPresent(Redirect.class)
                || m.isAnnotationPresent(ModifyConstant.class)
                || m.isAnnotationPresent(ModifyArg.class)
                || m.isAnnotationPresent(ModifyArgs.class)
                || m.isAnnotationPresent(ModifyVariable.class)
                || m.isAnnotationPresent(ModifyExpressionValue.class)
                || m.isAnnotationPresent(ModifyReturnValue.class)
                || m.isAnnotationPresent(WrapOperation.class)
                || m.isAnnotationPresent(WrapWithCondition.class)
                || m.isAnnotationPresent(MethodHook.class)
                || m.isAnnotationPresent(Accessor.class)
                || m.isAnnotationPresent(Invoker.class);
    }

    public static String validateInjectSignature(Method hook, String targetMethodDesc, boolean hasReturn) {
        Class<?>[] params = hook.getParameterTypes();
        if (params.length == 0) {
            return "Inject method must have at least CallbackInfo/CallbackInfoReturnable parameter";
        }
        Class<?> last = params[params.length - 1];
        if (hasReturn) {
            if (!com.heypixel.heypixelmod.cc.mizore.client.transform.callback.CallbackInfoReturnable.class.isAssignableFrom(last)) {
                return "Target returns value but hook last parameter is not CallbackInfoReturnable: " + hook.getName();
            }
        } else {
            if (!com.heypixel.heypixelmod.cc.mizore.client.transform.callback.CallbackInfo.class.isAssignableFrom(last)) {
                return "Target returns void but hook last parameter is not CallbackInfo: " + hook.getName();
            }
        }
        return null;
    }
}
