package com.heypixel.heypixelmod.cc.mizore.client.transform.callback;

public class CallbackInfo {
    private final String name;
    private final boolean cancellable;
    private boolean cancelled;

    public CallbackInfo(String name, boolean cancellable) {
        this.name = name;
        this.cancellable = cancellable;
        this.cancelled = false;
    }

    public String getId() {
        return name;
    }

    public boolean isCancellable() {
        return cancellable;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        if (!cancellable) {
            throw new IllegalStateException("Callback is not cancellable");
        }
        this.cancelled = true;
    }
}
