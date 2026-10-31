package org.treblereel.mcp.mcp;
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
        var result=new MethodContextQueries().get(db,cls,"pick(int)");
        assertTrue(result.path("source_complete").asBoolean(),result.toString());assertEquals(method,result.path("source").asText());
        assertEquals("(I)I",result.path("descriptor").asText());
        var contexts=new com.fasterxml.jackson.databind.ObjectMapper().readTree(new ContextQueries().getContext(db,java.util.List.of("p.Fixture#pick(int)","p.Fixture#pick(java.lang.String)","p.Fixture#pick(long)"),false,10));
        assertEquals(2,contexts.path("resolved_target_count").asInt());assertEquals(1,contexts.path("resolved_class_count").asInt());assertEquals(1,contexts.path("unresolved_target_count").asInt());
        var missing=new MethodContextQueries().get(db,cls,"pick(long)");assertFalse(missing.path("source_complete").asBoolean());
        java.nio.file.Files.writeString(root.resolve("src/main/java/p/Fixture.java"),"package p; class Fixture {}");
        assertFalse(new MethodContextQueries().get(db,cls,"pick(int)").path("source_complete").asBoolean());
        org.treblereel.mcp.db.QuillDatabase.retire(db);
    }
    @Test void declarationSelectionPreservesIdentityAndErrorsWithoutEnumeratingCallers() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {int pick(int n){return n;} String pick(String n){return n;} int caller(){return pick(1);}} ");
        try {
            var json=new com.fasterxml.jackson.databind.ObjectMapper();var queries=new SymbolUsageQueries();
            for(String signature:java.util.List.of("(I)I","pick(int)","(Ljava/lang/String;)Ljava/lang/String;")){
                var declaration=json.readTree(queries.findMethodDeclaration(db,"p.Fixture","pick",signature));
                var usages=json.readTree(queries.findSymbolUsages(db,"p.Fixture","pick","METHOD",signature,null,1,0));
                for(String key:java.util.List.of("target","kind","name","signature","descriptor","symbol_id"))assertEquals(usages.path(key),declaration.path(key));
                assertFalse(declaration.has("usages"));assertFalse(declaration.has("coverage"));assertFalse(declaration.has("_meta"));
            }
            var ambiguous=json.readTree(queries.findMethodDeclaration(db,"p.Fixture","pick",null));assertEquals("AMBIGUOUS_SYMBOL",ambiguous.path("error_code").asText());assertEquals(2,ambiguous.path("candidates").size());
            var missing=json.readTree(queries.findMethodDeclaration(db,"p.Fixture","pick","(J)J"));assertEquals("SYMBOL_NOT_FOUND",missing.path("error_code").asText());
        }finally{org.treblereel.mcp.db.QuillDatabase.retire(db);}
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
