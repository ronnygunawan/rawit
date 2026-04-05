package com.example;

import com.example.constructor.Point;
import com.example.invoker.Calculator;
import com.example.record.Coord;
import org.junit.jupiter.api.Test;

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
}
