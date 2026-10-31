package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.model.ClassRecord;

class QuarkusTestProfileOverridesTest {
    @TempDir Path root;
    Path output;
    void compile(String source) throws Exception {
        Path api=root.resolve("src/test/java/io/quarkus/test/junit/QuarkusTestProfile.java");Files.createDirectories(api.getParent());
        Files.writeString(api,"package io.quarkus.test.junit; public interface QuarkusTestProfile {java.util.Map<String,String> getConfigOverrides();}");
        Path file=root.resolve("src/test/java/sample/Profiles.java");Files.createDirectories(file.getParent());Files.writeString(file,source);
        output=Files.createDirectories(root.resolve("target/test-classes"));
        var compiler=ToolProvider.getSystemJavaCompiler();try(var manager=compiler.getStandardFileManager(null,null,null)) {
            assertTrue(compiler.getTask(null,manager,null,List.of("--release","17","-g","-proc:none","-d",output.toString()),null,manager.getJavaFileObjects(api,file)).call());
        }
    }
    List<ConfigurationScanner.Definition> scan() {
        var snapshot=ClassFileSnapshot.capture(List.of(output));var classes=new HashMap<String,ClassRecord>();
        for(var entry:snapshot.entries()){
            var reader=new org.objectweb.asm.ClassReader(entry.bytecode());String name=reader.getClassName().replace('/','.');
            classes.put(name,new ClassRecord(0,name,"CLASS",reader.getSuperName(),List.of(),"src/test/java/sample/Profiles.java",1,false,1,null,"source","current",".","test"));
        }
        return QuarkusTestProfileOverrides.scan(root.toAbsolutePath().normalize(),snapshot,classes);
    }
    @Test void literalMapsAndInheritedOwnerAreIndexedWithoutValuesOrExecution() throws Exception {
        compile("""
            package sample;
            import java.util.Map;
            import io.quarkus.test.junit.QuarkusTestProfile;
            abstract class Base implements QuarkusTestProfile {
              public Map<String,String> getConfigOverrides() {
                return Map.of("warehouse.enabled", "false", "warehouse.secret", "NeverPersistThis");
              }
            }
            class Child extends Base {}
            class Unrelated {public Map<String,String> getConfigOverrides(){return Map.of("not.profile","true");}}
            """);
        var defs=scan();assertEquals(Set.of("warehouse.enabled","warehouse.secret"),new HashSet<>(defs.stream().map(ConfigurationScanner.Definition::key).toList()));
        assertTrue(defs.stream().allMatch(d->d.kind().equals("quarkus_test_profile") && d.sourceSet().equals("test") && d.line()==6));
        assertTrue(defs.getFirst().evidence().contains("sample.Base"));assertTrue(defs.getFirst().evidence().contains("sample.Child"));
        assertFalse(defs.toString().contains("NeverPersistThis"));assertTrue(defs.getFirst().evidence().contains("\"activation\":\"unknown\""));
    }
    @Test void ofEntriesSingletonAndLocalsAreBoundedStaticDeclarations() throws Exception {
        compile("""
            package sample;
            import java.util.*;import io.quarkus.test.junit.QuarkusTestProfile;
            class Entries implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return Map.ofEntries(Map.entry("limits.timeout","5s"),Map.entry("limits.size","16"));}}
            class Single implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){Map<String,String> m=Collections.singletonMap("limits.other","true");return m;}}
            """);
        assertEquals(Set.of("limits.timeout","limits.size","limits.other"),new HashSet<>(scan().stream().map(ConfigurationScanner.Definition::key).toList()));
    }
    @Test void DynamicConditionalAndSideEffectsStayUnknownNeverExecute() throws Exception {
        compile("""
            package sample;
            import java.util.*;import io.quarkus.test.junit.QuarkusTestProfile;
            class Dynamic implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return Map.of("dynamic.key",System.getenv("DO_NOT_READ"));}}
            class Conditional implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){if(System.nanoTime()>0)return Map.of("branch.key","true");return Map.of();}}
            class SideEffect implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){System.setProperty("quill.must.not.execute","bad");return Map.of("side.key","false");}}
            """);
        var defs=scan();assertEquals(3,defs.size());assertTrue(defs.stream().allMatch(d->d.kind().equals("quarkus_test_profile_unknown") && d.key().equals("<dynamic>")));
        assertNull(System.getProperty("quill.must.not.execute"));assertFalse(defs.toString().contains("DO_NOT_READ"));
    }
    @Test void duplicateKeysAreUnknown() throws Exception {
        compile("package sample;import java.util.*;import io.quarkus.test.junit.QuarkusTestProfile;class Duplicate implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return Map.of(\"duplicate\",\"1\",\"duplicate\",\"2\");}}");
        assertEquals("quarkus_test_profile_unknown",scan().getFirst().kind());
    }
    @Test void missingDebugInformationRemainsAnUnknownLocation() throws Exception {
        compile("package sample;import java.util.*;import io.quarkus.test.junit.QuarkusTestProfile;class Literal implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return Map.of(\"a.key\",\"true\");}}");
        var writer=new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(Files.readAllBytes(output.resolve("sample/Literal.class"))).accept(writer,org.objectweb.asm.ClassReader.SKIP_DEBUG);
        var parsed=QuarkusTestProfileOverrides.read(writer.toByteArray());assertEquals("static_map",parsed.status());assertEquals(0,parsed.values().getFirst().line());
    }

    @Test void covariantAndDynamicOverridesDoNotReuseSuperclassValues() throws Exception {
        compile("""
          package sample;
          import java.util.*;import io.quarkus.test.junit.QuarkusTestProfile;
          abstract class Base implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return Map.of("base.only","true");}}
          class Child extends Base {public HashMap<String,String> getConfigOverrides(){return new HashMap<>();}}
          class Dynamic extends Base {public Map<String,String> getConfigOverrides(){return Map.of("dynamic",System.getenv("UNREAD"));}}
          """);
        var defs=scan();assertEquals(2,defs.size());assertTrue(defs.stream().allMatch(d->d.kind().equals("quarkus_test_profile_unknown")));
    }

}
