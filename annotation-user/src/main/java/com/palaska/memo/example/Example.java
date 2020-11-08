package com.palaska.memo.example;

import com.palaska.memo.annotation.processor.Memoize;

import java.util.function.Supplier;

public final class Example {
    private final int num1;
    private final Supplier<String> strSupplier;

    protected Example(int num1, Supplier<String> strSupplier) throws RuntimeException {
        this.num1 = num1;
        this.strSupplier = strSupplier;
    }

    @Memoize
    public int echo(int a) throws InterruptedException {
        Thread.sleep(1000);
        return a;
    }

    @Memoize
    private int sum(int a, int b) throws InterruptedException {
        Thread.sleep(1000);
        return a + b;
    }
}
