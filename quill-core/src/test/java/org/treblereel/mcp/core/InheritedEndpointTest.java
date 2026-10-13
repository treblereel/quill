package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.jboss.jandex.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.model.FrameworkEndpointRecord;

class InheritedEndpointTest {
    @TempDir static Path temp;
    static Index index;
    static Path classes;

    @BeforeAll static void compile() throws Exception {
        Map<String,String> sources = new LinkedHashMap<>();
        String retention = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) ";
        String spring = "org.springframework.web.bind.annotation";
        sources.put(spring + ".RequestMapping", retention + "public @interface RequestMapping {String[] value() default {}; String[] path() default {};}");
        for (String verb : List.of("Get", "Post")) sources.put(spring + "." + verb + "Mapping",
                retention + "public @interface " + verb + "Mapping {String[] value() default {}; String[] path() default {};}");
        for (String ns : List.of("jakarta", "javax")) {
            for (String a : List.of("Path", "Produces", "QueryParam")) sources.put(ns + ".ws.rs." + a,
                    retention + "public @interface " + a + " {String value();}");
            for (String a : List.of("GET", "POST")) sources.put(ns + ".ws.rs." + a,
                    retention + "public @interface " + a + " {}");
            sources.put(ns + ".ws.rs.HttpMethod", retention + "public @interface HttpMethod {String value();}");
        }
        sources.put("demo.Cases", """
                import org.springframework.web.bind.annotation.*;
                @RequestMapping("/iface") interface Api {@GetMapping("/read") String read(String id);}
                class Impl implements Api {public String read(String id) {return id;}}
                @RequestMapping("/own") class OwnType implements Api {public String read(String id) {return id;}}
                @RequestMapping("/base") class Base {@PostMapping("/base-read") public String read(String id) {return id;}}
                class Inherits extends Base {}
                class OverrideBody extends Base {public String read(String id) {return id;}}
                @RequestMapping("/direct") class OverrideMapping extends Base {@GetMapping("/own-read") public String read(String id) {return id;}}
                class InvalidOverride extends Base {@RequestMapping(path="/a",value="/b") public String read(String id) {return id;}}
                class Mixed extends Base implements Api {public String read(String id) {return id;}}
                interface Generic<T> {@GetMapping("/generic") T read(T value);}
                interface Middle<T> extends Generic<T> {}
                class GenericImpl implements Middle<String> {
                  public String read(String value) {return value;}
                  public String read(Integer value) {return value.toString();}
                }
                class GenericBase<T> implements Generic<T> {public T read(T value) {return value;}}
                class GenericInherited extends GenericBase<String> {}
                interface ShadowApi<T> {@GetMapping("/shadow") <T> T shadow(T value);}
                class ShadowImpl implements ShadowApi<String> {public <U> U shadow(U value) {return value;}}
                interface MethodApi<T> {@GetMapping("/method-generic") <U extends T> void method(U value);}
                class MethodImpl implements MethodApi<Number> {public <V extends Number> void method(V value) {}}
                interface ArrayApi<T> {@GetMapping("/array") void array(T[] value);}
                interface ArrayMiddle<T> extends ArrayApi<T[]> {}
                class ArrayImpl implements ArrayMiddle<String> {public void array(String[][] value) {}}
                interface Covariant {@GetMapping("/covariant") Object value();}
                class CovariantImpl implements Covariant {public String value() {return "";}}
                interface Bound<T extends Number> {@GetMapping("/bound") void bound(T value);}
                class BoundImpl<T extends Number> implements Bound<T> {public void bound(T value) {}}
                interface DefaultApi {@GetMapping("/default") default String value() {return "";}}
                class DefaultImpl implements DefaultApi {}
                interface Left extends Api {} interface Right extends Api {}
                class Diamond implements Left, Right {public String read(String id) {return id;}}
                interface ConflictA {@GetMapping("/a") String read(String id);}
                interface ConflictB {@GetMapping("/b") String read(String id);}
                class Conflict implements ConflictA,ConflictB {public String read(String id) {return id;}}
                class OwnConflict implements ConflictA,ConflictB {@GetMapping("/own") public String read(String id) {return id;}}
                class VisibilityBase {
                  @GetMapping("/private") private void hidden() {}
                  @GetMapping("/static") public static void global() {}
                  @GetMapping("/protected") protected void protectedMethod() {}
                  @GetMapping("/package") void packageMethod() {}
                }
                class VisibilityChild extends VisibilityBase {}
                @jakarta.ws.rs.Path("/iface-not-inherited") interface JaxApi {
                  @jakarta.ws.rs.GET @jakarta.ws.rs.Path("/read") String read(String id);
                }
                @jakarta.ws.rs.Path("/resource") class JaxImpl implements JaxApi {public String read(String id) {return id;}}
                class JaxUnannotatedBase {public String read(String id) {return id;}}
                @jakarta.ws.rs.Path("/resource") class JaxInheritedInterface extends JaxUnannotatedBase implements JaxApi {}
                class JaxNoType implements JaxApi {public String read(String id) {return id;}}
                @jakarta.ws.rs.Path("/base-not-inherited") class JaxBase {
                  @jakarta.ws.rs.POST @jakarta.ws.rs.Path("/base-read") public String read(String id) {return id;}
                }
                @jakarta.ws.rs.Path("/child") class JaxMixed extends JaxBase implements JaxApi {public String read(String id) {return id;}}
                class JaxInherits extends JaxBase {}
                class JaxOwnProduces implements JaxApi {@jakarta.ws.rs.Produces("json") public String read(String id) {return id;}}
                class JaxOwnParameter implements JaxApi {public String read(@jakarta.ws.rs.QueryParam("id") String id) {return id;}}
                class JaxOwnPath implements JaxApi {@jakarta.ws.rs.Path("/local") public String read(String id) {return id;}}
                class JaxOtherAnnotation implements JaxApi {@Deprecated public String read(String id) {return id;}}
                interface JaxA {@jakarta.ws.rs.GET @jakarta.ws.rs.Path("/a") void read();}
                interface JaxB {@jakarta.ws.rs.POST @jakarta.ws.rs.Path("/b") void read();}
                class JaxConflict implements JaxA,JaxB {public void read() {}}
                interface JaxChildApi extends JaxA {@jakarta.ws.rs.POST @jakarta.ws.rs.Path("/child") void read();}
                class JaxSpecific implements JaxChildApi {public void read() {}}
                interface JaxLeft extends JaxA {} interface JaxRight extends JaxA {}
                class JaxDiamond implements JaxLeft,JaxRight {public void read() {}}
                interface JaxGeneric<T> {@jakarta.ws.rs.GET @jakarta.ws.rs.Path("/generic") T read(T value);}
                class JaxGenericImpl implements JaxGeneric<String> {public String read(String value) {return value;}}
                interface LegacyApi {@javax.ws.rs.GET @javax.ws.rs.Path("/legacy") void legacy();}
                class LegacyImpl implements LegacyApi {public void legacy() {}}
                @jakarta.ws.rs.HttpMethod("PURGE") @interface Purge {}
                interface PurgeApi {@Purge @jakarta.ws.rs.Path("/purge") void purge();}
                class PurgeImpl implements PurgeApi {public void purge() {}}
                class JaxPrivate {@jakarta.ws.rs.GET private void hidden() {}}
                public class Cases {}
                """);
        sources.put("external.PackageBase", """
                public class PackageBase {
                 @org.springframework.web.bind.annotation.GetMapping("/package") void packageMethod() {}
                 @org.springframework.web.bind.annotation.GetMapping("/protected") protected void protectedMethod() {}
                }
                """);
        sources.put("demo.CrossPackage", "public class CrossPackage extends external.PackageBase {}");
        sources.put("external.BackToPackage", "public class BackToPackage extends demo.CrossPackage {}");
        StringBuilder deep = new StringBuilder("class Deep0 {@org.springframework.web.bind.annotation.GetMapping(\"/inherited\") public void inherited() {}}\n");
        for (int i=1;i<70;i++) deep.append("class Deep").append(i).append(" extends Deep").append(i-1).append(" {}\n");
        deep.append("@org.springframework.web.bind.annotation.RequestMapping(\"/own\") public class DeepOwner extends Deep69 {@org.springframework.web.bind.annotation.GetMapping(\"/direct\") public void direct() {}}\n");
        sources.put("demo.DeepOwner",deep.toString());
        List<String> args = new ArrayList<>(List.of("-proc:none", "-d", temp.resolve("classes").toString()));
        for (var source : sources.entrySet()) {
            Path file = temp.resolve("src/" + source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package " + source.getKey().substring(0,source.getKey().lastIndexOf('.')) + ";\n" + source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        classes = temp.resolve("classes");
        index = readIndex(Set.of());
    }
    static Index readIndex(Set<String> excluded) throws Exception {
        Indexer indexer = new Indexer();
        try (var paths = Files.walk(classes)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace('/', '.').replace(".class", "");
                if (excluded.contains(name)) continue;
                try (var in = Files.newInputStream(file)) {indexer.index(in);}
            }
        }
        return indexer.complete();
    }
    static List<FrameworkEndpointRecord> routes(String type) {
        return JandexScanner.extractFrameworkEndpoints(index, Map.of(type, 1, "demo.Base", 2,
                "demo.DefaultApi", 3, "demo.JaxBase", 4)).stream().filter(r -> r.className().equals(type)).toList();
    }
    static FrameworkEndpointRecord only(String type) {var rows=routes(type); assertEquals(1,rows.size(),type); return rows.getFirst();}
    @Test void interfaceMappingAppliesToConcreteOverride() {
        var r=only("demo.Impl"); assertEquals(List.of("/iface"),r.classPaths());
        assertEquals(List.of("/read"),r.methodPaths()); assertEquals("demo.Impl",r.declaringClassName());
        assertEquals(List.of("class:demo.Api","method:demo.Api#read(Ljava/lang/String;)Ljava/lang/String;"),r.mappingSources());
    }
    @Test void ownTypeMappingReplacesInterfacePrefix() {assertEquals(List.of("/own"),only("demo.OwnType").classPaths());}
    @Test void inheritedBodyKeepsBaseOwnership() {var r=only("demo.Inherits");assertEquals("demo.Base",r.declaringClassName());assertEquals(2,r.declaringClassId());assertEquals(List.of("/base"),r.classPaths());}
    @Test void overriddenBodyUsesChildOwnershipAndBaseAnnotations() {var r=only("demo.OverrideBody");assertEquals("demo.OverrideBody",r.declaringClassName());assertEquals(List.of("/base-read"),r.methodPaths());}
    @Test void ownMethodMappingOverridesParent() {var r=only("demo.OverrideMapping");assertEquals(List.of("/own-read"),r.methodPaths());assertEquals(List.of("/direct"),r.classPaths());}
    @Test void invalidOwnMappingDoesNotFallBackToParent() {assertTrue(routes("demo.InvalidOverride").isEmpty());}
    @Test void springInterfacePrecedesSuperclassForOverride() {var r=only("demo.Mixed");assertEquals(List.of("/read"),r.methodPaths());assertEquals(List.of("/iface"),r.classPaths());}
    @Test void genericOverrideDoesNotMatchUnrelatedOverloadOrBridge() {var r=only("demo.GenericImpl");assertEquals("(Ljava/lang/String;)Ljava/lang/String;",r.descriptor());}
    @Test void inheritedGenericBodyKeepsOriginalJvmDescriptor() {var r=only("demo.GenericInherited");assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;",r.descriptor());assertEquals("demo.GenericBase",r.declaringClassName());}
    @Test void methodVariableShadowsClassVariable() {assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;",only("demo.ShadowImpl").descriptor());}
    @Test void genericMethodVariableBoundUsesClassBinding() {assertEquals("(Ljava/lang/Number;)V",only("demo.MethodImpl").descriptor());}
    @Test void deepHierarchyStopsButKeepsOwnDeclaredMappings() {assertTimeout(java.time.Duration.ofSeconds(1),() -> assertEquals("direct",only("demo.DeepOwner").methodName()));}
    @Test void transitiveGenericArraysResolve() {assertEquals("([[Ljava/lang/String;)V",only("demo.ArrayImpl").descriptor());}
    @Test void covariantReturnDoesNotCreateDuplicateRoute() {assertEquals("()Ljava/lang/String;",only("demo.CovariantImpl").descriptor());}
    @Test void unresolvedRootTypeVariableUsesItsBound() {assertEquals("(Ljava/lang/Number;)V",only("demo.BoundImpl").descriptor());}
    @Test void interfaceDefaultMethodKeepsBodyDeclaration() {var r=only("demo.DefaultImpl");assertEquals("demo.DefaultApi",r.declaringClassName());assertEquals(3,r.declaringClassId());}
    @Test void diamondIsDeduplicated() {assertEquals(List.of("/read"),only("demo.Diamond").methodPaths());}
    @Test void conflictingInterfaceContractsAreOmitted() {assertTrue(routes("demo.Conflict").isEmpty());}
    @Test void ownMappingResolvesInterfaceConflict() {assertEquals(List.of("/own"),only("demo.OwnConflict").methodPaths());}
    @Test void privateAndStaticMethodsAreNotInherited() {assertEquals(Set.of("protectedMethod","packageMethod"),routes("demo.VisibilityChild").stream().map(FrameworkEndpointRecord::methodName).collect(java.util.stream.Collectors.toSet()));}
    @Test void packagePrivateDoesNotCrossPackageBoundary() {assertEquals("protectedMethod",only("demo.CrossPackage").methodName());assertEquals("protectedMethod",only("external.BackToPackage").methodName());}
    @Test void jaxUsesOwnClassPathAndInheritedMethodAnnotations() {var r=only("demo.JaxImpl");assertEquals(List.of("/resource"),r.classPaths());assertEquals(List.of("GET"),r.httpMethods());}
    @Test void jaxResourceInterfaceAppliesToInheritedUnannotatedBody() {var r=only("demo.JaxInheritedInterface");assertEquals(List.of("GET"),r.httpMethods());assertEquals("demo.JaxUnannotatedBase",r.declaringClassName());assertEquals(List.of("/resource"),r.classPaths());}
    @Test void jaxTypePathIsNeverInherited() {assertEquals(List.of(),only("demo.JaxNoType").classPaths());assertEquals(List.of(),only("demo.JaxInherits").classPaths());}
    @Test void jaxSuperclassPrecedesInterface() {var r=only("demo.JaxMixed");assertEquals(List.of("POST"),r.httpMethods());assertEquals(List.of("/base-read"),r.methodPaths());}
    @Test void jaxOwnProducesSuppressesAllInheritedAnnotations() {assertTrue(routes("demo.JaxOwnProduces").isEmpty());}
    @Test void jaxOwnParameterAnnotationSuppressesInheritance() {assertTrue(routes("demo.JaxOwnParameter").isEmpty());}
    @Test void jaxOwnPathSuppressesInheritedVerb() {var r=only("demo.JaxOwnPath");assertEquals(List.of("SUBRESOURCE"),r.httpMethods());assertEquals(List.of("/local"),r.methodPaths());}
    @Test void nonJaxAnnotationDoesNotSuppressInheritance() {assertEquals(List.of("GET"),only("demo.JaxOtherAnnotation").httpMethods());}
    @Test void jaxInterfaceConflictsAreOmitted() {assertTrue(routes("demo.JaxConflict").isEmpty());}
    @Test void mostSpecificJaxInterfaceWins() {assertEquals(List.of("/child"),only("demo.JaxSpecific").methodPaths());}
    @Test void jaxDiamondIsDeduplicated() {assertEquals(List.of("/a"),only("demo.JaxDiamond").methodPaths());}
    @Test void jaxGenericOverrideMatchesConcreteMethod() {assertEquals("(Ljava/lang/String;)Ljava/lang/String;",only("demo.JaxGenericImpl").descriptor());}
    @Test void javaxInheritanceWorks() {assertEquals(List.of("/legacy"),only("demo.LegacyImpl").methodPaths());}
    @Test void customJaxDesignatorIsInherited() {assertEquals(List.of("PURGE"),only("demo.PurgeImpl").httpMethods());}
    @Test void jaxPrivateMethodIsNotResourceMethod() {assertTrue(routes("demo.JaxPrivate").isEmpty());}
    @Test void missingParentDefinitionsDoNotInventInheritedRoutes() throws Exception {
        var rows=JandexScanner.extractFrameworkEndpoints(readIndex(Set.of("demo.Api")),Map.of("demo.Impl",1));
        assertTrue(rows.isEmpty());
    }
    @Test void externalBodyHasUnknownProjectCallOwner() {var r=JandexScanner.extractFrameworkEndpoints(index,Map.of("demo.Inherits",1)).getFirst();assertNull(r.declaringClassId());assertEquals("demo.Base",r.declaringClassName());}
}
