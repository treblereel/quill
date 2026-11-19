package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.jboss.jandex.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarkusRestDiscoveryTest {
    @TempDir Path root;
    void source(String name, String body) throws Exception {
        Path file = root.resolve("src/" + name.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());Files.writeString(file, body);
    }
    Index fixture() throws Exception {
        for (String name : List.of("jakarta.ws.rs.Path", "jakarta.ws.rs.ApplicationPath", "jakarta.ws.rs.ext.Provider",
                "jakarta.ws.rs.QueryParam", "jakarta.ws.rs.core.Context", "jakarta.inject.Inject",
                "jakarta.enterprise.context.ApplicationScoped", "jakarta.enterprise.inject.Vetoed",
                "jakarta.decorator.Decorator", "jakarta.decorator.Delegate", "jakarta.interceptor.Interceptor")) {
            int dot = name.lastIndexOf('.');String simple = name.substring(dot + 1);
            source(name, "package " + name.substring(0,dot) + "; import java.lang.annotation.*; " + (simple.equals("ApplicationScoped") ? "@Inherited " : "") + "@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE,ElementType.FIELD,ElementType.PACKAGE,ElementType.CONSTRUCTOR,ElementType.PARAMETER}) public @interface " + simple + " { String value() default \"\"; }");
        }
        source("catalog.Resources", """
                package catalog;
                import jakarta.ws.rs.*;
                import jakarta.inject.Inject;
                import jakarta.enterprise.context.ApplicationScoped;
                @ApplicationScoped class Service {}
                @Path("/items") class Resource { @Inject Service service; }
                @Path("/scoped") @ApplicationScoped class ScopedResource {}
                @Path("/param") class ParamResource { @QueryParam("q") String q; }
                @Path("/context") class ContextResource { @jakarta.ws.rs.core.Context Object context; }
                @jakarta.ws.rs.ext.Provider class Provider { @QueryParam("q") String q; }
                @ApplicationPath("/api") class Application {}
                @Path("/veto") @jakarta.enterprise.inject.Vetoed class VetoResource {}
                @Path("/abstract") abstract class AbstractRoot {}
                class OnlyChild extends AbstractRoot {}
                @ApplicationScoped abstract class ScopedParent {}
                @Path("/inherited") class ScopedChild extends ScopedParent {}
                @Path("/ambiguous") abstract class AmbiguousRoot {}
                class ChildA extends AmbiguousRoot {} class ChildB extends AmbiguousRoot {}
                @Path("/contract") interface Api {} class ApiImpl implements Api {}
                @Path("/ambcontract") interface AmbApi {} class ApiA implements AmbApi {} class ApiB implements AmbApi {}
                @Path("/client") interface Client {}
                class Nested { @Path("/inner") class Inner {} }
                """);
        source("vetoed.package-info", "@jakarta.enterprise.inject.Vetoed package vetoed;");
        source("vetoed.Resource", "package vetoed; @jakarta.ws.rs.Path(\"/no\") public class Resource {}");
        Path classes = Files.createDirectories(root.resolve("classes"));var args = new ArrayList<String>(List.of("-d",classes.toString()));
        try (var paths=Files.walk(root.resolve("src"))) { paths.filter(p->p.toString().endsWith(".java")).map(Path::toString).forEach(args::add); }
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        Indexer index = new Indexer();
        try(var paths=Files.walk(classes)) { for(Path p: paths.filter(p->p.toString().endsWith(".class")).toList()) try(var in=Files.newInputStream(p)){index.index(in);} }
        return index.complete();
    }
    @Test void ordinaryCdiIsUnchangedAndQuarkusUsesRealResourceBoundaries() throws Exception {
        Index index=fixture();Set<String> names=new HashSet<>();index.getKnownClasses().forEach(c->names.add(c.name().toString()));
        var ordinary=BeanResolver.resolve(index);assertEquals(2,ordinary.beans().size());
        var scopes=QuarkusRestDiscovery.scopes(index,names);
        assertEquals("@Singleton",scopes.get("catalog.Resource"));assertEquals("@RequestScoped",scopes.get("catalog.ParamResource"));
        assertEquals("@Singleton",scopes.get("catalog.ContextResource"));assertEquals("@Singleton",scopes.get("catalog.Provider"));
        assertEquals("@Singleton",scopes.get("catalog.Application"));assertEquals("@Singleton",scopes.get("catalog.OnlyChild"));
        assertEquals("@Dependent",scopes.get("catalog.ApiImpl"));
        for(String name:List.of("Client","Api","AmbApi","ApiA","ApiB","VetoResource","AbstractRoot","ChildA","ChildB","Nested$Inner")) assertFalse(scopes.containsKey("catalog."+name),name);
        assertFalse(scopes.containsKey("vetoed.Resource"));
        var actual=BeanResolver.resolve(index,null,scopes);var byName=new HashMap<String,org.treblereel.mcp.model.BeanRecord>();
        actual.beans().forEach(b->actual.classNameToId().forEach((n,id)->{if(id==b.classId())byName.put(n,b);}));
        assertEquals("@ApplicationScoped",byName.get("catalog.ScopedResource").scope());
        assertEquals("@ApplicationScoped",byName.get("catalog.ScopedChild").scope());
        assertEquals(1,actual.injectionPoints().size());assertNotNull(actual.injectionPoints().getFirst().resolvedBeanId());
        assertEquals(Map.of(),QuarkusRestDiscovery.scopes(index,Set.of()));
        assertEquals(Set.of("catalog.Resource"),QuarkusRestDiscovery.scopes(index,Set.of("catalog.Resource")).keySet());
    }
    @Test void decoratorsAndInterceptorsAreNotInjectionCandidatesAndDelegatesStayExplicit() throws Exception {
        source("catalog.Decorated", """
                package catalog;
                interface Port {}
                @jakarta.enterprise.context.ApplicationScoped class RealPort implements Port {}
                @jakarta.enterprise.context.ApplicationScoped class Consumer { @jakarta.inject.Inject Port port; }
                @jakarta.decorator.Decorator abstract class Wrapper implements Port {
                    @jakarta.inject.Inject @jakarta.decorator.Delegate Port delegate;
                    @jakarta.inject.Inject Service service;
                }
                @jakarta.decorator.Decorator class ConstructorWrapper implements Port {
                    @jakarta.inject.Inject ConstructorWrapper(@jakarta.decorator.Delegate Port delegate) {}
                }
                @jakarta.interceptor.Interceptor class Around implements Port {}
                """);
        var result = BeanResolver.resolve(fixture());
        var port = result.injectionPoints().stream().filter(p -> p.fieldName().equals("port")).findFirst().orElseThrow();
        assertEquals(org.treblereel.mcp.model.ResolutionStatus.RESOLVED, port.resolutionStatus());
        int real = result.beans().stream().filter(b -> b.classId() == result.classNameToId().get("catalog.RealPort")).findFirst().orElseThrow().id();
        assertEquals(real, port.resolvedBeanId());
        assertEquals(3, port.resolutionTrace().candidates().stream().filter(c -> c.reason() == org.treblereel.mcp.model.ResolutionReason.NOT_INJECTION_BEAN).count());
        assertEquals(2, result.injectionPoints().stream().filter(p -> p.resolutionReason() == org.treblereel.mcp.model.ResolutionReason.DECORATOR_DELEGATE_CHAIN)
                .peek(p -> { assertNull(p.resolvedBeanId()); assertEquals(org.treblereel.mcp.model.ResolutionStatus.UNSUPPORTED_MECHANISM,p.resolutionStatus()); }).count());
        assertTrue(result.injectionPoints().stream().anyMatch(p -> p.fieldName().equals("service") && p.resolvedBeanId()!=null));
    }

}
