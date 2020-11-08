package com.palaska.memo.example;

import com.palaska.memo.annotation.processor.Memoize;
import java.util.function.Supplier;

public final class Example {
    private final int num1;
    private final Supplier<String> strSupplier;

    public Example(int num1, Supplier<String> strSupplier) throws RuntimeException {
        this.num1 = num1;
        this.strSupplier = strSupplier;
    }

    @Memoize
    public int echo(int a) throws InterruptedException {
        Thread.sleep(1000);
        return num1;
    }

    @Memoize
    Supplier<Integer> sum(int a, int b) throws InterruptedException {
        System.out.println("Computing the sum..");
        Thread.sleep(2000);
        return () -> a + b + num1;
    }

    public String sayHello() {
        return "Hello";
    }

    @Deprecated
    public String sayHello2() {
        return "Hello";
    }

    public static void main(String[] args) {
        ExampleMemoized memoized = new ExampleMemoized(5, () -> "baris");
        try {
            System.out.println(memoized.sum(5, 6).get());
            System.out.println(memoized.sum(6, 7).get());
            System.out.println(memoized.sum(6, 7).get());
            System.out.println(memoized.sayHello());
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
