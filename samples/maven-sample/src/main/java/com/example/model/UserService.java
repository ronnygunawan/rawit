package com.example.model;

import rawit.Getter;
import rawit.Invoker;

/**
 * Demonstrates @Invoker with tagged method parameters and @Getter with tagged fields.
 */
public class UserService {

    @Getter @FirstName private String currentFirstName;
    @Getter @LastName private String currentLastName;

    @Invoker
    public String formatName(@FirstName String firstName, @LastName String lastName) {
        this.currentFirstName = firstName;
        this.currentLastName = lastName;
        return firstName + " " + lastName;
    }
}
