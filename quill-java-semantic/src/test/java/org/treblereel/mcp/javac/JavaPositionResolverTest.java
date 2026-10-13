package org.treblereel.mcp.javac;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JavaPositionResolverTest {
    @TempDir Path root;
    static Stream<Arguments> resolutions() {
        return Stream.of(
            row("static String pick(String x){return x;} static int pick(int x){return x;} void run(){pick(1);}", "pick(1)", "pick", "p.Example", "(I)I"),
            row("static String pick(String x){return x;} static int pick(int x){return x;} void run(){pick(\"x\");}", "pick(\"x\")", "pick", "p.Example", "(Ljava/lang/String;)Ljava/lang/String;"),
            row("static void pick(long x){} static void pick(Integer x){} void run(){pick(1);}", "pick(1)", "pick", "p.Example", "(J)V"),
            row("static void pick(String x){} static void pick(Object x){} void run(){pick(null);}", "pick(null)", "pick", "p.Example", "(Ljava/lang/String;)V"),
            row("static <T extends Number> T pick(T x){return x;} void run(){pick(1);}", "pick(1)", "pick", "p.Example", "(Ljava/lang/Number;)Ljava/lang/Number;"),
            row("static void pick(int... x){} static void pick(Object x){} void run(){pick(new int[]{1});}", "pick(new int[]{1})", "pick", "p.Example", "([I)V"),
            row("static void pick(java.util.function.Function<String,String> f){} void run(){pick(x -> x.trim());}", "pick(x ->", "pick", "p.Example", "(Ljava/util/function/Function;)V"),
            row("static int pick(int x){return x;} static String pick(String x){return x;} void run(){java.util.function.IntUnaryOperator f=Example::pick;}", "Example::pick", "pick", "p.Example", "(I)I"),
            row("static class Nested {Nested(String x){}} void run(){new Nested(\"x\");}", "new Nested(\"x\")", "Nested", "p.Example$Nested", "(Ljava/lang/String;)V"),
            row("class Inner {Inner(int x){}} void run(){new Inner(1);}", "new Inner(1)", "Inner", "p.Example$Inner", "(Lp/Example;I)V"),
            row("record Pair(int x){} void run(){new Pair(1);}", "new Pair(1)", "Pair", "p.Example$Pair", "(I)V"),
            row("static int поле; void run(){поле=1;}", "поле=1", "поле", "p.Example", "I"),
            row("static int выбрать(int x){return x;} void run(){выбрать(1);}", "выбрать(1)", "выбрать", "p.Example", "(I)I"),
            row("void run(){java.util.Collections.emptyList();}", "java.util.Collections.emptyList()", "emptyList", "java.util.Collections", "()Ljava/util/List;"),
            row("void run(){String.valueOf(1);}", "String.valueOf(1)", "valueOf", "java.lang.String", "(I)Ljava/lang/String;"),
            row("static int pick(java.util.List<? extends Number> xs){return 1;} void run(){pick(java.util.List.of(1));}", "run(){pick(java.util.List.of(1));}", "pick", "p.Example", "(Ljava/util/List;)I")
        );
    }
    static Arguments row(String body, String anchor, String token, String owner, String descriptor) {
        return Arguments.of("package p; class Example {"+body+"}", anchor, token, owner, descriptor);
    }
    Path write(String text) throws Exception { return Files.writeString(root.resolve("Example.java"), text); }
    JavaPositionResolver resolver() { return new JavaPositionResolver(List.of(root), List.of(), "17"); }
    JavaPositionResolver.Result query(JavaPositionResolver resolver, Path file, String anchor, String token) throws Exception {
        String source = Files.readString(file);
        assertEquals(source.indexOf(anchor), source.lastIndexOf(anchor), "Test anchor must be unique");
        return resolver.resolve(file, source.indexOf(anchor) + anchor.indexOf(token));
    }
    @ParameterizedTest @MethodSource("resolutions")
    void resolvesCompilerSelectedDeclarations(String source, String anchor, String token,
                                             String owner, String descriptor) throws Exception {
        Path file=write(source);
        try (var resolver=resolver()) {
            var result=query(resolver,file,anchor,token);
            assertEquals("resolved",result.status()); assertEquals(owner,result.owner());
            assertEquals(descriptor,result.descriptor());
            assertTrue(query(resolver,file,anchor,token).reused());
        }
        try (var files=Files.walk(root)) { assertFalse(files.anyMatch(p->p.toString().endsWith(".class"))); }
    }
    @Test void staticImportsAndInheritedDefaultMethods() throws Exception {
        Path file=write("package p; import static java.util.Collections.emptyList; interface I {default int pick(){return 1;}} class Example implements I {void run(){emptyList(); pick();}}");
        try(var resolver=resolver()) {
            assertEquals("java.util.Collections",query(resolver,file,"emptyList();","emptyList").owner());
            var inherited=query(resolver,file,"pick();","pick");
            assertEquals("p.I",inherited.owner()); assertEquals("()I",inherited.descriptor());
        }
    }
    @Test void localShadowingDoesNotSelectSameNameField() throws Exception {
        Path file=write("package p; class Example {String value; void run(){String value=\"x\"; value.length();}}");
        try(var resolver=resolver()) {
            var local=query(resolver,file,"value.length()","value");
            assertEquals("local",local.status()); assertNull(local.owner());
            assertEquals("java.lang.String",query(resolver,file,"value.length()","length").owner());
        }
    }
    @Test void receiverPositionDoesNotResolveSelectedMethod() throws Exception {
        Path file=write("package p; class Example {int pick(){return 1;} void run(Example receiver){receiver.pick();}}");
        try(var resolver=resolver()) {
            assertEquals("local",query(resolver,file,"receiver.pick()","receiver").status());
            assertEquals("METHOD",query(resolver,file,"receiver.pick()","pick").kind());
        }
    }
    @Test void equalSizeEditWithRestoredTimestampRebuildsAndDisposes() throws Exception {
        Path file=write("package p; class Example {static int pick(int x){return x;} static int pick(String x){return 1;} void run(){pick(1 );}}");
        try(var resolver=resolver()) {
            var before=query(resolver,file,"pick(1 )","pick");
            FileTime timestamp=Files.getLastModifiedTime(file);
            String changed=Files.readString(file).replace("pick(1 );","pick(\"\");");
            assertEquals(Files.size(file),changed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            Files.writeString(file,changed); Files.setLastModifiedTime(file,timestamp);
            var after=query(resolver,file,"pick(\"\")","pick");
            assertEquals("(Ljava/lang/String;)I",after.descriptor()); assertFalse(after.reused());
            assertTrue(after.generation()>before.generation()); assertEquals(1,resolver.closedSnapshots());
            resolver.close(); resolver.close(); assertEquals(2,resolver.closedSnapshots());
        }
    }
    @Test void additionAndDeletionChangesAnUneditedOverloadCall() throws Exception {
        Path file=write("package p; class Example {void run(){Helper.pick(null);}}");
        Path helper=Files.writeString(root.resolve("Helper.java"),"package p; class Helper {static int pick(Object x){return 1;}}");
        try(var resolver=resolver()) {
            assertEquals("(Ljava/lang/Object;)I",query(resolver,file,"Helper.pick(null)","pick").descriptor());
            Files.writeString(helper,"package p; class Helper extends More {static int pick(Object x){return 1;}}");
            Path extra=Files.writeString(root.resolve("More.java"),"package p; class More {static int pick(String x){return 1;}}");
            assertEquals("(Ljava/lang/String;)I",query(resolver,file,"Helper.pick(null)","pick").descriptor());
            Files.delete(extra);
            assertEquals("analysis_errors",query(resolver,file,"Helper.pick(null)","pick").status());
            Files.writeString(helper,"package p; class Helper {static int pick(Object x){return 1;}}");
            assertEquals("(Ljava/lang/Object;)I",query(resolver,file,"Helper.pick(null)","pick").descriptor());
        }
    }
    @Test void errorsAndRepairCannotRetainPreviousSuccessfulResolution() throws Exception {
        Path file=write("package p; class Example {void run(){missing();}}");
        try(var resolver=resolver()) {
            assertEquals("analysis_errors",query(resolver,file,"missing()","missing").status());
            Files.writeString(file,"package p; class Example {void missing(){} void run(){missing();}}");
            assertEquals("resolved",query(resolver,file,"missing();","missing").status());
        }
    }
    @Test void capturedAnonymousAndLocalConstructorDescriptorsRemainUnknown() throws Exception {
        Path file=write("package p; class Example {void run(){int x=1; class Local {int v=x;} new Local(); new Runnable(){public void run(){System.out.println(x);}};}}");
        try(var resolver=resolver()) {
            assertEquals("captured_constructor_descriptor_unknown",query(resolver,file,"new Local()","Local").status());
            assertEquals("captured_constructor_descriptor_unknown",query(resolver,file,"new Runnable()","Runnable").status());
        }
    }
    @Test void invalidPositionCommentsAndNonModuleFilesDoNotProduceSymbols() throws Exception {
        Path file=write("package p; class Example {void run(){/* pick */ String text=\"pick\";}}");
        try(var resolver=resolver()) {
            assertEquals("invalid_position",resolver.resolve(file,-1).status());
            assertEquals("invalid_position",resolver.resolve(file,(int)Files.size(file)).status());
            assertEquals("unresolved",query(resolver,file,"/* pick */","pick").status());
            assertEquals("unresolved",query(resolver,file,"\"pick\"","pick").status());
            assertEquals("source_not_in_module",resolver.resolve(root.resolve("absent.java"),0).status());
        }
    }
    @Test void projectStaticInitializerIsNotExecuted() throws Exception {
        Path file=write("package p; class Example {static {if(true)throw new RuntimeException(\"must not run\");} static int pick(){return 1;} void run(){pick();}}");
        try(var resolver=resolver()) { assertEquals("resolved",query(resolver,file,"pick();","pick").status()); }
    }
    @Test void concurrentQueriesShareOnlyOneCurrentSnapshot() throws Exception {
        Path file=write("package p; class Example {static int pick(int x){return x;} void run(){pick(1);}}");
        var executor=Executors.newFixedThreadPool(4);
        try(var resolver=resolver()) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<JavaPositionResolver.Result>>();
            for(int i=0;i<20;i++) futures.add(executor.submit(()->query(resolver,file,"pick(1)","pick")));
            for(var future:futures) {var result=future.get();assertEquals("(I)I",result.descriptor());assertEquals(1,result.generation());}
        } finally {executor.shutdownNow();}
    }

    Path library(String name, String code, String service) throws Exception {
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();
        Path output=Files.createTempDirectory(root,"compiled-");
        var source=new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///"+name+".java"),javax.tools.JavaFileObject.Kind.SOURCE) {
            public CharSequence getCharContent(boolean ignore) {return code;}
        };
        try(var manager=compiler.getStandardFileManager(null,null,null)) {
            assertTrue(compiler.getTask(null,manager,null,List.of("-proc:none","--release","17","-d",output.toString()),null,List.of(source)).call());
        }
        Path jar=root.resolve("library.jar");
        byte[] base=jarBytes(output,service,0);
        Files.write(jar,jarBytes(output,service,8192-base.length));
        assertEquals(8192,Files.size(jar));
        return jar;
    }
    byte[] jarBytes(Path output,String service,int padding) throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        try(var jar=new java.util.jar.JarOutputStream(bytes);var files=Files.walk(output)) {
            for(Path file:files.filter(Files::isRegularFile).sorted().toList())
                jarEntry(jar,output.relativize(file).toString().replace('\\','/'),Files.readAllBytes(file));
            if(service!=null)jarEntry(jar,"META-INF/services/javax.annotation.processing.Processor",service.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jarEntry(jar,"padding",new byte[padding]);
        }
        return bytes.toByteArray();
    }
    void jarEntry(java.util.jar.JarOutputStream jar,String name,byte[] bytes) throws Exception {
        var entry=new java.util.jar.JarEntry(name);entry.setTime(0);entry.setMethod(java.util.zip.ZipEntry.STORED);
        entry.setSize(bytes.length);var crc=new java.util.zip.CRC32();crc.update(bytes);entry.setCrc(crc.getValue());
        jar.putNextEntry(entry);jar.write(bytes);jar.closeEntry();
    }
    @Test void dependencyReplacementWithRestoredSizeAndTimestampInvalidatesModel() throws Exception {
        Path jar=library("Lib","package p; public class Lib {public static String pick(){return \"x\";}}",null);
        Path file=write("package p; class Example {void run(){Object x=Lib.pick();}}");
        FileTime timestamp=Files.getLastModifiedTime(jar);
        try(var resolver=new JavaPositionResolver(List.of(file),List.of(jar),"17")) {
            var before=query(resolver,file,"Lib.pick()","pick");
            assertEquals("()Ljava/lang/String;",before.descriptor());
            library("Lib","package p; public class Lib {public static Object pick(){return \"x\";}}",null);
            Files.setLastModifiedTime(jar,timestamp);
            var after=query(resolver,file,"Lib.pick()","pick");
            assertEquals("()Ljava/lang/Object;",after.descriptor());assertFalse(after.reused());
            assertEquals(before.generation()+1,after.generation());
        }
    }
    @Test void serviceRegisteredAnnotationProcessorIsNeverExecuted() throws Exception {
        Path marker=root.resolve("processor-ran");
        String escaped=marker.toString().replace("\\","\\\\").replace("\"","\\\"");
        Path jar=library("Processor","package p; @javax.annotation.processing.SupportedAnnotationTypes(\"*\") public class Processor extends javax.annotation.processing.AbstractProcessor {public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> a,javax.annotation.processing.RoundEnvironment e) {try {java.nio.file.Files.writeString(java.nio.file.Path.of(\""+escaped+"\"),\"ran\");}catch(Exception x){throw new RuntimeException(x);}return false;}}","p.Processor\n");
        Path file=write("package p; class Example {static int pick(){return 1;} void run(){pick();}}");
        try(var resolver=new JavaPositionResolver(List.of(file),List.of(jar),"17")) {
            assertEquals("resolved",query(resolver,file,"pick();","pick").status());
        }
        assertFalse(Files.exists(marker));
    }

    @Test void sourceEditRacingLookupCannotReturnPreviousHighConfidenceTarget() throws Exception {
        Path file=write("package p; class Example {static int pick(int x){return x;} static int pick(String x){return 1;} void run(){pick(1 );}}");
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        try(var resolver=new JavaPositionResolver(List.of(root),List.of(),"17",()->{
            if(first.getAndSet(false))try {
                FileTime time=Files.getLastModifiedTime(file);
                Files.writeString(file,Files.readString(file).replace("pick(1 );","pick(\"\");"));
                Files.setLastModifiedTime(file,time);
            } catch(Exception e) {throw new RuntimeException(e);}
        })) {
            assertEquals("inputs_changed",query(resolver,file,"pick(1 )","pick").status());
            assertEquals(1,resolver.closedSnapshots());
            assertEquals("(Ljava/lang/String;)I",query(resolver,file,"pick(\"\")","pick").descriptor());
        }
    }

    @Test void probeRejectsMalformedRequestThenServesAValidQueryAndExitsOnEof() throws Exception {
        Path file=write("package p; class Example {static int pick(int x){return x;} void run(){pick(1);}}");
        String source=Files.readString(file);
        int offset=source.indexOf("pick(1)");
        Path javaExecutable=Path.of(System.getProperty("java.home"),"bin","java");
        var process=new ProcessBuilder(javaExecutable.toString(),"-cp",System.getProperty("java.class.path"),
                ProbeMain.class.getName(),root.toString(),"","17").start();
        try {
            String path=java.util.Base64.getEncoder().encodeToString(file.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try(var input=process.getOutputStream()) { input.write(("bad-request\n"+path+"\t"+offset+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,process.exitValue());
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(output.startsWith("READY\nERROR\tIllegalArgumentException\n"));
            assertTrue(output.contains("\nresolved\t"));
        } finally { if(process.isAlive())process.destroyForcibly(); }
    }
    @Test void classpathJavaSourcesAreNotLoadedImplicitly() throws Exception {
        Path dependencies=root.resolve("dependencies/p"); Files.createDirectories(dependencies);
        Files.writeString(dependencies.resolve("Hidden.java"),"package p; public class Hidden {public static int value(){return 1;}}");
        Path sourceRoot=root.resolve("sources"); Files.createDirectories(sourceRoot);
        Path file=Files.writeString(sourceRoot.resolve("Example.java"),"package p; class Example {void run(){Hidden.value();}}");
        try(var model=new JavaPositionResolver(List.of(sourceRoot),List.of(root.resolve("dependencies")),"17")) {
            assertEquals("analysis_errors",model.resolve(file,Files.readString(file).indexOf("value()")).status());
        }
    }
    @Test void sourceTargetDoesNotHideNewerJdkApisAndWarmupReusesTheModel() throws Exception {
        Path file=write("package p; class Example {void run(){\"text\".isBlank();}}");
        int offset=Files.readString(file).indexOf("isBlank");
        try(var model=new JavaPositionResolver(List.of(root),List.of(),null,"8","8")) {
            assertEquals("warmed",model.warm(file).status());
            var result=model.resolve(file,offset);
            assertEquals("resolved",result.status()); assertTrue(result.reused());
            assertEquals("java.lang.String",result.owner()); assertEquals("()Z",result.descriptor());
        }
        try(var model=new JavaPositionResolver(List.of(root),List.of(),"8")) {
            assertEquals("analysis_errors",model.resolve(file,offset).status());
        }
    }

}
