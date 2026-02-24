package com.heypixel.heypixelmod.cc.mizore.client.transform.callback;

public class CallbackInfoReturnable<R> extends CallbackInfo {
    private R returnValue;

    public CallbackInfoReturnable(String name, boolean cancellable) {
        super(name, cancellable);
    }

    public CallbackInfoReturnable(String name, boolean cancellable, R returnValue) {
        super(name, cancellable);
        this.returnValue = returnValue;
    }

    public R getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(R value) {
        this.returnValue = value;
        cancel();
    }
}
