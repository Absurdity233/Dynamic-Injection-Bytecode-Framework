package com.heypixel.heypixelmod.cc.mizore.client.transform.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Slice {
    String id() default "";
    At from() default @At("HEAD");
    At to() default @At("TAIL");
}
