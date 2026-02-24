package com.heypixel.heypixelmod.cc.mizore.client.transform.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModifyConstant {
    String method();
    String desc() default "";

    float floatValue() default 0.0f;
    double doubleValue() default 0.0d;
    int intValue() default 0;
    String stringValue() default "";

    int ordinal() default -1;
    String constType() default "";
}
