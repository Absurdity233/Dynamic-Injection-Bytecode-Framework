package com.heypixel.heypixelmod.cc.mizore.client.transform.callback;

public class Args {
    private final Object[] args;

    public Args(Object[] args) {
        this.args = args;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(int index) {
        return (T) args[index];
    }

    public <T> void set(int index, T value) {
        args[index] = value;
    }

    public int size() {
        return args.length;
    }

    public Object[] getAll() {
        return args;
    }
}
