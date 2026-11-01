package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.jboss.jandex.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CdiBindingTest {
    @TempDir Path root;
    void source(String name, String text) throws Exception {
        Path p=root.resolve("src/"+name.replace('.','/')+".java");Files.createDirectories(p.getParent());Files.writeString(p,text);
    }
    Index index(String body) throws Exception {
        for(String name:List.of("jakarta.inject.Inject","jakarta.inject.Qualifier","jakarta.enterprise.inject.Produces","jakarta.enterprise.context.Dependent","jakarta.enterprise.util.Nonbinding")) {
            int dot=name.lastIndexOf('.'); source(name,"package "+name.substring(0,dot)+"; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface "+name.substring(dot+1)+" {}");
        }
        source("jakarta.enterprise.inject.Typed","package jakarta.enterprise.inject; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Typed {Class<?>[] value() default {};}");
        for(String name:List.of("IfBuildProfile","UnlessBuildProfile")) source("io.quarkus.arc.profile."+name,"package io.quarkus.arc.profile; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface "+name+" {String value();}");
        source("other.Flavor","package other; @jakarta.inject.Qualifier @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Flavor {String value() default \"red\";}");
        source("sample.Facts","package sample; import jakarta.inject.*; import jakarta.enterprise.inject.*; import jakarta.enterprise.context.*; "+body);
        Path out=Files.createDirectories(root.resolve("classes"));var args=new ArrayList<String>(List.of("-d",out.toString()));
        try(var files=Files.walk(root.resolve("src"))){files.filter(p->p.toString().endsWith(".java")).map(Path::toString).forEach(args::add);}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        Indexer index=new Indexer();try(var files=Files.walk(out)){for(Path p:files.filter(p->p.toString().endsWith(".class")).toList())try(var in=Files.newInputStream(p)){index.index(in);}}
        return index.complete();
    }
    @Test void qualifierDefaultsBindingMembersNonbindingAndQualifiedNames() throws Exception {
        var result=BeanResolver.resolve(index("""
            @Qualifier @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            @interface Flavor {String value() default "red"; @jakarta.enterprise.util.Nonbinding String note() default "";}
            interface Port {}
            @Dependent @Flavor class Red implements Port {}
            @Dependent @Flavor("blue") class Blue implements Port {}
            @Dependent @other.Flavor class Other implements Port {}
            @Dependent class Consumer {
              @Inject @Flavor(value="red",note="ignored") Port first;
              @Inject @Flavor("blue") Port second;
              @Inject @other.Flavor Port third;
            }
            """));
        for(var point:result.injectionPoints()) {
            var bean=result.beans().stream().filter(b->Objects.equals(b.id(),point.resolvedBeanId())).findFirst().orElseThrow();
            String expected=switch(point.fieldName()){case "first"->"sample.Red";case "second"->"sample.Blue";default->"sample.Other";};
            assertEquals(result.classNameToId().get(expected),bean.classId());
        }
        assertEquals(3,result.injectionPoints().size());
    }
    @Test void typedObjectArraysAndPrimitiveWrapperAssignability() throws Exception {
        var result=BeanResolver.resolve(index("""
            interface Port {}
            @Dependent @Typed(Port.class) class Restricted implements Port {}
            @Dependent class Factory { @Produces int number(){return 1;} @Produces String[] names(){return null;} }
            @Dependent class Consumer { @Inject Integer boxed; @Inject String[] names; }
            """));
        var restricted=result.beans().stream().filter(b->b.classId()==result.classNameToId().get("sample.Restricted")).findFirst().orElseThrow();
        assertEquals(Set.of("sample.Port","java.lang.Object"),new HashSet<>(restricted.beanTypes()));
        var number=result.beans().stream().filter(b->"number".equals(b.memberName())).findFirst().orElseThrow();
        assertEquals(Set.of("int","java.lang.Object"),new HashSet<>(number.beanTypes()));
        var names=result.beans().stream().filter(b->"names".equals(b.memberName())).findFirst().orElseThrow();
        assertEquals(Set.of("java.lang.String[]","java.lang.Object"),new HashSet<>(names.beanTypes()));
        result.injectionPoints().forEach(p->assertNotNull(p.resolvedBeanId(),p.toString()));
    }
    @Test void unscopedAndImplicitProducersRequireConfirmedFrameworkContext() throws Exception {
        Index index=index("""
            class Factory { @Produces String text(){return "";} @Dependent Integer number(){return 1;} }
            @Dependent class Consumer { @Inject String text; }
            """);
        assertTrue(BeanResolver.resolve(index).beans().stream().noneMatch(b->"text".equals(b.memberName())));
        var actual=BeanResolver.resolve(index,null,Map.of("sample.Factory","@Dependent"));
        assertEquals(2,actual.beans().stream().filter(b->b.kind().equals("PRODUCER_METHOD")).count());
        assertNotNull(actual.injectionPoints().getFirst().resolvedBeanId());
    }
    @Test void conditionalCandidatesNeedProfileEvenWhenThereIsOnlyOne() throws Exception {
        var result=BeanResolver.resolve(index("""
            @Dependent @io.quarkus.arc.profile.IfBuildProfile("dev") class Service {}
            @Dependent class Consumer { @Inject Service service; }
            """));
        var point=result.injectionPoints().getFirst();
        assertNull(point.resolvedBeanId());
        assertEquals(org.treblereel.mcp.model.ResolutionStatus.CONTEXT_REQUIRED,point.resolutionStatus());
    }
    @Test void compilerGeneratedBridgesAreNotAdditionalInitializerInjectionPoints() throws Exception {
        var result=BeanResolver.resolve(index("""
            interface Initializer<T>{void set(T value);}
            @Dependent class Consumer implements Initializer<String> { @Inject public void set(String value){} }
            """));
        assertEquals(1,result.injectionPoints().size());assertEquals("java.lang.String",result.injectionPoints().getFirst().targetType());
    }
    @Test void compilerGeneratedBridgesAreNotAdditionalProducerBeans() throws Exception {
        var result=BeanResolver.resolve(index("""
            interface Maker<T>{T make();}
            @Dependent class Factory implements Maker<String> { @Produces public String make(){return "";} }
            """));
        assertEquals(1,result.beans().stream().filter(b->b.kind().equals("PRODUCER_METHOD")).count());
    }
    @Test void differentGenericArgumentsNeverResolveByErasure() throws Exception {
        var result=BeanResolver.resolve(index("""
            @Dependent class Factory { @Produces java.util.List<Long> values(){return null;} }
            @Dependent class Consumer { @Inject java.util.List<String> names; }
            """));
        assertNull(result.injectionPoints().getFirst().resolvedBeanId());
    }
    @Test void producerConditionsIncludeDeclaringBeanAndMember() throws Exception {
        var result=BeanResolver.resolve(index("""
            @Dependent @io.quarkus.arc.profile.IfBuildProfile("prod") class Factory {
             @Produces @io.quarkus.arc.profile.UnlessBuildProfile("test") String value(){return "";}
            }
            """));
        var product=result.beans().stream().filter(b->"value".equals(b.memberName())).findFirst().orElseThrow();
        assertEquals(Set.of("prod","!test"),new HashSet<>(product.profiles()));
        assertEquals("@Dependent",product.scope());
    }
}
