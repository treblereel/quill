package org.treblereel.mcp.mcp;

import org.treblereel.mcp.db.ReadQueryContext;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class MethodContextQueriesTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    @org.junit.jupiter.api.AfterEach void stop(){JavaSemanticQueries.close();}
    @Test void realCompilerContextReturnsWholeMethodAndRejectsStaleSource() throws Exception {
        String method="int pick(int n) {\n return n + 1;\n }";
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {\n "+method+"\n String pick(String s){return s;}\n}");
        var settings=new java.util.Properties();
        settings.setProperty("compilerSettingsProtocol","1");settings.setProperty("evidence","successful_mojo");
        settings.setProperty("sourceSet","main");settings.setProperty("release","17");
        settings.setProperty("jdkHome",System.getProperty("java.home"));
        var jdk=new java.util.Properties();try(var reader=java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(System.getProperty("java.home"),"release"))){jdk.load(reader);}
        settings.setProperty("jdkVersion",jdk.getProperty("JAVA_VERSION").replace("\"",""));
        settings.setProperty("sourceRoot.count","1");settings.setProperty("sourceRoot.0",root.resolve("src/main/java").toString());
        settings.setProperty("compileClasspath.count","0");settings.setProperty("argument.count","0");
        settings.setProperty("buildInput.count","1");settings.setProperty("buildInput.path.0",root.resolve("pom.xml").toString());
        settings.setProperty("buildInput.sha256.0",org.treblereel.mcp.core.SourceFileHashes.hash(root.resolve("pom.xml")));
        try(var writer=java.nio.file.Files.newBufferedWriter(root.resolve("target/quill-java-main.properties"))){settings.store(writer,"fixture");}
        var cls=org.treblereel.mcp.db.IndexReader.findClassByName(db,"p.Fixture").orElseThrow();
        var result=TestReads.read(db, readScope -> new MethodContextQueries().get(readScope,cls,"pick(int)"));
        assertTrue(result.path("source_complete").asBoolean(),result.toString());assertEquals(method,result.path("source").asText());
        assertEquals("(I)I",result.path("descriptor").asText());
        var contexts=new com.fasterxml.jackson.databind.ObjectMapper().readTree(TestReads.<String>read(db, readScope -> new ContextQueries().getContext(readScope,java.util.List.of("p.Fixture#pick(int)","p.Fixture#pick(java.lang.String)","p.Fixture#pick(long)"),false,10)));
        assertEquals(2,contexts.path("resolved_target_count").asInt());assertEquals(1,contexts.path("resolved_class_count").asInt());assertEquals(1,contexts.path("unresolved_target_count").asInt());
        var missing=TestReads.read(db, readScope -> new MethodContextQueries().get(readScope,cls,"pick(long)"));assertFalse(missing.path("source_complete").asBoolean());
        java.nio.file.Files.writeString(root.resolve("src/main/java/p/Fixture.java"),"package p; class Fixture {}");
        assertFalse(TestReads.read(db, readScope -> new MethodContextQueries().get(readScope,cls,"pick(int)")).path("source_complete").asBoolean());
        
    }
    @Test void declarationSelectionPreservesIdentityAndErrorsWithoutEnumeratingCallers() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {int pick(int n){return n;} String pick(String n){return n;} int caller(){return pick(1);}} ");
        try {
            var json=new com.fasterxml.jackson.databind.ObjectMapper();var queries=new SymbolUsageQueries();
            for(String signature:java.util.List.of("(I)I","pick(int)","(Ljava/lang/String;)Ljava/lang/String;")){
                var declaration=json.readTree(TestReads.<String>read(db, readScope -> queries.findMethodDeclaration(readScope,"p.Fixture","pick",signature)));
                var usages=json.readTree(TestReads.<String>read(db, readScope -> queries.findSymbolUsages(readScope,"p.Fixture","pick","METHOD",signature,null,1,0)));
                for(String key:java.util.List.of("target","kind","name","signature","descriptor","symbol_id"))assertEquals(usages.path(key),declaration.path(key));
                assertFalse(declaration.has("usages"));assertFalse(declaration.has("coverage"));assertFalse(declaration.has("_meta"));
            }
            var ambiguous=json.readTree(TestReads.<String>read(db, readScope -> queries.findMethodDeclaration(readScope,"p.Fixture","pick",null)));assertEquals("AMBIGUOUS_SYMBOL",ambiguous.path("error_code").asText());assertEquals(2,ambiguous.path("candidates").size());
            var missing=json.readTree(TestReads.<String>read(db, readScope -> queries.findMethodDeclaration(readScope,"p.Fixture","pick","(J)J")));assertEquals("SYMBOL_NOT_FOUND",missing.path("error_code").asText());
        }finally{}
    }
    /** Most projects are built without Quill's compiler capture; recorded agents then opened the whole file. */
    @Test void withoutCompilerSettingsTheSyntaxOfTheFileStillGivesTheMethod() throws Exception {
        String pickInt="@Deprecated\n int pick(int n) {\n  String s = \"pick(1) { }\"; // pick(2) }\n  return helper(n) + s.length();\n }";
        String nestedPick="int pick(int n) { return helper(n) * 2; }";
        String source="package p;\nclass Fixture {\n /* pick(int n) { */\n "+pickInt+"\n"
                + " String pick(String s) { return s + '}'; }\n"
                + " int helper(int n) { pick(1); return n; }\n"
                + " abstract static class Base { abstract int only(int a, java.util.Map<String, Integer> b); }\n"
                + " static class Inner { "+nestedPick+" int helper(int n) { return n; } }\n}";
        var db=JavaEvidenceFixture.index(root,source);
        var outer=org.treblereel.mcp.db.IndexReader.findClassByName(db,"p.Fixture").orElseThrow();
        var result=TestReads.read(db, scope -> new MethodContextQueries().get(scope,outer,"pick(int)"));
        assertTrue(result.path("source_complete").asBoolean(),result.toString());
        assertEquals(pickInt,result.path("source").asText());
        assertEquals("java_syntax",result.path("declaration").path("basis").asText());
        assertEquals(4,result.path("declaration").path("source_line").asInt());
        assertEquals(8,result.path("declaration").path("source_end_line").asInt());
        assertEquals("source_set_unknown",result.path("declaration").path("compiler_status").asText());
        var inner=org.treblereel.mcp.db.IndexReader.findClassByName(db,"p.Fixture$Inner").orElseThrow();
        assertEquals(nestedPick,TestReads.read(db, scope -> new MethodContextQueries().get(scope,inner,"pick(int)")).path("source").asText());
        var base=org.treblereel.mcp.db.IndexReader.findClassByName(db,"p.Fixture$Base").orElseThrow();
        assertEquals("abstract int only(int a, java.util.Map<String, Integer> b);",
                TestReads.read(db, scope -> new MethodContextQueries().get(scope,base,"only")).path("source").asText());
    }
    @Test void aDeclarationThatTheBytecodeLinesDoNotPlaceStaysUnresolved() throws Exception {
        // Two same-arity declarations named run, and neither has a call or field access to place it.
        var db=JavaEvidenceFixture.index(root,"package p;\nclass Fixture {\n void run(int a) { }\n static class Other { void run(int b) { } }\n}");
        var cls=org.treblereel.mcp.db.IndexReader.findClassByName(db,"p.Fixture").orElseThrow();
        var result=TestReads.read(db, scope -> new MethodContextQueries().get(scope,cls,"run"));
        assertFalse(result.path("source_complete").asBoolean(),result.toString());
        assertEquals("source_range_ambiguous",result.path("status").asText());
    }
    @Test void rawUtf16ColumnsAndCrLfAreNotVisualTabColumns() {
        String text="first\r\n\tλ😀 body\r\nlast";
        assertEquals(8,MethodContextQueries.offset(text,2,2));
        assertEquals(text.indexOf("body"),MethodContextQueries.offset(text,2,6));
        assertEquals(-1,MethodContextQueries.offset(text,0,1));
        assertEquals(-1,MethodContextQueries.offset(text,4,1));
        assertEquals(-1,MethodContextQueries.offset(text,2,999));
    }
}
