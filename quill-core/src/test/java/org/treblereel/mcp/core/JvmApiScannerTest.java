package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.*;
import org.treblereel.mcp.model.ClassRecord;

class JvmApiScannerTest {
    @TempDir Path root;
    private static final ObjectMapper JSON=new ObjectMapper();
    Path compile() throws Exception {
        Path src=root.resolve("Api.java"),out=Files.createDirectories(root.resolve("classes"));
        Files.writeString(src,"""
                package p;
                public class Api<T extends Number> {
                    public static final long LONG=Long.MIN_VALUE;
                    public static final float NAN=Float.NaN;
                    public static final double INF=Double.POSITIVE_INFINITY;
                    public static final String TEXT="hi\\nthere";
                    public final String NOT_CONSTANT=new String("runtime");
                    protected static final class Nested {}
                    public <X extends CharSequence> X identity(X value) throws java.io.IOException {return value;}
                }
                """);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",out.toString(),src.toString()));
        return out;
    }
    @Test void retainsRawSignaturesInnerFlagsAndExactConstantRepresentations() throws Exception {
        var capture=JvmApiScanner.capture(ClassFileSnapshot.capture(List.of(compile())),Map.of("p.Api",1,"p.Api$Nested",2));
        JsonNode api=JSON.readTree(capture.get(1)),nested=JSON.readTree(capture.get(2));
        assertTrue(api.path("generic_signature").asText().contains("Number"));
        assertTrue((nested.path("access_flags").asInt() & 4)!=0);
        assertTrue((nested.path("access_flags").asInt() & 16)!=0);
        Map<String,JsonNode> members=new HashMap<>();api.path("members").forEach(m->members.put(m.path("name").asText(),m));
        assertEquals(Long.toString(Long.MIN_VALUE),members.get("LONG").path("constant").path("value").asText());
        assertEquals(Integer.toUnsignedString(Float.floatToRawIntBits(Float.NaN)),members.get("NAN").path("constant").path("bits").asText());
        assertEquals(Long.toUnsignedString(Double.doubleToRawLongBits(Double.POSITIVE_INFINITY)),members.get("INF").path("constant").path("bits").asText());
        assertEquals("hi\nthere",members.get("TEXT").path("constant").path("value").asText());
        assertTrue(members.get("NOT_CONSTANT").path("constant").isNull());
        assertTrue(members.get("identity").path("generic_signature").asText().contains("CharSequence"));
        assertEquals("java.io.IOException",members.get("identity").path("exceptions").get(0).asText());
        assertThrows(UnsupportedOperationException.class,()->capture.put(3,"bad"));
    }
    @Test void duplicateBytecodeOwnersAreExplicitlyAmbiguous() throws Exception {
        Path out=compile();var capture=JvmApiScanner.capture(ClassFileSnapshot.capture(List.of(out,out)),Map.of("p.Api",1));
        assertEquals("ambiguous_bytecode_owner",JSON.readTree(capture.get(1)).path("status").asText());
        assertFalse(JSON.readTree(capture.get(1)).has("members"));
    }
    IndexSnapshot snapshot(Map<Integer,String> api,boolean includeClass) {
        var classes=includeClass?List.of(new ClassRecord(1,"p.Api","CLASS","java.lang.Object",List.of(),null,0,false,0)):List.<ClassRecord>of();
        return new IndexSnapshot(classes,List.of(),List.of(),List.of(),Map.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),null,List.of(),api);
    }
    @Test void apiEvidenceParticipatesInAtomicFreshAndIncrementalSnapshots() throws Exception {
        var db=QuillDatabase.create(root.resolve("index.db"));
        var captured=JvmApiScanner.capture(ClassFileSnapshot.capture(List.of(compile())),Map.of("p.Api",1));
        IndexWriter.writeFresh(db,snapshot(captured,true));
        assertEquals(captured.get(1),db.withHandle(h->h.createQuery("SELECT evidence FROM jvm_api").mapTo(String.class).one()));
        IndexWriter.writeIncremental(db,snapshot(Map.of(1,"changed"),true));
        assertEquals("changed",db.withHandle(h->h.createQuery("SELECT evidence FROM jvm_api").mapTo(String.class).one()));
        IndexWriter.writeIncremental(db,snapshot(Map.of(),false));
        assertEquals(0,db.withHandle(h->h.createQuery("SELECT count(*) FROM jvm_api").mapTo(Integer.class).one()).intValue());
        assertTrue(db.withHandle(h->h.createQuery("PRAGMA foreign_key_check").mapToMap().list()).isEmpty());
    }
}
