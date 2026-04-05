package com.example;

import com.example.model.Calculator;
import com.example.model.Coord;
import com.example.model.Point;
import com.example.model.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RawitSampleTest {

    @Test
    void instanceInvoker() {
        Calculator calc = new Calculator();
        int result = calc.add().x(3).y(4).invoke();
        assertEquals(7, result);
    }

    @Test
    void staticInvoker() {
        int result = Calculator.multiply().a(3).b(4).invoke();
        assertEquals(12, result);
    }

    @Test
    void constructor() {
        Point p = Point.constructor().x(10).y(20).construct();
        assertEquals(10, p.getX());
        assertEquals(20, p.getY());
    }

    @Test
    void recordConstructor() {
        Coord c = Coord.constructor().lat(1).lon(2).construct();
        assertEquals(1, c.lat());
        assertEquals(2, c.lon());
    }

    @Test
    void getterStringField() {
        User user = new User("Alice", 30, true, true, true, List.of("admin"));
        assertEquals("Alice", user.getName());
    }

    @Test
    void getterIntField() {
        User user = new User("Bob", 25, false, false, false, List.of());
        assertEquals(25, user.getAge());
    }

    @Test
    void getterPrimitiveBooleanUsesIsPrefix() {
        User user = new User("Carol", 28, true, false, null, List.of());
        assertTrue(user.isActive());
    }

    @Test
    void getterPrimitiveBooleanWithIsPrefixKeepsName() {
        User user = new User("Dave", 35, false, true, null, List.of());
        assertTrue(user.isVerified());
    }

    @Test
    void getterBoxedBooleanUsesGetPrefix() {
        User user = new User("Eve", 40, false, false, true, List.of());
        assertEquals(true, user.getPremium());
    }

    @Test
    void getterStaticField() {
        new User("Test", 1, false, false, null, List.of());
        assertTrue(User.getInstanceCount() > 0);
    }

    @Test
    void getterGenericListField() {
        User user = new User("Frank", 22, false, false, null, List.of("user", "editor"));
        assertEquals(List.of("user", "editor"), user.getRoles());
    }
}
