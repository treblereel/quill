package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.tools.ToolProvider;
import org.jboss.jandex.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.model.FrameworkEndpointRecord;

class FrameworkEndpointScannerTest {
    @TempDir static Path temp;
    static Index index;
    static Path classes;

    @BeforeAll
    static void compileBytecode() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        String spring = "org.springframework.web.bind.annotation";
        sources.put(spring + ".RequestMethod", "public enum RequestMethod {GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS,TRACE}");
        sources.put(spring + ".RequestMapping", "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface RequestMapping {String[] value() default {}; String[] path() default {}; RequestMethod[] method() default {};}");
        for (String verb : List.of("Get", "Post", "Put", "Delete", "Patch"))
            sources.put(spring + "." + verb + "Mapping", "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface " + verb + "Mapping {String[] value() default {}; String[] path() default {};}");
        sources.put(spring + ".FakeRequestMapping", "public @interface FakeRequestMapping {String value();}");
        sources.put("org.springframework.core.annotation.AliasFor", "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface AliasFor {String value() default \"\"; String attribute() default \"\"; Class<? extends java.lang.annotation.Annotation> annotation() default java.lang.annotation.Annotation.class;}");
        for (String namespace : List.of("jakarta", "javax")) {
            sources.put(namespace + ".ws.rs.Path", "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Path {String value();}");
            sources.put(namespace + ".ws.rs.HttpMethod", "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface HttpMethod {String value();}");
            for (String verb : List.of("GET", "POST"))
                sources.put(namespace + ".ws.rs." + verb, "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface " + verb + " {}");
        }
        sources.put("demo.Routes", """
                import org.springframework.web.bind.annotation.*;
                import org.springframework.core.annotation.AliasFor;
                @RequestMapping(path="/api") @interface Prefix {}
                @RequestMapping(path="/fixed", method=RequestMethod.POST) @interface Fixed {}
                @RequestMapping(method=RequestMethod.GET) @interface Custom {
                  @AliasFor(annotation=RequestMapping.class, attribute="path") String[] route() default {"/default"};
                  String value() default "ignored";
                }
                @Custom @interface Nested {
                  @AliasFor(annotation=Custom.class, attribute="route") String[] location() default {"/nested-default"};
                }
                @Custom @interface DirectRoot {
                  @AliasFor(annotation=RequestMapping.class, attribute="path") String[] route() default {"/root-default"};
                }
                @RequestMapping(method=RequestMethod.GET) @interface LocalAlias {
                  @AliasFor(annotation=RequestMapping.class, attribute="path") String[] value() default {};
                                    @AliasFor(annotation=RequestMapping.class, attribute="path") String[] route() default {};
                }
                @CycleB @interface CycleA {} @CycleA @interface CycleB {}
                @jakarta.ws.rs.HttpMethod("PURGE") @interface Purge {String value() default "not-a-path";}
                @javax.ws.rs.HttpMethod("REPORT") @interface Report {}
                @jakarta.ws.rs.HttpMethod("BAD VERB") @interface BadVerb {}
                @Purge @interface IndirectVerb {}
                @interface Unrelated {String value();}
                @Prefix public class Routes {
                  @Custom(route={"/one", "/two"}, value="/wrong") public String custom(String id) {return id;}
                  @Custom public void defaults() {}
                  @Nested(location="/nested") public void nested() {}
                  @Nested public void nestedDefault() {}
                  @DirectRoot(route="/root") public void root() {}
                  @LocalAlias("/local") public void localAlias() {}
                  @LocalAlias(value="/one",route="/two") public void badLocalAlias() {}
                  @Fixed public void fixed() {}
                  @CycleA public void cycle() {}
                  @Unrelated("/wrong") public void unrelated() {}
                  @GetMapping(value="/value",path={}) public void emptyPath() {}
                  @RequestMapping(path="/same",value="/same",method={RequestMethod.PUT,RequestMethod.POST}) public void sameAliases() {}
                  @RequestMapping(path="/one",value="/two") public void conflictingAliases() {}
                  @GetMapping("/one") @PostMapping("/two") public void ambiguous() {}
                  @FakeRequestMapping("/fake") public void fake() {}
                  @Purge @jakarta.ws.rs.Path("/cache") public void purge() {}
                  @Report @javax.ws.rs.Path("/report") public void report() {}
                  @BadVerb public void badVerb() {}
                  @IndirectVerb public void indirectVerb() {}
                  @jakarta.ws.rs.Path("/sub") public Object subresource() {return null;}
                  @GetMapping("/spring") @jakarta.ws.rs.POST @jakarta.ws.rs.Path("/jax") public void mixed() {}
                  @GetMapping("/string") public void overloaded(String x) {}
                  @GetMapping("/int") public void overloaded(int x) {}
                }
                """);
        sources.put("demo.ClassVerb", """
                @org.springframework.web.bind.annotation.RequestMapping(path="/base",method=org.springframework.web.bind.annotation.RequestMethod.POST)
                public class ClassVerb {
                  @org.springframework.web.bind.annotation.RequestMapping("/inherited") public void inheritedVerb() {}
                  @org.springframework.web.bind.annotation.GetMapping("/overridden") public void overriddenVerb() {}
                }
                """);
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 36; i++) deep.append(i == 35
                ? "@org.springframework.web.bind.annotation.RequestMapping(path=\"/deep\") "
                : "@Deep" + (i + 1) + " ").append("@interface Deep").append(i).append(" {}\n");
        deep.append("public class DeepRoutes {@Deep0 public void deep() {}}\n");
        sources.put("demo.DeepRoutes", deep.toString());
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "-d", temp.resolve("classes").toString()));
        for (var source : sources.entrySet()) {
            Path path = temp.resolve("src/" + source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(path.getParent());
            String packageName = source.getKey().substring(0, source.getKey().lastIndexOf('.'));
            Files.writeString(path, "package " + packageName + ";\n" + source.getValue());
            arguments.add(path.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        classes = temp.resolve("classes");
        index = indexAll(true);
    }

    static Index indexAll(boolean definitions) throws Exception {
        Indexer indexer = new Indexer();
        try (var paths = Files.walk(classes)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                if (!definitions && !path.endsWith("Routes.class")) continue;
                try (var input = Files.newInputStream(path)) {indexer.index(input);}
            }
        }
        return indexer.complete();
    }

    static List<FrameworkEndpointRecord> routes() {
        return JandexScanner.extractFrameworkEndpoints(index, Map.of("demo.Routes", 1));
    }
    static FrameworkEndpointRecord route(String method) {
        return routes().stream().filter(r -> r.methodName().equals(method)).findFirst().orElseThrow();
    }
    static void absent(String method) {assertTrue(routes().stream().noneMatch(r -> r.methodName().equals(method)), method);}

    @Test void resolvesComposedPathsAndVerbWithoutUnrelatedValue() {
        var r = route("custom");
        assertEquals(List.of("/one", "/two"), r.methodPaths());
        assertEquals(List.of("/api"), r.classPaths());
        assertEquals(List.of("GET"), r.httpMethods());
        assertEquals(List.of("demo.Custom", "org.springframework.web.bind.annotation.RequestMapping"), r.annotations());
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;", r.descriptor());
    }
    @Test void resolvesAliasDefault() {assertEquals(List.of("/default"), route("defaults").methodPaths());}
    @Test void resolvesNestedAlias() {assertEquals(List.of("/nested"), route("nested").methodPaths());}
    @Test void resolvesNestedDefault() {assertEquals(List.of("/nested-default"), route("nestedDefault").methodPaths());}
    @Test void nearestExplicitRootAliasWins() {assertEquals(List.of("/root"), route("root").methodPaths());}
    @Test void resolvesExplicitOverridesForTheSameRootAttribute() {assertEquals(List.of("/local"), route("localAlias").methodPaths());}
    @Test void conflictingOverridesForTheSameAttributeAreOmitted() {absent("badLocalAlias");}
    @Test void preservesFixedMetaMapping() {
        assertEquals(List.of("/fixed"), route("fixed").methodPaths());
        assertEquals(List.of("POST"), route("fixed").httpMethods());
    }
    @Test void cyclesTerminateWithoutInventingRoutes() {absent("cycle");}
    @Test void unrelatedAttributesAreNotRoutes() {absent("unrelated"); absent("fake");}
    @Test void emptyPathFallsBackToValue() {assertEquals(List.of("/value"), route("emptyPath").methodPaths());}
    @Test void samePathAliasesAndMultipleMethodsAreValid() {
        assertEquals(List.of("/same"), route("sameAliases").methodPaths());
        assertEquals(List.of("POST", "PUT"), route("sameAliases").httpMethods());
    }
    @Test void conflictingMappingsAreOmitted() {absent("conflictingAliases"); absent("ambiguous");}
    @Test void customJakartaVerbDoesNotUseAnnotationValueAsPath() {
        assertEquals(List.of("PURGE"), route("purge").httpMethods());
        assertEquals(List.of("/cache"), route("purge").methodPaths());
        assertEquals(List.of(), route("purge").classPaths());
    }
    @Test void customJavaxVerbWorks() {assertEquals(List.of("REPORT"), route("report").httpMethods());}
    @Test void invalidAndIndirectDesignatorsAreNotRoutes() {absent("badVerb"); absent("indirectVerb");}
    @Test void preservesSubresourceMarker() {assertEquals(List.of("SUBRESOURCE"), route("subresource").httpMethods());}
    @Test void mixedFrameworksKeepTheirOwnPathsAndMethods() {
        var mixed = routes().stream().filter(r -> r.methodName().equals("mixed")).toList();
        assertEquals(2, mixed.size());
        assertEquals(List.of("GET"), mixed.get(0).httpMethods());
        assertEquals(List.of("/spring"), mixed.get(0).methodPaths());
        assertEquals(List.of("POST"), mixed.get(1).httpMethods());
        assertEquals(List.of("/jax"), mixed.get(1).methodPaths());
        assertEquals(List.of(), mixed.get(1).classPaths());
    }
    @Test void overloadsKeepDistinctDescriptors() {
        assertEquals(Set.of("(Ljava/lang/String;)V", "(I)V"), routes().stream()
                .filter(r -> r.methodName().equals("overloaded")).map(FrameworkEndpointRecord::descriptor)
                .collect(java.util.stream.Collectors.toSet()));
    }
    @Test void dependencyDefinitionsResolveButNeverBecomeEndpointOwners() throws Exception {
        Index owners = indexAll(false);
        var result = JandexScanner.extractFrameworkEndpoints(owners, CompositeIndex.create(owners, index),
                Map.of("demo.Routes", 1, "demo.Fixed", 2));
        assertTrue(result.stream().anyMatch(r -> r.methodName().equals("custom")));
        assertTrue(result.stream().allMatch(r -> r.className().equals("demo.Routes")));
    }
    @Test void missingDefinitionsKeepDirectMappingsAndOmitUnknownCustomMappings() throws Exception {
        var result = JandexScanner.extractFrameworkEndpoints(indexAll(false), Map.of("demo.Routes", 1));
        assertTrue(result.stream().anyMatch(r -> r.methodName().equals("emptyPath")));
        assertTrue(result.stream().noneMatch(r -> r.methodName().equals("custom")));
        assertEquals(List.of("SUBRESOURCE"), result.stream().filter(r -> r.methodName().equals("purge"))
                .findFirst().orElseThrow().httpMethods());
    }
    @Test void deepMetaChainsAreBounded() {
        assertTimeout(java.time.Duration.ofSeconds(1), () ->
                assertTrue(JandexScanner.extractFrameworkEndpoints(index, Map.of("demo.DeepRoutes", 3)).isEmpty()));
    }
    @Test void classAndMethodHttpMethodsCombineAsUnion() {
        var result = JandexScanner.extractFrameworkEndpoints(index, Map.of("demo.ClassVerb", 2));
        assertEquals(List.of("POST"), result.stream().filter(r -> r.methodName().equals("inheritedVerb")).findFirst().orElseThrow().httpMethods());
        assertEquals(List.of("GET", "POST"), result.stream().filter(r -> r.methodName().equals("overriddenVerb")).findFirst().orElseThrow().httpMethods());
    }
}
