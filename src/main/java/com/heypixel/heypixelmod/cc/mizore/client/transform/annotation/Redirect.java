package com.heypixel.heypixelmod.cc.mizore.client.transform.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Redirect {
    String method();
    String desc() default "";
    String targetOwner();
    String targetMethod();
    String targetDesc();
    int ordinal() default -1;
    boolean remap() default true;
}