package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SymbolContractTest {

    @Test
    void roundTripsJvmIdentityWithoutLosingUnicodeOrSpecialCharacters() {
        String id = SymbolContract.id("org.acme.Café$Companion", "METHOD",
                "`when`:impl", "(Ljava/lang/String;[Лмодель;)Ljava/lang/Object;");

        SymbolContract.Reference reference = SymbolContract.parse(id).orElseThrow();

        assertEquals("org.acme.Café$Companion", reference.className());
        assertEquals("METHOD", reference.kind());
        assertEquals("`when`:impl", reference.jvmName());
        assertEquals("(Ljava/lang/String;[Лмодель;)Ljava/lang/Object;", reference.descriptor());
        assertEquals("", reference.sourceFile());
    }

    @Test
    void returnsEmptyForOrdinaryTargets() {
        assertFalse(SymbolContract.isId("org.acme.OrderService"));
        assertTrue(SymbolContract.parse("org.acme.OrderService").isEmpty());
        assertTrue(SymbolContract.parse(null).isEmpty());
    }

    @Test
    void rejectsUnsupportedVersionsExplicitly() {
        for (String id : new String[] {
                "quill:symbol:v1:anything", "quill:symbol:v3:anything"}) {
            SymbolContract.ParseException error = assertThrows(
                    SymbolContract.ParseException.class,
                    () -> SymbolContract.parse(id));
            assertEquals("UNSUPPORTED_SYMBOL_ID_VERSION", error.errorCode());
            assertEquals("Unsupported symbol_id version", error.getMessage());
        }
    }

    @Test
    void rejectsMalformedShapeAndBase64() {
        assertMalformed("quill:symbol:v2:only:four:identity:parts");
        assertMalformed("quill:symbol:v2:not+url:method:name:descriptor:scope");
    }

    @Test
    void rejectsBlankOrUnknownIdentityComponents() {
        assertMalformed(rawId("", "method", "run", "()V"));
        assertMalformed(rawId("org.acme.Type", "unknown", "run", "()V"));
        assertMalformed(rawId("org.acme.Type", "method", "", "()V"));
        assertMalformed(rawId("org.acme.Type", "method", "run", ""));
        assertMalformed(rawId("org.acme.Type", "class", "", ""));
    }

    @Test
    void acceptsTypeIdentityWithoutDescriptor() {
        SymbolContract.Reference reference = SymbolContract.parse(
                SymbolContract.id("org.acme.Type", "CLASS", "org.acme.Type", ""))
                .orElseThrow();

        assertEquals("CLASS", reference.kind());
        assertEquals("", reference.descriptor());
    }

    private static void assertMalformed(String id) {
        SymbolContract.ParseException error = assertThrows(SymbolContract.ParseException.class,
                () -> SymbolContract.parse(id));
        assertEquals("MALFORMED_SYMBOL_ID", error.errorCode());
        assertEquals("Malformed symbol_id", error.getMessage());
    }

    private static String rawId(String className, String kind, String name, String descriptor) {
        return "quill:symbol:v2:" + encode(className) + ":" + kind + ":"
                + encode(name) + ":" + encode(descriptor) + ":";
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
