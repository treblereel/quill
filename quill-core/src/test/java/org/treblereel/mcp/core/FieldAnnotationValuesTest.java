package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FieldAnnotationValuesTest {
    @TempDir Path root;
    private JsonNode capture(String fields) throws Exception {
        Path src=root.resolve("Fixture.java"),out=Files.createDirectories(root.resolve("classes"));
        Files.writeString(src,"""
            package p;import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @interface Meta {
              String name() default "implicit"; boolean enabled() default false;
              long count() default 0; Class<?> type() default Object.class;
              RetentionPolicy mode() default RetentionPolicy.SOURCE;
              Nested nested() default @Nested; int[] numbers() default {};
              String value() default "default"; String defaultValue() default "not explicit";
            }
            @Retention(RetentionPolicy.CLASS) @interface Nested {String value() default "nested-default";}
            class Fixture {
            """+fields+"\n}");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",out.toString(),src.toString()));
        var fieldsOnly=new ObjectMapper().createArrayNode();
        for(var member:new ObjectMapper().readTree(JvmApiScanner.capture(ClassFileSnapshot.capture(List.of(out)),Map.of("p.Fixture",1)).get(1)).path("members"))if(member.path("kind").asText().equals("FIELD"))fieldsOnly.add(member);
        return fieldsOnly;
    }
    @Test void capturesTypedExplicitValuesWithoutExecutingOrInventingDefaults() throws Exception {
        var members=capture("@Meta(name=\"literal-${not-evaluated}\",enabled=true,count=9223372036854775807L,type=String[].class,mode=RetentionPolicy.CLASS,nested=@Nested(\"inside\"),numbers={1,2}) boolean field; @Nested int other;");
        var captured=members.get(0).path("annotation_values");assertTrue(captured.path("complete").asBoolean());var attrs=captured.path("explicit").path("p.Meta");
        assertEquals("literal-${not-evaluated}",attrs.path("name").asText());assertTrue(attrs.path("enabled").isBoolean());assertEquals(Long.MAX_VALUE,attrs.path("count").asLong());assertEquals("java.lang.String[]",attrs.path("type").path("name").asText());assertEquals("CLASS",attrs.path("mode").path("constant").asText());assertEquals("inside",attrs.path("nested").path("attributes").path("value").asText());assertEquals(2,attrs.path("numbers").size());assertFalse(attrs.has("defaultValue"));
        assertTrue(members.get(1).path("annotation_values").path("explicit").path("p.Nested").isEmpty());
    }
    @Test void redactsSensitiveDefaultsAndReportsLimitsWithoutZeroFallback() throws Exception {
        var members=capture("@Meta(name=\"db.password\",defaultValue=\"PRIVATE_SENTINEL\") String a; @Meta(value=\""+"x".repeat(257)+"\") String b; @Meta(numbers={"+String.join(",",Collections.nCopies(17,"1"))+"}) int c;");
        assertFalse(members.toString().contains("PRIVATE_SENTINEL"));assertFalse(members.get(0).path("annotation_values").path("complete").asBoolean());
        assertEquals("redacted",members.get(0).path("annotation_values").path("explicit").path("p.Meta").path("defaultValue").path("$status").asText());assertTrue(members.get(1).toString().contains("string_limit"));assertTrue(members.get(2).toString().contains("array_limit"));
    }
}
