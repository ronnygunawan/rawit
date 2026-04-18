package com.example.model;

import rawit.Constructor;

/**
 * Demonstrates @Constructor combined with tag annotations on record components.
 * The generated builder chain propagates tag annotations onto stage method parameters,
 * enabling compile-time tag safety through the fluent API.
 */
@Constructor
public record TaggedUser(@UserId long userId, @FirstName String firstName, @LastName String lastName) { }
