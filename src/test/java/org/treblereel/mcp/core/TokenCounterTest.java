package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TokenCounterTest {

    @Test
    void countsTokensForSimpleText() {
        int tokens = TokenCounter.count("Hello, world!");
        assertTrue(tokens > 0);
        assertTrue(tokens < 10);
    }

    @Test
    void emptyStringReturnsZero() {
        assertEquals(0, TokenCounter.count(""));
        assertEquals(0, TokenCounter.count(null));
    }

    @Test
    void javaCodeProducesReasonableCount() {
        String code = """
                package org.acme;
                import jakarta.enterprise.context.ApplicationScoped;
                @ApplicationScoped
                public class OrderService {
                    public void createOrder() {}
                }
                """;
        int tokens = TokenCounter.count(code);
        assertTrue(tokens > 10 && tokens < 100, "Expected 10-100 tokens, got " + tokens);
    }
}
