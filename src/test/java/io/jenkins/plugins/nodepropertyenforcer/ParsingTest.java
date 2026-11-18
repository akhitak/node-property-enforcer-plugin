package io.jenkins.plugins.nodepropertyenforcer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ParsingTest {
    @Test
    void acceptsOrdinaryBoundedObjects() throws Exception {
        assertEquals("agent", SubmittedProperties.parse("{\"name\":\"agent\"}").getString("name"));
    }

    @Test
    void rejectsMalformedDuplicateDeepAndOversizedInput() {
        for (String json : new String[] {
            null,
            "",
            "[]",
            "null",
            "true",
            "{",
            "{}{}",
            "{\"a\":1,\"a\":2}",
            "{\"a\":{\"b\":1,\"b\":2}}",
            "{\"x\":".repeat(70) + "{}" + "}".repeat(70),
            " ".repeat(1_048_577)
        }) {
            assertThrows(IOException.class, () -> SubmittedProperties.parse(json));
        }
    }
}
