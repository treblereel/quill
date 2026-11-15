package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;
import org.treblereel.mcp.mcp.semantic.JavaSemanticQueries;

class MethodPrefetchRelationsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @org.junit.jupiter.api.AfterEach
    void stop() {
        JavaSemanticQueries.close();
    }

    @Test
    void exposesMissingExternalOnlyLambdaEvidence() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; class Fixture { void start() { Runnable r = () -> System.out.println(1); r.run(); } }");
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("class", "p.Fixture")
                        .put("name", "start")
                        .put("descriptor", "()V");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, root, fact);
                    return null;
                });
        assertTrue(fact.path("lambda_evidence_gap").asBoolean(), fact.toString());
        assertTrue(fact.has("selected_method_source") || fact.has("selected_method_source_status"));
    }

    /**
     * V24-V27 CQ-049: after Quill listed every call of parse, Claude grepped \\bparse\\b 8 times
     * for comments and strings.
     */
    @Test
    void aMethodFactListsWhereItsNameAppearsOutsideCodeInItsFile() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        """
                package p;
                class Mapper {
                    /** Delegates to {@link #parse(String)}. */
                    Object resolve(String v) { return parse(v); }
                    // parse keeps the subresource
                    private Object parse(String v) { return "parse failed".isEmpty() ? null : v; }
                    Object parser() { return null; }
                }
                """);
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("class", "p.Mapper")
                        .put("name", "parse")
                        .put("descriptor", "(Ljava/lang/String;)Ljava/lang/Object;");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, root, fact);
                    return null;
                });

        JsonNode mentions = fact.path("name_in_source");
        assertEquals(
                fact.path("selected_method_source").path("source_file").asText(),
                mentions.path("file").asText(),
                fact.toString());
        assertEquals(2, mentions.path("code_lines").size(), fact.toString());
        assertEquals(
                List.of("3 comment", "5 comment", "6 string"),
                mentions.path("other").valueStream().map(JsonNode::asText).toList(),
                fact.toString());
    }

    @Test
    void methodFactCarriesTheSelectedBodySoTheFileNeedNotBeOpened() throws Exception {
        String longBody = "System.out.println(\"" + "x".repeat(3000) + "\");";
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p;\nclass Fixture {\n    void start() { helper(); }\n    void helper() {}\n    void large() { "
                                + longBody
                                + " }\n}\n");
        JavaEvidenceFixture.writeCompilerSettings(root);
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("class", "p.Fixture")
                        .put("name", "start")
                        .put("descriptor", "()V");
        ObjectNode large =
                JSON.createObjectNode()
                        .put("class", "p.Fixture")
                        .put("name", "large")
                        .put("descriptor", "()V");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, root, fact);
                    MethodPrefetchRelations.append(reader, root, large);
                    return null;
                });

        JsonNode body = fact.path("selected_method_source");
        assertEquals("void start() { helper(); }", body.path("source").asText(), fact.toString());
        assertEquals("src/main/java/p/Fixture.java", body.path("source_file").asText());
        assertEquals(3, body.path("source_line").asInt());
        assertEquals(3, body.path("source_end_line").asInt());
        assertFalse(body.has("declaration"), "Compiler bookkeeping is not part of the body");
        assertFalse(large.has("selected_method_source"));
        assertEquals(
                "source_budget",
                large.path("selected_method_source_status").asText(),
                large.toString());
    }

    /**
     * V41 CQ-016: asked not to mix the produced scope with the declaring class's, no Quill answer
     * named the latter.
     */
    @Test
    void producerFactStatesTheDeclaringClassBeanScope() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; class Gen {} class Producers { static Gen gen() { return new Gen(); } }");
        db.useHandle(
                handle -> {
                    int producers =
                            handle.createQuery(
                                            "SELECT id FROM classes WHERE class_name = 'p.Producers'")
                                    .mapTo(Integer.class)
                                    .one();
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types) VALUES (?, 'CLASS', '@Dependent', '[\"@Default\",\"@Any\"]', '[\"p.Producers\"]')",
                            producers);
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name) "
                                    + "VALUES (?, 'PRODUCER_METHOD', '@RequestScoped', '[\"@Default\",\"@Any\"]', '[\"p.Gen\"]', ?, 'gen')",
                            producers,
                            producers);
                });
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("class", "p.Producers")
                        .put("name", "gen")
                        .put("descriptor", "()Lp/Gen;");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, root, fact);
                    return null;
                });

        assertTrue(
                fact.path("produces").get(0).asText().startsWith("PRODUCER_METHOD @RequestScoped"),
                fact.toString());
        assertEquals(
                "CLASS @Dependent", fact.path("declaring_class_bean").asText(), fact.toString());
        assertTrue(
                fact.path("declaring_class_injects")
                        .path(0)
                        .asText()
                        .startsWith("not needed: static producer"),
                fact.toString());
    }

    /**
     * Apollo v3 AQ-005: asked whether a @ConditionalOnMissingBean producer registers under some
     * profiles, Claude read every auth configuration for its @Profile.
     */
    @Test
    void producerFactListsOtherProvidersOfItsTypeWithTheirConditions() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; class Holder {} class AuthConfig { Holder holder() { return null; } } "
                                + "class LdapConfig { Holder holder() { return null; } } class DefaultConfig { Holder holder() { return null; } }");
        db.useHandle(
                handle -> {
                    String[][] rows = {
                        {"p.AuthConfig", "[\"auth\"]"},
                        {"p.LdapConfig", "[\"ldap\"]"},
                        {"p.DefaultConfig", null}
                    };
                    for (String[] row : rows) {
                        int id =
                                handle.createQuery("SELECT id FROM classes WHERE class_name = :n")
                                        .bind("n", row[0])
                                        .mapTo(Integer.class)
                                        .one();
                        handle.execute(
                                "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) VALUES (?, 'CLASS', '@Singleton', '[]', ?, 'spring')",
                                id,
                                "[\"" + row[0] + "\"]");
                        handle.execute(
                                "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, profiles, framework) "
                                        + "VALUES (?, 'PRODUCER_METHOD', '@Singleton', '[]', '[\"p.Holder\"]', ?, 'holder', ?, 'spring')",
                                id,
                                id,
                                row[1]);
                    }
                    handle.execute(
                            "INSERT INTO class_annotations(class_id, annotation_name, direct, annotation_values) SELECT id, "
                                    + "'org.springframework.context.annotation.Profile', 1, '{\"value\":[\"auth\"]}' FROM classes WHERE class_name = 'p.AuthConfig'");
                    handle.execute(
                            "INSERT INTO class_annotations(class_id, annotation_name, direct, annotation_values) SELECT id, "
                                    + "'com.acme.condition.ConditionalOnMissingProfile', 1, '{\"value\":[\"auth\",\"ldap\"]}' FROM classes WHERE class_name = 'p.DefaultConfig'");
                    handle.execute(
                            "INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolved_bean_id, resolution_status, "
                                    + "resolution_strategy, resolution_reason, resolution_confidence) SELECT ob.id, 'FIELD', 'p.Holder', '[\"@Default\"]', 'holder', pb.id, "
                                    + "'RESOLVED', 'STATIC_SPRING', 'UNIQUE_CONTEXT_CANDIDATE', 'HIGH' FROM beans ob JOIN classes oc ON oc.id = ob.class_id, beans pb "
                                    + "JOIN classes pc ON pc.id = pb.declaring_class_id WHERE oc.class_name = 'p.LdapConfig' AND ob.kind = 'CLASS' "
                                    + "AND pc.class_name = 'p.AuthConfig' AND pb.kind = 'PRODUCER_METHOD'");
                    // Apollo AQ-005: another @Bean method's parameters were listed as the declaring
                    // bean's injections.
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, framework) "
                                    + "SELECT id, 'PRODUCER_METHOD', '@Singleton', '[]', '[\"p.Encoder\"]', id, 'encoder', 'spring' FROM classes WHERE class_name = 'p.AuthConfig'");
                    handle.execute(
                            "INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolution_status, resolution_strategy, "
                                    + "resolution_reason, resolution_confidence) SELECT b.id, 'METHOD_PARAM', 'javax.sql.DataSource', '[]', 'encoder', 'UNKNOWN', "
                                    + "'STATIC_SPRING', 'NO_CANDIDATE', 'LOW' FROM beans b JOIN classes c ON c.id = b.class_id WHERE c.class_name = 'p.AuthConfig' AND b.kind = 'CLASS'");
                });
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("class", "p.AuthConfig")
                        .put("name", "holder")
                        .put("descriptor", "()Lp/Holder;");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, root, fact);
                    return null;
                });

        assertEquals(
                "[\"@Profile([auth])\"]",
                fact.path("declaring_class_conditions").toString(),
                fact.toString());
        String providers = fact.path("same_type_providers").toString();
        assertTrue(providers.contains("p.LdapConfig#holder profiles [ldap]"), providers);
        assertTrue(
                providers.contains(
                        "p.DefaultConfig#holder class @ConditionalOnMissingProfile([auth, ldap])"),
                providers);
        assertTrue(
                fact.path("same_type_providers_basis")
                        .asText()
                        .contains("@ConditionalOnMissingBean"),
                fact.toString());
        // Apollo v1: CDI words on Spring beans ("@Default", "@Singleton", PRODUCER_METHOD) were
        // repeated as fact.
        assertTrue(
                fact.path("produces").path(0).asText().startsWith("BEAN_METHOD singleton"),
                fact.toString());
        assertFalse(fact.path("produces").toString().contains("@Default"), fact.toString());
        assertEquals(
                "CLASS singleton", fact.path("declaring_class_bean").asText(), fact.toString());
        assertTrue(
                fact.path("produced_bean_injected_into")
                        .path(0)
                        .asText()
                        .startsWith("p.LdapConfig#holder"),
                fact.toString());
        assertFalse(
                fact.path("produced_bean_injected_into").toString().contains("@Default"),
                fact.toString());
        assertFalse(
                fact.path("declaring_class_injects").toString().contains("encoder"),
                fact.toString());
    }

    /**
     * V62 CQ-018: every session called list_injection_points on Producers only to learn the
     * declaring bean injects nothing.
     */
    @Test
    void instanceProducerFactStatesWhatItsDeclaringBeanInjects() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; class Gen {} class Dep {} class Producers { Gen gen() { return new Gen(); } } class Factory { Dep dep; Gen make() { return new Gen(); } }");
        db.useHandle(
                handle -> {
                    for (String owner : java.util.List.of("p.Producers", "p.Factory")) {
                        int id =
                                handle.createQuery("SELECT id FROM classes WHERE class_name = :n")
                                        .bind("n", owner)
                                        .mapTo(Integer.class)
                                        .one();
                        handle.execute(
                                "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types) VALUES (?, 'CLASS', '@ApplicationScoped', '[\"@Default\"]', ?)",
                                id,
                                "[\"" + owner + "\"]");
                        handle.execute(
                                "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name) "
                                        + "VALUES (?, 'PRODUCER_METHOD', '@Dependent', '[\"@Default\"]', '[\"p.Gen\"]', ?, ?)",
                                id,
                                id,
                                owner.equals("p.Producers") ? "gen" : "make");
                    }
                    int factoryBean =
                            handle.createQuery(
                                            "SELECT b.id FROM beans b JOIN classes c ON c.id=b.class_id WHERE c.class_name='p.Factory' AND b.kind='CLASS'")
                                    .mapTo(Integer.class)
                                    .one();
                    handle.execute(
                            "INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolution_status, resolution_strategy, resolution_reason, resolution_confidence) "
                                    + "VALUES (?, 'FIELD', 'p.Dep', '[\"@Default\"]', 'dep', 'UNKNOWN', 'STATIC_CDI', 'NO_CANDIDATE', 'LOW')",
                            factoryBean);
                });
        ObjectNode none =
                JSON.createObjectNode()
                        .put("class", "p.Producers")
                        .put("name", "gen")
                        .put("descriptor", "()Lp/Gen;");
        ObjectNode some =
                JSON.createObjectNode()
                        .put("class", "p.Factory")
                        .put("name", "make")
                        .put("descriptor", "()Lp/Gen;");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, root, none);
                    MethodPrefetchRelations.append(reader, root, some);
                    return null;
                });

        assertEquals(
                "none: the declaring bean has no injection points",
                none.path("declaring_class_injects").path(0).asText(),
                none.toString());
        assertTrue(
                some.path("declaring_class_injects")
                        .path(0)
                        .asText()
                        .startsWith("FIELD dep: p.Dep"),
                some.toString());
    }

    /**
     * Without a descriptor one static overload must not hide what the instance overloads need, nor
     * may a cut list read as complete.
     */
    @Test
    void producerWithMixedOverloadsListsInjectionsAndMarksTruncation() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; class Gen {} class Producers { static Gen gen() { return new Gen(); } Gen gen(int i) { return new Gen(); } }");
        db.useHandle(
                handle -> {
                    int id =
                            handle.createQuery(
                                            "SELECT id FROM classes WHERE class_name = 'p.Producers'")
                                    .mapTo(Integer.class)
                                    .one();
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types) VALUES (?, 'CLASS', '@ApplicationScoped', '[\"@Default\"]', '[\"p.Producers\"]')",
                            id);
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name) "
                                    + "VALUES (?, 'PRODUCER_METHOD', '@Dependent', '[\"@Default\"]', '[\"p.Gen\"]', ?, 'gen')",
                            id,
                            id);
                    int bean =
                            handle.createQuery(
                                            "SELECT id FROM beans WHERE class_id = ? AND kind = 'CLASS'")
                                    .bind(0, id)
                                    .mapTo(Integer.class)
                                    .one();
                    for (int i = 0; i < 10; i++)
                        handle.execute(
                                "INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolution_status, resolution_strategy, resolution_reason, resolution_confidence) "
                                        + "VALUES (?, 'FIELD', 'p.Gen', '[\"@Default\"]', ?, 'UNKNOWN', 'STATIC_CDI', 'NO_CANDIDATE', 'LOW')",
                                bean,
                                "dep" + i);
                });
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("class", "p.Producers")
                        .put("name", "gen")
                        .put("descriptor", "");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, root, fact);
                    return null;
                });

        JsonNode lines = fact.path("declaring_class_injects");
        assertTrue(lines.path(0).asText().startsWith("overloads differ"), fact.toString());
        assertEquals(1 + 8 + 1, lines.size(), fact.toString());
        assertTrue(
                lines.path(lines.size() - 1).asText().startsWith("truncated: more than 8"),
                fact.toString());
    }

    @Test
    void methodFactListsAnnotationValuesAndCallsTwoLevelsDownThroughLambdas() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        """
                package p;
                class Base { void inherited() {} }
                class Fixture extends Base {
                    @Deprecated(since = "9")
                    void start() { helper(); parent(); new Base(); Runnable task = () -> other(); task.run(); }
                    void helper() { deep(); }
                    void deep() { leaf(); }
                    void leaf() {}
                    void parent() { inherited(); }
                    void other() { System.out.println("x"); }
                }
                """);
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("class", "p.Fixture")
                        .put("name", "start")
                        .put("descriptor", "()V");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, fact);
                    return null;
                });

        assertEquals(
                "@java.lang.Deprecated(since=9)",
                fact.path("annotations").get(0).asText(),
                fact.toString());
        List<String> calls = fact.path("calls").valueStream().map(node -> node.asText()).toList();
        assertTrue(calls.contains("1 p.Fixture#helper [virtual]"), calls.toString());
        assertTrue(calls.contains("1 p.Fixture#other [virtual]"), calls.toString());
        assertTrue(calls.contains("2 p.Fixture#deep [virtual]"), calls.toString());
        assertTrue(calls.contains("1 java.lang.Runnable#run():void [external]"), calls.toString());
        assertFalse(calls.contains("3 p.Fixture#leaf [virtual]"), calls.toString());
        assertTrue(
                calls.contains("2 p.Fixture#inherited [virtual, inherited: declared by p.Base]"),
                calls.toString());
        assertTrue(calls.contains("1 p.Base#<init> [special]"), calls.toString());
        assertTrue(calls.stream().noneMatch(call -> call.contains("lambda$")), calls.toString());
        // V41 CQ-027: a lambda's depth-1 callees came after the depth-2 ones, so a halved list lost
        // them.
        assertEquals(
                calls.stream()
                        .sorted(
                                java.util.Comparator.comparing(
                                        call -> call.substring(0, call.indexOf(' '))))
                        .toList(),
                calls,
                "depth order");
        assertEquals(
                "(java.nio.file.Path, java.nio.file.LinkOption[]):boolean",
                MethodPrefetchRelations.javaSignature(
                        "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"));
        assertEquals("", MethodPrefetchRelations.javaSignature("(Lbroken"));
    }

    @Test
    void firedEventObserversAreThoseOfTheEventFieldsTheMethodReads() throws Exception {
        String runtime =
                "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) ";
        var db =
                JavaEvidenceFixture.index(
                        root,
                        java.util.Map.of(
                                "src/main/java/jakarta/inject/Inject.java",
                                "package jakarta.inject; "
                                        + runtime
                                        + "public @interface Inject {}",
                                "src/main/java/jakarta/enterprise/context/ApplicationScoped.java",
                                "package jakarta.enterprise.context; "
                                        + runtime
                                        + "public @interface ApplicationScoped {}",
                                "src/main/java/jakarta/enterprise/event/Event.java",
                                "package jakarta.enterprise.event; public interface Event<T> { void fire(T event); void fireAsync(T event); }",
                                "src/main/java/jakarta/enterprise/event/Observes.java",
                                "package jakarta.enterprise.event; "
                                        + runtime
                                        + "public @interface Observes {}",
                                "src/main/java/p/Publisher.java",
                                """
                        package p;
                        import jakarta.enterprise.event.Event;
                        @jakarta.enterprise.context.ApplicationScoped
                        public class Publisher {
                            public record Created() {}
                            public record Deleted() {}
                            @jakarta.inject.Inject Event<Created> created;
                            @jakarta.inject.Inject Event<Deleted> deleted;
                            void create() { created.fire(new Created()); }
                            void createAsync() { created.fireAsync(new Created()); }
                            void delete() { deleted.fire(new Deleted()); }
                        }
                        """,
                                "src/main/java/p/Listener.java",
                                """
                        package p;
                        import jakarta.enterprise.event.Observes;
                        @jakarta.enterprise.context.ApplicationScoped
                        public class Listener {
                            void onCreated(@Observes Publisher.Created event) {}
                            void onDeleted(@Observes Publisher.Deleted event) {}
                        }
                        """));
        // The fixture indexes bytecode only, so the Publisher bean and its two Event points are
        // added here.
        db.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types) "
                                    + "SELECT id, 'CLASS', '@ApplicationScoped', '[\"@Default\"]', '[\"p.Publisher\"]' FROM classes WHERE class_name = 'p.Publisher'");
                    for (String field : List.of("created:Created", "deleted:Deleted"))
                        handle.execute(
                                "INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolution_status, "
                                        + "resolution_strategy, resolution_reason, resolution_confidence) SELECT b.id, 'FIELD', "
                                        + "'jakarta.enterprise.event.Event<p.Publisher$"
                                        + field.split(":")[1]
                                        + ">', '[\"@Default\"]', '"
                                        + field.split(":")[0]
                                        + "', 'RESOLVED', 'STATIC_CDI', 'BUILTIN_BEAN', 'high' FROM beans b "
                                        + "JOIN classes c ON c.id = b.class_id WHERE c.class_name = 'p.Publisher'");
                });
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("class", "p.Publisher")
                        .put("name", "create")
                        .put("descriptor", "()V");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, fact);
                    return null;
                });

        assertEquals(
                List.of(
                        "created jakarta.enterprise.event.Event<p.Publisher.Created> -> p.Listener#onCreated(@Observes p.Publisher.Created)"),
                fact.path("observers_of_fired_events")
                        .valueStream()
                        .map(node -> node.asText())
                        .toList(),
                fact.toString());
        assertEquals(
                "Event fields this method or its walked callees read",
                fact.path("observers_basis").asText());
        var event = fact.path("event_dispatches").get(0);
        assertEquals("p.Publisher$Created", event.path("event_type").asText());
        assertEquals("fire", event.path("dispatch_methods").get(0).asText());
        assertEquals(
                List.of("@Default", "@Any"),
                event.path("event_qualifiers").valueStream().map(n -> n.asText()).toList());
        assertTrue(event.path("entry_points").toString().contains("onCreated"));
        assertFalse(fact.path("event_delivery_proven").asBoolean());
        assertTrue(fact.path("event_delivery_semantics").asText().contains("synchronization"));
        var packet = JSON.createObjectNode();
        packet.putArray("facts").add(fact.deepCopy());
        PrefetchContext.boundContext(packet);
        assertTrue(
                packet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        <= PrefetchContext.CONTEXT_LIMIT_BYTES);
        assertTrue(packet.toString().contains("event_dispatches"));
        ObjectNode async =
                JSON.createObjectNode()
                        .put("class", "p.Publisher")
                        .put("name", "createAsync")
                        .put("descriptor", "()V");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, async);
                    return null;
                });
        assertEquals(
                List.of("fireAsync"),
                async.path("event_dispatches")
                        .get(0)
                        .path("dispatch_methods")
                        .valueStream()
                        .map(n -> n.asText())
                        .toList());
    }

    @Test
    void dependencyInterceptorsBoundByTheMethodAnnotationAreListedByPriority() throws Exception {
        String runtime =
                "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) ";
        var db =
                JavaEvidenceFixture.index(
                        root,
                        java.util.Map.of(
                                "src/main/java/tx/Tx.java",
                                "package tx; "
                                        + runtime
                                        + "public @interface Tx { String value() default \"REQUIRED\"; }",
                                "src/main/java/p/Fixture.java",
                                "package p; class Fixture { @tx.Tx(\"NEVER\") void start() {} }"));
        org.treblereel.mcp.db.IndexFixture.writeExternalBeans(
                db,
                List.of(
                        interceptor(1, "dep.NeverInterceptor", 200, "@tx.Tx(value=NEVER)"),
                        interceptor(2, "dep.RequiredInterceptor", 200, "@tx.Tx(value=REQUIRED)"),
                        interceptor(
                                3,
                                "dep.AnyTxInterceptor",
                                100,
                                "@tx.Tx(value=REQUIRED @Nonbinding)")));
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("class", "p.Fixture")
                        .put("name", "start")
                        .put("descriptor", "()V");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, fact);
                    return null;
                });
        db.useHandle(
                handle ->
                        handle.execute(
                                "INSERT INTO metadata(key, value) VALUES ('quarkus_generated_classes', ?)",
                                "{\"jars\":[],\"classes\":[],\"dependency_bean_classes\":[\"dep.NeverInterceptor\"]}"));
        ObjectNode registered =
                JSON.createObjectNode()
                        .put("class", "p.Fixture")
                        .put("name", "start")
                        .put("descriptor", "()V");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, registered);
                    return null;
                });

        assertEquals(
                List.of(
                        "dep.NeverInterceptor @Priority(200) via @tx.Tx(value=NEVER) from dep:tx:1"),
                registered
                        .path("dependency_interceptors")
                        .valueStream()
                        .map(node -> node.asText())
                        .toList(),
                registered.toString());
        assertEquals(
                List.of(
                        "dep.AnyTxInterceptor @Priority(100) via @tx.Tx(value=REQUIRED @Nonbinding) from dep:tx:1",
                        "dep.NeverInterceptor @Priority(200) via @tx.Tx(value=NEVER) from dep:tx:1"),
                fact.path("dependency_interceptors")
                        .valueStream()
                        .map(node -> node.asText())
                        .toList(),
                fact.toString());
    }

    private static org.treblereel.mcp.model.ExternalBeanRecord interceptor(
            int id, String name, int priority, String binding) {
        return new org.treblereel.mcp.model.ExternalBeanRecord(
                id,
                name,
                "INTERCEPTOR",
                "@Dependent",
                List.of("@Default", "@Any"),
                List.of(),
                false,
                false,
                priority,
                List.of(),
                null,
                List.of(name),
                "cdi",
                "dep:tx:1",
                "/tmp/tx.jar",
                List.of(),
                List.of(binding));
    }

    /**
     * V21 CQ-033: with pingClients' body in hand, agents still opened the file for sessions,
     * PING_MSG and onOpen.
     */
    @Test
    void methodFactNamesTheFieldsItsBodyUsesAndWhoElseTouchesThem() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        """
                package p;
                class Fixture {
                    private static final String PING = "ping";
                    private final java.util.Set<String> sessions = new java.util.HashSet<>();
                    private int unrelated;
                    void ping() { sessions.forEach(session -> System.out.println(session + PING)); }
                    void open(String s) { sessions.add(s); }
                    void close(String s) { sessions.remove(s); }
                    void other() { unrelated++; }
                }
                """);
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("class", "p.Fixture")
                        .put("name", "ping")
                        .put("descriptor", "()V");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, fact);
                    return null;
                });

        List<String> members =
                fact.path("referenced_members").valueStream().map(JsonNode::asText).toList();
        String sessions =
                members.stream()
                        .filter(line -> line.startsWith("sessions:"))
                        .findFirst()
                        .orElse("");
        assertTrue(
                sessions.contains("java.util.Set<java.lang.String>")
                        && sessions.contains("private final"),
                members.toString());
        assertTrue(sessions.contains("also used by <init>, close, open"), members.toString());
        assertTrue(
                members.stream().noneMatch(line -> line.startsWith("unrelated")),
                members.toString());
    }

    /**
     * V41 CQ-035: asked for a startup observer's order and beans, Claude grepped other
     * observers, @Priority and config keys.
     */
    @Test
    void anObserverNamesTheOtherObserversOfItsEventAndItsConfigKeys() throws Exception {
        String runtime =
                "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) ";
        var db =
                JavaEvidenceFixture.index(
                        root,
                        java.util.Map.of(
                                "src/main/java/jakarta/enterprise/event/Observes.java",
                                "package jakarta.enterprise.event; "
                                        + runtime
                                        + "public @interface Observes {}",
                                "src/main/java/jakarta/annotation/Priority.java",
                                "package jakarta.annotation; "
                                        + runtime
                                        + "public @interface Priority { int value(); }",
                                "src/main/java/org/eclipse/microprofile/config/inject/ConfigProperty.java",
                                "package org.eclipse.microprofile.config.inject; "
                                        + runtime
                                        + "public @interface ConfigProperty { String name(); }",
                                "src/main/java/io/quarkus/runtime/StartupEvent.java",
                                "package io.quarkus.runtime; public class StartupEvent {}",
                                "src/main/resources/application.properties",
                                "app.mode=true\n",
                                "src/main/java/p/A.java",
                                """
                        package p;
                        import jakarta.enterprise.event.Observes; import io.quarkus.runtime.StartupEvent;
                        import org.eclipse.microprofile.config.inject.ConfigProperty;
                        class A { @ConfigProperty(name = "app.mode") String mode; void onStart(@Observes StartupEvent e) { System.out.println(mode); } }
                        """,
                                "src/main/java/p/B.java",
                                """
                        package p;
                        import jakarta.enterprise.event.Observes; import jakarta.annotation.Priority; import io.quarkus.runtime.StartupEvent;
                        class B { void onStart(@Observes @Priority(10) StartupEvent e) {} }
                        """,
                                "src/main/java/p/C.java",
                                """
                        package p;
                        import jakarta.enterprise.event.Observes; import io.quarkus.runtime.StartupEvent;
                        class C { void begin(@Observes StartupEvent e) {} }
                        """));
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("class", "p.A")
                        .put("name", "onStart")
                        .put("descriptor", "(Lio/quarkus/runtime/StartupEvent;)V");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, fact);
                    return null;
                });

        var observers = fact.path("same_event_observers");
        assertEquals(
                List.of("p.B#onStart @Priority(10)"),
                observers.path("listed").valueStream().map(JsonNode::asText).toList(),
                fact.toString());
        assertEquals(2, observers.path("others").asInt());
        assertEquals(
                "1 share default @Priority 2500 with this one: order unspecified",
                observers.path("order").asText());
        String mode = fact.path("referenced_members").get(0).asText();
        assertTrue(
                mode.contains(
                        "key app.mode; main src/main/resources/application.properties:1=true"),
                mode);
    }

    /**
     * V21: Codex made 86 dependency-jar lookups to learn how Quarkus runs a scheduled or
     * transactional method.
     */
    @Test
    void frameworkAnnotationsCarryTheirDocumentedContractAndTheApplicationsSettings()
            throws Exception {
        String runtime =
                "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) ";
        var db =
                JavaEvidenceFixture.index(
                        root,
                        java.util.Map.of(
                                "src/main/java/io/quarkus/scheduler/Scheduled.java",
                                "package io.quarkus.scheduler; "
                                        + runtime
                                        + "public @interface Scheduled { String every() default \"\"; }",
                                "src/main/java/jakarta/transaction/Transactional.java",
                                "package jakarta.transaction; "
                                        + runtime
                                        + "public @interface Transactional {}",
                                "src/main/java/jakarta/enterprise/event/Observes.java",
                                "package jakarta.enterprise.event; "
                                        + runtime
                                        + "public @interface Observes {}",
                                "src/main/java/io/quarkus/runtime/StartupEvent.java",
                                "package io.quarkus.runtime; public class StartupEvent {}",
                                "src/main/resources/application.properties",
                                "quarkus.scheduler.start-mode=forced\n%prod.app.ping=30s\n",
                                "src/main/java/p/Fixture.java",
                                """
                        package p;
                        class Fixture {
                            @io.quarkus.scheduler.Scheduled(every = "${app.ping:20s}")
                            void ping() {}
                            @jakarta.transaction.Transactional
                            void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event) {}
                            void plain() {}
                        }
                        """));
        ObjectNode ping =
                JSON.createObjectNode()
                        .put("class", "p.Fixture")
                        .put("name", "ping")
                        .put("descriptor", "()V");
        ObjectNode start =
                JSON.createObjectNode()
                        .put("class", "p.Fixture")
                        .put("name", "onStart")
                        .put("descriptor", "(Lio/quarkus/runtime/StartupEvent;)V");
        ObjectNode plain =
                JSON.createObjectNode()
                        .put("class", "p.Fixture")
                        .put("name", "plain")
                        .put("descriptor", "()V");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, ping);
                    MethodPrefetchRelations.append(reader, start);
                    MethodPrefetchRelations.append(reader, plain);
                    return null;
                });

        String scheduled = ping.path("framework_contract").toString();
        assertTrue(scheduled.contains("off or disabled"), scheduled);
        assertTrue(
                scheduled.contains(
                        "quarkus.scheduler.enabled: not declared; framework default true"),
                scheduled);
        assertTrue(
                scheduled.contains(
                        "quarkus.scheduler.start-mode=forced src/main/resources/application.properties:1"),
                scheduled);
        assertTrue(
                scheduled.contains(
                        "%prod.app.ping=30s src/main/resources/application.properties:2"),
                scheduled);
        assertTrue(
                scheduled.contains("app.ping: no unprofiled declaration; expression default 20s"),
                scheduled);
        String observer = start.path("framework_contract").toString();
        assertTrue(observer.contains("StartupEvent"), observer);
        assertTrue(observer.contains("checked exception commits"), observer);
        assertTrue(observer.contains("lambdas included"), observer);
        assertFalse(plain.has("framework_contract"), plain.toString());
    }

    @Test
    void impactCallersBecomeOneLinePerClassAndCoverageKeepsItsVerdict() {
        ObjectNode fact = JSON.createObjectNode();
        ArrayNode tests = fact.putArray("test_callers");
        ObjectNode caller =
                tests.addObject()
                        .put("class", "p.FixtureTest")
                        .put("source", "src/test/java/p/FixtureTest.java");
        caller.putArray("methods")
                .add(JSON.createObjectNode().put("method", "a").put("evidence_lines", "[1]"))
                .add(JSON.createObjectNode().put("method", "b"));
        fact.putObject("coverage")
                .put("scan_complete", true)
                .put("snapshot_freshness", "current")
                .putArray("incomplete_reasons")
                .add("compiled_output_coverage_unverified");
        fact.putObject("reference_page_counts").put("tests", 1);
        tests.addObject().put("class", "p.Other").put("source", "it/java/p/Other.java");
        fact.putArray("declaring_class_tests").add("p.FixtureTest").add("p.OnlyTypeTest");

        MethodPrefetchRelations.compactImpact(fact);

        // The class names its conventional file; V63 CQ-046 spent about 60 bytes a line on it.
        assertEquals("p.FixtureTest via a lines [1], b", fact.path("test_callers").get(0).asText());
        assertEquals("p.Other it/java/p/Other.java", fact.path("test_callers").get(1).asText());
        assertEquals(
                List.of("p.OnlyTypeTest"),
                fact.path("declaring_class_tests").valueStream().map(JsonNode::asText).toList());
        assertTrue(fact.path("coverage").path("scan_complete").asBoolean());
        assertEquals(1, fact.path("coverage").path("incomplete_reasons").size());
        assertFalse(fact.path("coverage").has("snapshot_freshness"));
        assertFalse(fact.has("reference_page_counts"));
    }

    /**
     * Apollo v1-v3 AQ-046: asked which tests to run after changing a delegating method, Claude left
     * out the delegates' tests.
     */
    @Test
    void aDelegatingMethodNamesItsDelegatesTests() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; interface V { boolean ok(String a); } "
                                + "class UserV implements V { public boolean ok(String a) { return true; } } "
                                + "class TokenV implements V { public boolean ok(String a) { return false; } } "
                                + "class Unified { V delegate; boolean ok(String a) { return delegate.ok(a); } } "
                                + "class UserVTest { UserV v = new UserV(); } class Unrelated {}");
        db.useHandle(
                handle ->
                        handle.execute(
                                "UPDATE classes SET source_set = CASE WHEN class_name = 'p.UserVTest' THEN 'test' ELSE 'main' END"));
        db.useHandle(
                handle ->
                        handle.execute(
                                "INSERT INTO dependencies(from_class_id, to_class_id, kind, occurrence_count, evidence_lines) SELECT t.id, u.id, "
                                        + "'CONSTRUCTS', 1, '[]' FROM classes t, classes u WHERE t.class_name = 'p.UserVTest' AND u.class_name = 'p.UserV'"));
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("class", "p.Unified")
                        .put("name", "ok")
                        .put("descriptor", "(Ljava/lang/String;)Z");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, root, fact);
                    return null;
                });

        String delegates = fact.path("delegate_tests").toString();
        assertTrue(delegates.contains("p.UserVTest (tests UserV)"), fact.toString());
        assertTrue(fact.path("delegate_tests_basis").asText().contains("V#ok"), fact.toString());
    }
}
