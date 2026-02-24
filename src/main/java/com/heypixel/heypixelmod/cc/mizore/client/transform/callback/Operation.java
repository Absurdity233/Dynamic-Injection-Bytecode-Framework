package com.heypixel.heypixelmod.cc.mizore.client.transform.callback;

@FunctionalInterface
public interface Operation<R> {
    R call(Object... args);

    @SuppressWarnings("unchecked")
    default <T> T callWithArgs(Object... args) {
        return (T) call(args);
    }

    static <R> Operation<R> of(Operation<R> op) {
        return op;
    }

    interface VoidOperation {
        void call(Object... args);
    }

    static Operation<Void> ofVoid(VoidOperation op) {
        return args -> {
            op.call(args);
            return null;
        };
    }
}
