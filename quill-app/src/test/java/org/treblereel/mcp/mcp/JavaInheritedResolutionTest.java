package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.*;
import org.treblereel.mcp.analysis.JvmMethodResolver;

class JavaInheritedResolutionTest {
    @TempDir Path root;
    private static final ObjectMapper JSON=new ObjectMapper();
    Jdbi db;
    @BeforeEach void setup() throws Exception {
        db=JavaEvidenceFixture.index(root,"""
                package p;
                interface Contract { default Number run(){return 0;} }
                interface Narrow extends Contract { default Number run(){return 1;} }
                class Base { public Number run(){return 2;} public static void utility(){} }
                class Child extends Base {}
                class GrandChild extends Child {public Number run(){return 4;}}
                class Override extends Base { public Number run(){return 3;} }
                class Defaults implements Narrow {}
                class Generic<T> { public T get(){return null;} }
                class Strings extends Generic<String> { public String get(){return "ok";} }
                class Use {
                    Number inherited(Child c){return c.run();}
                    Number base(Base b){return b.run();}
                    Number overridden(Override o){return o.run();}
                    Number defaults(Defaults d){return d.run();}
                    void utility(){Child.utility();}
                    Object bridge(Strings s){return ((Generic<String>)s).get();}
                }
                """);
    }
    @Test void inheritedReferencesReachBaseButOverridesStaySeparateAndPagesKeepRawOwners() throws Exception {
        var response=JSON.readTree(ReadQueryContext.<String>readWithGraph(db, readScope -> new SymbolUsageQueries().findSymbolUsages(readScope, "p.Base", "run", "method", "()Ljava/lang/Number;", "all", 1, 0)));
        assertEquals(2,response.path("total").asInt(),response.toString()); assertTrue(response.path("has_more").asBoolean());
        var next=JSON.readTree(ReadQueryContext.<String>readWithGraph(db, readScope -> new SymbolUsageQueries().findSymbolUsages(readScope, "p.Base", "run", "method", "()Ljava/lang/Number;", "all", 1, 1)));
        assertEquals(2,next.path("total").asInt()); assertFalse(next.path("has_more").asBoolean());
        var calls=JSON.readTree(ReadQueryContext.<String>readWithGraph(db, readScope -> new CallHierarchyQueries().getCallHierarchy(readScope, "p.Use", "inherited", null, "outbound", false, 1, 20, 0)));
        var dispatch=calls.path("calls").get(0).path("dispatch");
        assertEquals("p.Child",dispatch.path("declared_target").path("class").asText());
        assertEquals("p.Base",dispatch.path("resolved_declaration").path("class").asText());
        assertEquals(java.util.Set.of("p.GrandChild"),new java.util.HashSet<>(dispatch.path("possible_targets").valueStream().map(n->n.path("class").asText()).toList()));
        assertEquals("inherited_class_declaration",dispatch.path("declaration_resolution").asText());
        assertFalse(dispatch.path("runtime_target_proven").asBoolean());
        var inbound=JSON.readTree(ReadQueryContext.<String>readWithGraph(db, readScope -> new CallHierarchyQueries().getCallHierarchy(readScope, "p.Base", "run", null, "inbound", false, 1, 20, 0)));
        assertEquals(2,inbound.path("total").asInt(),inbound.toString());
        var both=JSON.readTree(ReadQueryContext.<String>readWithGraph(db, readScope -> new CallHierarchyQueries().getCallHierarchy(readScope, "p.Base", "run", null, "both", false, 1, 20, 0)));
        assertEquals(2,both.path("total").asInt(),both.toString());
    }
    @Test void defaultsUseMostSpecificInterfaceAndClassStaticMethodsResolve() {
        var resolver=new JvmMethodResolver(ReadQueryContext.readWithGraph(db, context -> context.graph()));
        assertEquals("p.Narrow",resolver.resolve("p.Defaults","run","()Ljava/lang/Number;").owner().className());
        assertEquals("p.Base",resolver.resolve("p.Child","utility","()V").owner().className());
        assertFalse(resolver.resolve("p.Child","<init>","(I)V").resolved());
    }
    @Test void bridgesAreRetainedAndExposeBytecodeForwardingWithoutGuessingGenericTargets() throws Exception {
        int id=db.withHandle(repositoryHandle1 -> new SymbolRepository(repositoryHandle1).findAllClasses()).stream().filter(c->c.className().equals("p.Strings")).findFirst().orElseThrow().id();
        assertEquals(1,db.withHandle(repositoryHandle2 -> new SymbolRepository(repositoryHandle2).findClassMembers(id)).stream().filter(m->m.name().equals("get")).count());
        assertEquals(2,db.withHandle(repositoryHandle3 -> new SymbolRepository(repositoryHandle3).findJvmClassMembers(id)).stream().filter(m->m.name().equals("get")).count());
        var calls=JSON.readTree(ReadQueryContext.<String>readWithGraph(db, readScope -> new CallHierarchyQueries().getCallHierarchy(readScope, "p.Strings", "get", "()Ljava/lang/Object;", "outbound", false, 1, 20, 0)));
        assertEquals("()Ljava/lang/String;",calls.path("calls").get(0).path("callee").path("descriptor").asText());
        // Explicit bridge queries are supported; ordinary method selection remains unambiguous.
        var usages=JSON.readTree(ReadQueryContext.<String>readWithGraph(db, readScope -> new SymbolUsageQueries().findSymbolUsages(readScope, "p.Strings", "get", "method", null, "all", 20, 0)));
        assertFalse(usages.has("error_code"),usages.toString());
    }
    @Test void interfaceCyclesAndStaticInterfaceMembersAreNotInherited() {
        db.useHandle(h->{
            h.execute("UPDATE classes SET interfaces='[\"p.Narrow\"]' WHERE class_name='p.Contract'");
            h.execute("INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) SELECT id,'METHOD','utility','utility():void','()V','void','[]','public static','[]' FROM classes WHERE class_name='p.Contract'");
        });
        var resolver=new JvmMethodResolver(ReadQueryContext.readWithGraph(db, context -> context.graph()));
        assertEquals("hierarchy_cycle",resolver.resolve("p.Defaults","run","()Ljava/lang/Number;").status());
        assertFalse(resolver.resolve("p.Defaults","utility","()V").resolved());
    }
    @Test void duplicateOwnersAndMissingAncestorsStayUnknown() {
        db.useHandle(h->h.execute("INSERT INTO classes(class_name,kind,interfaces) VALUES ('p.Base','CLASS','[]')"));
        var resolver=new JvmMethodResolver(ReadQueryContext.readWithGraph(db, context -> context.graph()));
        assertEquals("owner_ambiguous",resolver.resolve("p.Child","run","()Ljava/lang/Number;").status());
        assertEquals("ancestor_not_indexed",resolver.resolve("p.Missing","run","()V").status());
    }
    @Test void cyclesAndUnrelatedInterfaceDeclarationsAreNotArbitrarilySelected() {
        db.useHandle(h->{
            h.execute("UPDATE classes SET superclass='p.Child' WHERE class_name='p.Base'");
            h.execute("INSERT INTO classes(class_name,kind,interfaces) VALUES ('p.Mixed','CLASS','[\"p.Contract\",\"p.Other\"]')");
            h.execute("INSERT INTO classes(class_name,kind,interfaces) VALUES ('p.Other','INTERFACE','[]')");
            h.execute("INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) SELECT id,'METHOD','run','run():java.lang.Number','()Ljava/lang/Number;','java.lang.Number','[]','public abstract','[]' FROM classes WHERE class_name='p.Other'");
        });
        var resolver=new JvmMethodResolver(ReadQueryContext.readWithGraph(db, context -> context.graph()));
        assertEquals("hierarchy_cycle",resolver.resolve("p.Child","absent","()V").status());
        assertEquals("interface_declaration_ambiguous",resolver.resolve("p.Mixed","run","()Ljava/lang/Number;").status());
    }
}
