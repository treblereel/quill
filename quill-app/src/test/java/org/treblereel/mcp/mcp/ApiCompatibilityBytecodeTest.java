package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.jdbi.v3.core.Jdbi;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.JandexScanner;
import org.treblereel.mcp.db.QuillDatabase;

/** Independent checks against actual javac output and an already-compiled consumer. */
class ApiCompatibilityBytecodeTest {
    @TempDir Path root;
    boolean compile(Path output,Map<String,String> sources,Path classpath) throws Exception {
        Files.createDirectories(output);List<String> args=new ArrayList<>(List.of("-proc:none","-d",output.toString()));
        if(classpath!=null)args.addAll(List.of("-classpath",classpath.toString()));
        for(var source:sources.entrySet()) {
            Path file=output.resolve("source").resolve(source.getKey());Files.createDirectories(file.getParent());Files.writeString(file,source.getValue());args.add(file.toString());
        }
        return ToolProvider.getSystemJavaCompiler().run(null,new ByteArrayOutputStream(),new ByteArrayOutputStream(),args.toArray(String[]::new))==0;
    }
    Jdbi index(Path classes,String name) throws Exception {
        Jdbi db=QuillDatabase.create(root.resolve(name+".db"));
        db.useHandle(h->h.execute("INSERT INTO classes(class_name,kind,superclass,interfaces,origin,lifecycle,module,source_set) VALUES ('p.Api','CLASS','java.lang.Object','[]','source','current','app','main')"));
        var indexer=new Indexer();try(var input=Files.newInputStream(classes.resolve("p/Api.class"))){indexer.index(input);}
        for(var member:JandexScanner.extractClassMembers(indexer.complete(),Map.of("p.Api",1))) {
            db.useHandle(h->h.createUpdate("INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations,annotation_details) VALUES (1,:kind,:name,:signature,:descriptor,:type,'[]',:flags,'[]','[]')")
                    .bind("kind",member.kind()).bind("name",member.name()).bind("signature",member.signature()).bind("descriptor",member.descriptor())
                    .bind("type",member.typeName()).bind("flags",member.modifiers()).execute());
        }
        return db;
    }
    String run(Path api,Path client,int exit) throws Exception {
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",api+File.pathSeparator+client,"p.Use").redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(exit,process.exitValue());
            return new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        } finally {if(process.isAlive())process.destroyForcibly();}
    }
    boolean has(JsonNode result,String reason) {for(JsonNode f:result.path("findings"))if(f.path("reason").asText().equals(reason))return true;return false;}
    @Test void removalAndStaticChangeMatchActualLinkageFailures() throws Exception {
        Path old=root.resolve("old"),removed=root.resolve("removed"),instance=root.resolve("instance"),client=root.resolve("client");
        assertTrue(compile(old,Map.of("p/Api.java","package p; public class Api {public static String run(String x){return x;}}"),null));
        assertTrue(compile(client,Map.of("p/Use.java","package p; public class Use {public static void main(String[] a){System.out.println(Api.run(\"ok\"));}}"),old));
        assertTrue(compile(removed,Map.of("p/Api.java","package p; public class Api {public static String run(int x){return \"new\";}}"),null));
        assertTrue(run(removed,client,1).contains("NoSuchMethodError"));
        JsonNode removal=ApiCompatibilityQueries.compare(index(old,"old-index"),index(removed,"removed-index"),20);
        assertTrue(has(removal,"MEMBER_REMOVED_OR_DESCRIPTOR_CHANGED"));
        assertEquals("potentially_breaking",removal.path("verdict").asText());
        assertTrue(compile(instance,Map.of("p/Api.java","package p; public class Api {public String run(String x){return x;}}"),null));
        assertTrue(run(instance,client,1).contains("IncompatibleClassChangeError"));
        assertTrue(has(ApiCompatibilityQueries.compare(index(old,"old-index-two"),index(instance,"instance-index"),20),"STATIC_INSTANCE_CHANGED"));
    }
    @Test void bridgePreservesIndexedBinaryDescriptor() throws Exception {
        Path old=root.resolve("old"),current=root.resolve("current"),client=root.resolve("client");
        String base="package p; public class Base {public Number value(){return 1;}}";
        assertTrue(compile(old,Map.of("p/Base.java",base,"p/Api.java","package p; public class Api extends Base {public Number value(){return 1;}}"),null));
        assertTrue(compile(client,Map.of("p/Use.java","package p; public class Use {public static void main(String[] a){System.out.println(new Api().value());}}"),old));
        assertTrue(compile(current,Map.of("p/Base.java",base,"p/Api.java","package p; public class Api extends Base {public Integer value(){return 2;}}"),null));
        assertEquals("2",run(current,client,0).strip());
        JsonNode report=ApiCompatibilityQueries.compare(index(old,"bridge-old"),index(current,"bridge-new"),20);
        assertFalse(has(report,"MEMBER_REMOVED_OR_DESCRIPTOR_CHANGED"));assertFalse(report.path("compatibility_proven").asBoolean());
        assertEquals("review_required",report.path("verdict").asText());
    }
    @Test void addedOverloadPreservesBinaryConsumerButMakesNullCallSourceAmbiguous() throws Exception {
        Path old=root.resolve("old"),current=root.resolve("current"),client=root.resolve("client"),recompile=root.resolve("recompile");
        String oldApi="package p; public class Api {public static String run(String x){return \"string\";}}";
        String caller="package p; public class Use {public static void main(String[] a){System.out.println(Api.run(null));}}";
        assertTrue(compile(old,Map.of("p/Api.java",oldApi),null));assertTrue(compile(client,Map.of("p/Use.java",caller),old));
        assertTrue(compile(current,Map.of("p/Api.java",oldApi.replace("}}","} public static String run(Integer x){return \"integer\";}}")),null));
        assertEquals("string",run(current,client,0).strip());assertFalse(compile(recompile,Map.of("p/Use.java",caller),current));
        JsonNode report=ApiCompatibilityQueries.compare(index(old,"overload-old"),index(current,"overload-new"),20);
        assertTrue(has(report,"MEMBER_ADDED"));assertEquals("review_required",report.path("verdict").asText());
    }
}
