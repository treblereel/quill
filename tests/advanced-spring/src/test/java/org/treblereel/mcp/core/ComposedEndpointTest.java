package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.*;
import java.util.*;
import org.jboss.jandex.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AliasFor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.*;

/** Compares bytecode extraction against Spring's actual merged annotation semantics. */
class ComposedEndpointTest {
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
    @RequestMapping(value="/meta", method=RequestMethod.GET)
    @interface Custom {
        @AliasFor(annotation=RequestMapping.class, attribute="path") String[] route() default {};
    }
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    @Custom
    @interface Nested {
        @AliasFor(annotation=Custom.class, attribute="route") String[] location() default {};
    }
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    @RequestMapping(path="/get-meta",method=RequestMethod.GET)
    @interface ComposedGet {
        @AliasFor(annotation=RequestMapping.class, attribute="path") String[] route() default {};
    }
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    @RequestMapping(method=RequestMethod.POST)
    @interface Local {
        @AliasFor(annotation=RequestMapping.class, attribute="path") String[] value() default {};
        @AliasFor(annotation=RequestMapping.class, attribute="path") String[] route() default {};
    }
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    @RequestMapping(method=RequestMethod.GET)
    @interface InvalidLocal {
        @AliasFor("route") String[] value() default {};
        @AliasFor(annotation=RequestMapping.class, attribute="path") String[] route() default {};
    }
    static class Controller {
        @Custom(route="/custom") public void custom() {}
        @Custom public void fixedDefault() {}
        @Nested(location="/nested") public void nested() {}
        @ComposedGet(route="/get") public void get() {}
        @Local("/local") public void local() {}
        @InvalidLocal("/invalid") public void invalid() {}
    }

    private void matchesSpring(String method) throws Exception {
        Indexer indexer = new Indexer();
        for (Class<?> type : List.of(Controller.class, Custom.class, Nested.class, ComposedGet.class,
                Local.class, InvalidLocal.class, RequestMapping.class, GetMapping.class, AliasFor.class)) indexer.indexClass(type);
        Index index = indexer.complete();
        var record = JandexScanner.extractFrameworkEndpoints(index, Map.of(Controller.class.getName(), 1))
                .stream().filter(r -> r.methodName().equals(method)).findFirst().orElseThrow();
        RequestMapping merged = AnnotatedElementUtils.findMergedAnnotation(
                Controller.class.getDeclaredMethod(method), RequestMapping.class);
        assertNotNull(merged);
        assertEquals(merged.path().length == 0 ? List.of("") : Arrays.asList(merged.path()), record.methodPaths());
        assertEquals(Arrays.stream(merged.method()).map(Enum::name).sorted().toList(), record.httpMethods());
    }
    @Test void invalidLocalAndExternalAliasCombinationIsOmitted() throws Exception {
        assertThrows(org.springframework.core.annotation.AnnotationConfigurationException.class,
                () -> AnnotatedElementUtils.findMergedAnnotation(
                        Controller.class.getDeclaredMethod("invalid"), RequestMapping.class));
        Indexer indexer = new Indexer();
        for (Class<?> type : List.of(Controller.class, InvalidLocal.class, RequestMapping.class, AliasFor.class))
            indexer.indexClass(type);
        assertTrue(JandexScanner.extractFrameworkEndpoints(indexer.complete(), Map.of(Controller.class.getName(), 1))
                .stream().noneMatch(r -> r.methodName().equals("invalid")));
    }
    @Test void customAliasMatchesSpring() throws Exception {matchesSpring("custom");}
    @Test void emptyAliasDefaultOverridesMetaPath() throws Exception {matchesSpring("fixedDefault");}
    @Test void nestedAliasMatchesSpring() throws Exception {matchesSpring("nested");}
    @Test void composedGetMappingMatchesSpring() throws Exception {matchesSpring("get");}
    @Test void explicitOverridesForSameAttributeMatchSpring() throws Exception {matchesSpring("local");}
}
