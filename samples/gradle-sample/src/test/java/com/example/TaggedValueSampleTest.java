package com.example;

import com.example.model.FirstName;
import com.example.model.LastName;
import com.example.model.TaggedUser;
import com.example.model.UserId;
import com.example.model.UserService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaggedValueSampleTest {

    @Test
    void taggedConstructorWithLiterals() {
        TaggedUser user = TaggedUser.constructor()
                .userId(42)
                .firstName("John")
                .lastName("Doe")
                .construct();
        assertEquals(42, user.userId());
        assertEquals("John", user.firstName());
        assertEquals("Doe", user.lastName());
    }

    @Test
    void taggedConstructorWithTaggedVariables() {
        @UserId long id = 99;
        @FirstName String first = "Jane";
        @LastName String last = "Smith";
        TaggedUser user = TaggedUser.constructor()
                .userId(id)
                .firstName(first)
                .lastName(last)
                .construct();
        assertEquals(99, user.userId());
        assertEquals("Jane", user.firstName());
        assertEquals("Smith", user.lastName());
    }

    @Test
    void taggedInvokerChain() {
        UserService service = new UserService();
        @FirstName String first = "Alice";
        @LastName String last = "Wonder";
        String result = service.formatName().firstName(first).lastName(last).invoke();
        assertEquals("Alice Wonder", result);
    }

    @Test
    void taggedGetterOnFields() {
        UserService service = new UserService();
        service.formatName().firstName("Bob").lastName("Builder").invoke();
        assertEquals("Bob", service.getCurrentFirstName());
        assertEquals("Builder", service.getCurrentLastName());
    }

    @Test
    void taggedRecordAccessors() {
        TaggedUser user = TaggedUser.constructor()
                .userId(1)
                .firstName("Carol")
                .lastName("Danvers")
                .construct();
        assertEquals(1, user.userId());
        assertEquals("Carol", user.firstName());
        assertEquals("Danvers", user.lastName());
    }
}
