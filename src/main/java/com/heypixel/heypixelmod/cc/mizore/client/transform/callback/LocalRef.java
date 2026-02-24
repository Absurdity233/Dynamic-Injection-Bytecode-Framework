package com.heypixel.heypixelmod.cc.mizore.client.transform.callback;

public class LocalRef<T> {
    private T value;

    public LocalRef(T value) {
        this.value = value;
    }

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = value;
    }
}
