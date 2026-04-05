package com.example.model;

import rawit.Getter;

import java.util.List;

public class User {

    @Getter private String name;
    @Getter private int age;
    @Getter private boolean active;
    @Getter private boolean isVerified;
    @Getter private Boolean premium;
    @Getter private static int instanceCount;
    @Getter private List<String> roles;

    public User(String name, int age, boolean active, boolean isVerified,
                Boolean premium, List<String> roles) {
        this.name = name;
        this.age = age;
        this.active = active;
        this.isVerified = isVerified;
        this.premium = premium;
        this.roles = roles;
        instanceCount++;
    }
}
