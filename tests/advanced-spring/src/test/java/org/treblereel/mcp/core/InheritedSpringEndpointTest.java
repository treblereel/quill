package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.jboss.jandex.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.*;
import org.springframework.web.bind.annotation.*;

/** Uses the same TYPE_HIERARCHY annotation stream as Spring's handler mapping. */
class InheritedSpringEndpointTest {
    @RequestMapping("/iface") interface Api {@GetMapping("/read") String read(String value);}
    static class Impl implements Api {public String read(String value) {return value;}}
    @RequestMapping("/own") static class OwnType implements Api {public String read(String value) {return value;}}
    @RequestMapping("/base") static class Base {@PostMapping("/base-read") public String read(String value) {return value;}}
    static class Inherits extends Base {}
    static class PlainBase {public String read(String value) {return value;}}
    static class AddedInterface extends PlainBase implements Api {}
    static class OverrideBody extends Base {public String read(String value) {return value;}}
    static class Mixed extends Base implements Api {public String read(String value) {return value;}}
    static class OwnMethod extends Base {@GetMapping("/own-read") public String read(String value) {return value;}}
    interface Generic<T> {@GetMapping("/generic") T read(T value);}
    interface Middle<T> extends Generic<T> {}
    static class GenericImpl implements Middle<String> {public String read(String value) {return value;} public String read(Integer value) {return value.toString();}}
    interface DefaultApi {@GetMapping("/default") default String read(String value) {return value;}}
    static class DefaultImpl implements DefaultApi {}
    interface Left extends Api {} interface Right extends Api {}
    static class Diamond implements Left,Right {public String read(String value) {return value;}}
    interface ChildApi extends Api {@PostMapping("/child") String read(String value);}
    static class Specific implements ChildApi {public String read(String value) {return value;}}

    static RequestMapping spring(java.lang.reflect.AnnotatedElement element) {
        return MergedAnnotations.from(element, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                .stream(RequestMapping.class).findFirst().map(MergedAnnotation::synthesize).orElse(null);
    }
    private void matches(Class<?> owner) throws Exception {
        Indexer indexer=new Indexer();
        for (Class<?> type : List.of(owner, Api.class, Base.class, Generic.class, Middle.class, DefaultApi.class,
                Left.class,Right.class,ChildApi.class,RequestMapping.class,GetMapping.class,PostMapping.class,AliasFor.class)) indexer.indexClass(type);
        var rows=JandexScanner.extractFrameworkEndpoints(indexer.complete(),Map.of(owner.getName(),1));
        assertEquals(1,rows.size());
        var record=rows.getFirst();
        RequestMapping method=spring(owner.getMethod("read",String.class));
        RequestMapping type=spring(owner);
        assertNotNull(method);
        assertEquals(Arrays.asList(method.path()),record.methodPaths());
        assertEquals(type==null ? List.of() : Arrays.asList(type.path()),record.classPaths());
        Set<String> verbs=new TreeSet<>();
        Arrays.stream(method.method()).map(Enum::name).forEach(verbs::add);
        if (type!=null) Arrays.stream(type.method()).map(Enum::name).forEach(verbs::add);
        assertEquals(List.copyOf(verbs),record.httpMethods());
        assertEquals(owner.getMethod("read",String.class).getDeclaringClass().getName(),record.declaringClassName());
    }
    @Test void interfaceMappingMatchesSpring() throws Exception {matches(Impl.class);}
    @Test void ownClassPathMatchesSpring() throws Exception {matches(OwnType.class);}
    @Test void inheritedUnannotatedBodyUsesItsDeclarationHierarchyInSpring() throws Exception {
        assertNull(spring(AddedInterface.class.getMethod("read",String.class)));
        Indexer indexer=new Indexer();
        for (Class<?> type : List.of(AddedInterface.class,PlainBase.class,Api.class,RequestMapping.class,GetMapping.class)) indexer.indexClass(type);
        assertTrue(JandexScanner.extractFrameworkEndpoints(indexer.complete(),Map.of(AddedInterface.class.getName(),1)).isEmpty());
    }
    @Test void inheritedBodyMatchesSpring() throws Exception {matches(Inherits.class);}
    @Test void overriddenBodyMatchesSpring() throws Exception {matches(OverrideBody.class);}
    @Test void interfaceAndSuperclassPrecedenceMatchesSpring() throws Exception {matches(Mixed.class);}
    @Test void ownMethodMappingMatchesSpring() throws Exception {matches(OwnMethod.class);}
    @Test void genericOverrideMatchesSpring() throws Exception {matches(GenericImpl.class);}
    @Test void interfaceDefaultMethodMatchesSpring() throws Exception {matches(DefaultImpl.class);}
    @Test void diamondMatchesSpring() throws Exception {matches(Diamond.class);}
    @Test void childInterfaceMatchesSpring() throws Exception {matches(Specific.class);}
}
