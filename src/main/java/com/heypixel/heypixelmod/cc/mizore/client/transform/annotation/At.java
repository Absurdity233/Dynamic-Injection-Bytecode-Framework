package com.heypixel.heypixelmod.cc.mizore.client.transform.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface At {
    String value();
    String target() default "";
    int ordinal() default -1;
    int opcode() default -1;
    Shift shift() default Shift.NONE;
    int by() default 0;
    boolean remap() default true;

    enum Shift {
        NONE, BEFORE, AFTER, BY
    }
}
