package com.example.model;

import rawit.Invoker;

public class Calculator {

    @Invoker
    public int add(int x, int y) {
        return x + y;
    }

    @Invoker
    public static int multiply(int a, int b) {
        return a * b;
    }
}
