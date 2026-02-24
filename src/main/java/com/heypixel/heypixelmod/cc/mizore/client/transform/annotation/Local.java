package com.heypixel.heypixelmod.cc.mizore.client.transform.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Local {
    int index() default -1;
    int ordinal() default -1;
    String name() default "";
}
