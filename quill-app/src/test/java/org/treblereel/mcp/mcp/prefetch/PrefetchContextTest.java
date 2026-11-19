package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

class PrefetchContextTest {

    /** These packets are sized for this budget; the default limit may differ. */
    private static final int LEGACY_BUDGET = 5000;

    @TempDir Path root;

    @Test
    void arbitraryWordingUsesIndexedIdentifiersAndChangedSourceNeverBecomesFreshFacts()
            throws Exception {
        var db = JavaEvidenceFixture.index(root, "package p; class Fixture {int value;} ");
        var first =
                TestReads.read(
                        db,
                        readScope ->
                                PrefetchContext.collect(
                                        readScope, root, "Please explain p.Fixture."));
        assertEquals(1, first.path("facts").size(), first.toString());
        assertEquals(
                first.path("facts"),
                TestReads.read(
                                db,
                                readScope ->
                                        PrefetchContext.collect(
                                                readScope, root, "Unrelated wording: Fixture?"))
                        .path("facts"));
        assertEquals(
                1,
                TestReads.read(
                                db,
                                readScope ->
                                        PrefetchContext.collect(
                                                readScope,
                                                root,
                                                "p.Fixture.value and Fixture.java"))
                        .path("facts")
                        .size());
        assertEquals(
                0,
                TestReads.read(
                                db,
                                readScope ->
                                        PrefetchContext.collect(
                                                readScope, root, "No known identifiers here"))
                        .path("facts")
                        .size());
        Files.writeString(
                root.resolve("src/main/java/p/Fixture.java"),
                "package p; class Fixture {long value;}");
        assertEquals(
                0,
                TestReads.read(db, readScope -> PrefetchContext.collect(readScope, root, "Fixture"))
                        .path("facts")
                        .size());
    }

    @Test
    void oversizedFactLosesItsLargestFieldInsteadOfDisappearing() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var packet = json.createObjectNode();
        var fact =
                packet.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("tool", "get_context")
                        .put("target", "a.B#m")
                        .put("answer", "keep");
        fact.putArray("dispatches").addObject().put("text", "x".repeat(6000));
        PrefetchContext.boundContext(packet, null, LEGACY_BUDGET);
        assertTrue(
                packet.toString().length() <= PrefetchContext.CONTEXT_LIMIT_BYTES,
                packet.toString());
        assertEquals(1, packet.path("facts").size(), packet.toString());
        assertEquals("keep", packet.path("facts").get(0).path("answer").asText());
        assertEquals("a.B#m", packet.path("facts").get(0).path("target").asText());
        assertEquals("dispatches", packet.path("context_omitted_fields").get(0).asText());
        assertFalse(packet.has("unavailable"), packet.toString());
    }

    /**
     * In V20 the budget cut the body of Credentials#onStart before its call list, and agents opened
     * the file.
     */
    @Test
    void theSelectedMethodBodyGoesLastBecauseWithoutItAgentsReadTheWholeFile() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var packet = json.createObjectNode();
        var fact =
                packet.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "a.B#m");
        fact.putObject("selected_method_source")
                .put("source_file", "a/B.java")
                .put("source", "b".repeat(1300));
        var calls = fact.putArray("calls");
        for (int i = 0; i < 12; i++)
            calls.addObject().put("target", "c.D" + i + "#run").put("line", i);
        // A small overrun: the body is then the smallest single field that covers it.
        fact.putObject("dependency_interceptors").put("text", "i".repeat(1500));
        fact.put("padding", "p".repeat(2000));
        PrefetchContext.boundContext(packet, null, LEGACY_BUDGET);
        assertTrue(
                packet.toString().length() <= PrefetchContext.CONTEXT_LIMIT_BYTES,
                packet.toString());
        assertEquals(
                1300,
                packet.path("facts")
                        .get(0)
                        .path("selected_method_source")
                        .path("source")
                        .asText()
                        .length(),
                packet.toString());
        assertTrue(packet.path("context_truncated").asBoolean());
    }

    /** Replay of V21 CQ-027: the transaction contract was cut while index navigation stayed. */
    @Test
    void theFrameworkContractGoesLastLikeTheBody() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var packet = json.createObjectNode();
        var fact =
                packet.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "a.B#m");
        fact.putObject("framework_contract").put("rules", "r".repeat(1000));
        fact.putObject("dependency_interceptors").put("text", "i".repeat(1500));
        fact.put("padding", "p".repeat(2700));
        PrefetchContext.boundContext(packet);
        assertTrue(
                packet.toString().length() <= PrefetchContext.CONTEXT_LIMIT_BYTES,
                packet.toString());
        assertEquals(
                1000,
                packet.path("facts")
                        .get(0)
                        .path("framework_contract")
                        .path("rules")
                        .asText()
                        .length(),
                packet.toString());
    }

    @Test
    void theBodyStillYieldsBeforeTheWholeFactIsDropped() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var packet = json.createObjectNode();
        var fact =
                packet.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "a.B#m")
                        .put("answer", "keep");
        fact.putObject("selected_method_source").put("source", "b".repeat(6000));
        PrefetchContext.boundContext(packet, null, LEGACY_BUDGET);
        assertEquals(
                "keep", packet.path("facts").get(0).path("answer").asText(), packet.toString());
        assertFalse(packet.path("facts").get(0).has("selected_method_source"));
    }

    @Test
    void factWithOnlyIdentityOverTheLimitIsStillReportedAsUnavailable() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var packet = json.createObjectNode();
        packet.putArray("facts")
                .addObject()
                .put("kind", "direct_method_impact")
                .put("target", "x".repeat(6000));
        PrefetchContext.boundContext(packet, null, LEGACY_BUDGET);
        assertEquals(0, packet.path("facts").size());
        assertEquals(
                "prefetch_context_limit",
                packet.path("unavailable").get(0).path("status").asText());
    }

    @Test
    void objectHoldingAListIsHalvedNotRemoved() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var packet = json.createObjectNode();
        var fact =
                packet.putArray("facts").addObject().put("kind", "INTERFACE").put("answer", "keep");
        var keys = fact.putObject("config_mapping").put("prefix", "rbac").putArray("keys");
        for (int i = 0; i < 200; i++)
            keys.add("rbac.some.configuration.key.number." + i + ".value");
        PrefetchContext.boundContext(packet);
        assertTrue(
                packet.path("facts").get(0).path("config_mapping").path("keys").size() > 0,
                packet.toString());
        assertTrue(
                packet.path("facts").get(0).path("config_mapping").has("keys_truncated"),
                packet.toString());
    }

    @Test
    void omittedFieldTakesItsExplanationWithIt() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var packet = json.createObjectNode();
        var fact = packet.putArray("facts").addObject().put("kind", "CLASS").put("answer", "keep");
        fact.put("generated_classes_basis", "why these classes were generated");
        fact.putArray("generated_classes").add("g".repeat(3000)).add("g".repeat(3000));
        PrefetchContext.boundContext(packet, null, LEGACY_BUDGET);
        assertFalse(packet.path("facts").get(0).has("generated_classes"), packet.toString());
        assertFalse(packet.path("facts").get(0).has("generated_classes_basis"), packet.toString());
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode overrunBy(
            int bytes,
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> content) {
        var packet = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        var fact = packet.putArray("facts").addObject().put("kind", "CLASS");
        content.accept(fact);
        fact.put("answer", "");
        fact.put(
                "answer",
                "a"
                        .repeat(
                                PrefetchContext.CONTEXT_LIMIT_BYTES
                                        + bytes
                                        - packet.toString().length()));
        assertEquals(PrefetchContext.CONTEXT_LIMIT_BYTES + bytes, packet.toString().length());
        return packet;
    }

    @Test
    void tinyOverrunCutsAListInsteadOfALargeField() {
        var packet =
                overrunBy(
                        1,
                        fact -> {
                            for (int i = 0; i < 8; i++)
                                fact.putArray("l" + i).add("x".repeat(90)).add("y".repeat(90));
                        });
        PrefetchContext.boundContext(packet);
        assertTrue(
                packet.toString().length() <= PrefetchContext.CONTEXT_LIMIT_BYTES,
                packet.toString());
        assertTrue(
                packet.path("facts").get(0).path("answer").asText().length() > 3000,
                packet.toString());
        assertFalse(packet.has("context_omitted_fields"), packet.toString());
    }

    /**
     * Cryostat CQ-028: a pointer to the cut evidence in every packet sent Codex to get_context on a
     * target it had whole. A cut packet says where the rest is (an unsteered cut sends the agent
     * searching); a whole one does not.
     */
    @Test
    void onlyACutPacketPointsToWhereTheRestIs() {
        var cut =
                overrunBy(
                        60,
                        fact -> {
                            var consumers = fact.putArray("injected_into");
                            for (int i = 0; i < 20; i++)
                                consumers.add(
                                        "io.example.Consumer" + i + "#holder (FIELD) [RESOLVED]");
                        });
        cut.put("instructions", "Quill already ran these reads.");
        PrefetchContext.boundContext(cut);
        assertTrue(
                cut.path("instructions")
                        .asText()
                        .contains("get_context or find_usages on the same target returns them"),
                cut.path("instructions").asText());
        assertTrue(cut.toString().length() <= PrefetchContext.CONTEXT_LIMIT_BYTES, cut.toString());
        var whole =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .createObjectNode()
                        .put("instructions", "Quill already ran these reads.");
        whole.putArray("facts").addObject().put("kind", "CLASS").put("class", "a.B");
        PrefetchContext.boundContext(whole);
        assertEquals("Quill already ran these reads.", whole.path("instructions").asText());
    }

    /**
     * Apollo AQ-043: halving injected_into to fit a small overrun left 13 of 26 consumers and about
     * a thousand bytes of the budget unused.
     */
    @Test
    void aListCutForASmallOverrunLosesOnlyTheItemsThatPayForIt() {
        var packet =
                overrunBy(
                        60,
                        fact -> {
                            var consumers = fact.putArray("injected_into");
                            for (int i = 0; i < 20; i++)
                                consumers.add(
                                        "io.example.Consumer" + i + "#holder (FIELD) [RESOLVED]");
                        });
        PrefetchContext.boundContext(packet);
        var fact = packet.path("facts").get(0);
        assertTrue(
                packet.toString().length() <= PrefetchContext.CONTEXT_LIMIT_BYTES,
                packet.toString());
        assertTrue(
                fact.path("injected_into").size() >= 16,
                fact.path("injected_into_truncated").toString());
        assertEquals(
                fact.path("injected_into").size(),
                fact.path("injected_into_truncated").path("shown").asInt());
        assertEquals(20, fact.path("injected_into_truncated").path("total").asInt());
    }

    @Test
    void shortMeaningfulListsSurviveWhileALargerListCanPay() {
        var packet =
                overrunBy(
                        5,
                        fact -> {
                            fact.putArray("qualifiers").add("@Tenant(\"eu\")").add("@Default");
                            var callers = fact.putArray("callers");
                            for (int i = 0; i < 10; i++)
                                callers.add("io.example.Caller" + i + " calls key(String)");
                        });
        PrefetchContext.boundContext(packet);
        assertEquals(2, packet.path("facts").get(0).path("qualifiers").size(), packet.toString());
    }

    @Test
    void limitStatementsNeverGoSoATotalStaysTrue() {
        var packet =
                overrunBy(
                        20,
                        fact -> {
                            var callers = fact.putArray("contract_callers");
                            for (int i = 0; i < 30; i++)
                                callers.add("io.example.Caller" + i + " calls key(String)");
                            fact.putObject("contract_callers_truncated")
                                    .put("shown", 30)
                                    .put("total", 57);
                            fact.put(
                                    "contract_callers_scope",
                                    "Runtime dispatch and inherited implementors are not covered");
                            fact.put("has_more", true).put("total", 57).put("complete", false);
                        });
        PrefetchContext.boundContext(packet);
        var fact = packet.path("facts").get(0);
        assertEquals(
                57,
                fact.path("contract_callers_truncated").path("total").asInt(),
                packet.toString());
        assertTrue(fact.has("contract_callers_scope"), packet.toString());
        assertTrue(fact.path("has_more").asBoolean());
        assertEquals(57, fact.path("total").asInt());
        assertTrue(fact.has("complete"));
    }

    @Test
    void factLeftWithoutContentIsReportedAndKeepsNoStaleMarker() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var packet = json.createObjectNode();
        packet.putArray("facts")
                .addObject()
                .put("kind", "direct_method_impact")
                .put("target", "a.B#m")
                .putArray("dispatches")
                .add("x".repeat(6000))
                .add("y".repeat(6000));
        PrefetchContext.boundContext(packet, null, LEGACY_BUDGET);
        assertEquals(0, packet.path("facts").size(), packet.toString());
        assertEquals(
                "a.B#m",
                packet.path("unavailable").get(0).path("target").asText(),
                packet.toString());
    }

    @Test
    void evictedFieldTakesItsMarkerAndExplanation() {
        var packet =
                overrunBy(
                        40,
                        fact -> {
                            fact.put("decorators", "d".repeat(1000))
                                    .put("decorators_basis", "why decorators apply");
                            fact.put("decorators_scope", "which decorators");
                        });
        var fact = (com.fasterxml.jackson.databind.node.ObjectNode) packet.path("facts").get(0);
        fact.put("answer", "a".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 1000));
        fact.put("note", "n".repeat(50));
        PrefetchContext.boundContext(packet);
        if (!fact.has("decorators")) {
            assertFalse(fact.has("decorators_basis"), packet.toString());
            assertFalse(fact.has("decorators_scope"), packet.toString());
        }
        assertFalse(fact.has("decorators"), packet.toString());
    }

    @Test
    void contractCallersAreCutNotRemoved() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var packet = json.createObjectNode();
        var callers =
                packet.putArray("facts")
                        .addObject()
                        .put("kind", "INTERFACE")
                        .putArray("contract_callers");
        for (int i = 0; i < 200; i++)
            callers.add("io.example.Caller" + i + " calls key(String),keyExists(String,String)");
        PrefetchContext.boundContext(packet);
        var fact = packet.path("facts").get(0);
        assertTrue(fact.path("contract_callers").size() > 0, packet.toString());
        assertEquals(200, fact.path("contract_callers_truncated").path("total").asInt());
        assertFalse(packet.path("context_omitted_fields").toString().contains("contract_callers"));
    }

    @Test
    void fieldTargetDeliversItsInjectionPointAndNamesFieldsThatAreNotInjected() throws Exception {
        var db = QuillDatabase.create(root.resolve("fields.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Consumer",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Consumer.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Message",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Message.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "CLASS",
                                                "@Singleton",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Consumer")),
                                        new BeanRecord(
                                                0,
                                                2,
                                                "CLASS",
                                                "@Dependent",
                                                List.of("@Named(value=msg)", "@Default", "@Any"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Message"))))
                        .injectionPoints(
                                List.of(
                                        InjectionPointRecord.staticAnalysis(
                                                0,
                                                1,
                                                "FIELD",
                                                "x.Message",
                                                List.of("@Default"),
                                                "message",
                                                2,
                                                false,
                                                InjectionPointRecord.STATIC_CDI)))
                        .dependencies(List.of())
                        .metadata(Map.of("index_id", "fields"))
                        .snapshot());
        db.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) VALUES (1,'FIELD','message','message','Lx/Message;','x.Message','[]','','[\"jakarta.inject.Inject\"]')");
                    handle.execute(
                            "INSERT INTO class_annotations(class_id,annotation_name,direct,annotation_values) VALUES (2,'jakarta.inject.Named',1,'{\"value\":\"msg\"}'),(2,'x.Tag',1,NULL),(2,'java.lang.annotation.Documented',0,NULL)");
                });
        var pack =
                TestReads.read(
                        db,
                        readScope ->
                                PrefetchContext.collect(
                                        readScope,
                                        root,
                                        "What is injected into x.Consumer#message and x.Consumer#missing?"));
        assertEquals("ready", pack.path("status").asText(), pack.toString());
        var fact = pack.path("facts").get(0);
        assertEquals("injection_point", fact.path("kind").asText(), pack.toString());
        assertEquals("x.Consumer#message", fact.path("target").asText());
        assertEquals("x.Message", fact.path("injection_point").path("required_type").asText());
        assertEquals("@Singleton", fact.path("owner_scope").asText());
        // V41 CQ-013: asked which qualifiers are explicit or implicit, Claude read both sources to
        // find out.
        assertEquals(
                List.of(
                        "x.Consumer#message: declared none; implied @Default",
                        "x.Message: declared @Named(value=msg); implied @Default, @Any; annotations @jakarta.inject.Named, @x.Tag"),
                fact.path("qualifier_origin").valueStream().map(JsonNode::asText).toList(),
                fact.toString());
        assertEquals(
                "not_an_injection_point", pack.path("unavailable").get(0).path("status").asText());
    }

    // V55 CQ-010: told the field resolves to a built-in bean, Codex still ran rg 'implements
    // Event|@Typed' to prove no application class does.
    @Test
    void anInjectionPointWithNoApplicationCandidateStatesTheScopeItWasCheckedAgainst()
            throws Exception {
        var db = QuillDatabase.create(root.resolve("builtin.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Consumer",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Consumer.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Message",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Message.java",
                                                1,
                                                false,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "CLASS",
                                                "@Singleton",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Consumer"))))
                        .injectionPoints(
                                List.of(
                                        InjectionPointRecord.staticAnalysis(
                                                        0,
                                                        1,
                                                        "FIELD",
                                                        "jakarta.enterprise.event.Event<x.Message>",
                                                        List.of("@Default"),
                                                        "events",
                                                        null,
                                                        false,
                                                        InjectionPointRecord.STATIC_CDI)
                                                .asCdiBuiltin()))
                        .dependencies(List.of())
                        .metadata(Map.of("index_id", "builtin"))
                        .snapshot());
        db.useHandle(
                handle ->
                        handle.execute(
                                "INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) VALUES (1,'FIELD','events','events','Ljakarta/enterprise/event/Event;','jakarta.enterprise.event.Event','[]','','[\"jakarta.inject.Inject\"]')"));
        var pack =
                TestReads.read(
                        db,
                        readScope ->
                                PrefetchContext.collect(
                                        readScope,
                                        root,
                                        "What is injected into x.Consumer#events?"));
        var fact = pack.path("facts").get(0);
        assertEquals(
                "no application class extends or implements jakarta.enterprise.event.Event (of 2 indexed); no application bean has it among its bean types, @Typed included (of 1)",
                fact.path("application_candidates_checked").asText(),
                pack.toString());
    }

    // V41 CQ-023: given the Instance injection point and its @LookupIfProperty candidate, agents
    // read the source for how the field is used.
    @Test
    void anInjectionPointNamesWhereItsFieldIsReadWithTheSourceLine() throws Exception {
        java.nio.file.Files.writeString(
                root.resolve("Consumer.java"),
                "class Consumer {\n  Event<Message> events;\n  void run(Message m) { events.fire(m); }\n}\n");
        var db = QuillDatabase.create(root.resolve("usage.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Consumer",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Consumer.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "CLASS",
                                                "@Singleton",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Consumer"))))
                        .injectionPoints(
                                List.of(
                                        InjectionPointRecord.staticAnalysis(
                                                        0,
                                                        1,
                                                        "FIELD",
                                                        "jakarta.enterprise.event.Event<x.Message>",
                                                        List.of("@Default"),
                                                        "events",
                                                        null,
                                                        false,
                                                        InjectionPointRecord.STATIC_CDI)
                                                .asCdiBuiltin()))
                        .dependencies(List.of())
                        .metadata(Map.of("index_id", "usage", "project_root", root.toString()))
                        .snapshot());
        db.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) VALUES (1,'FIELD','events','events','Ljakarta/enterprise/event/Event;','jakarta.enterprise.event.Event','[]','','[\"jakarta.inject.Inject\"]')");
                    handle.execute(
                            "INSERT INTO field_accesses(from_class_id,from_method,from_descriptor,to_class_id,field_name,field_descriptor,access_kind,evidence_lines) VALUES (1,'run','(Lx/Message;)V',1,'events','Ljakarta/enterprise/event/Event;','read_instance','[3]')");
                });
        var pack =
                TestReads.read(
                        db,
                        readScope ->
                                PrefetchContext.collect(
                                        readScope,
                                        root,
                                        "What is injected into x.Consumer#events?"));
        assertEquals(
                List.of("run line 3: void run(Message m) { events.fire(m); }"),
                pack.path("facts")
                        .get(0)
                        .path("used_in")
                        .valueStream()
                        .map(JsonNode::asText)
                        .toList(),
                pack.toString());
    }

    // V55 CQ-025: the budget dropped the thread hop fact, the one Codex otherwise rebuilt by
    // reading each class.
    @Test
    void theBudgetCutsOtherEvidenceBeforeAThreadHop() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var fact =
                data.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "x.A");
        fact.putObject("thread_hops")
                .put(
                        "switches",
                        "line 10 io.smallrye.mutiny.Uni#runSubscriptionOn " + "x".repeat(650));
        fact.put("notes", "y".repeat(900));
        fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 1500));
        PrefetchContext.boundContext(data);
        org.junit.jupiter.api.Assertions.assertTrue(
                data.path("facts").get(0).has("thread_hops"),
                data.path("context_omitted_fields").toString());
    }

    // V62 CQ-026: the budget dropped dependency_interceptors, the interceptor answer; V41 answered
    // from prefetch alone.
    @Test
    void theBudgetCutsOtherEvidenceBeforeTheInterceptorsBoundToAMethod() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var fact =
                data.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "x.A");
        fact.putArray("dependency_interceptors")
                .add(
                        "io.quarkus.narayana.jta.runtime.interceptor.TransactionalInterceptorRequired @Priority(200) "
                                + "x".repeat(300));
        fact.put(
                "dependency_interceptors_basis",
                "interceptors in dependency jars bound by an annotation on this method or its class");
        fact.put("notes", "y".repeat(900));
        fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 1250));
        PrefetchContext.boundContext(data);
        org.junit.jupiter.api.Assertions.assertTrue(
                data.path("facts").get(0).has("dependency_interceptors"),
                data.path("context_omitted_fields").toString());
    }

    // V66 CQ-027: kept interceptors pushed the impact over budget and halved calls; readValue went
    // and all three answers lost it.
    @Test
    void theBudgetCutsOtherEvidenceBeforeAMethodsCalls() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var fact =
                data.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "x.A");
        fact.putArray("calls")
                .add("1 x.Config#walk [virtual]")
                .add("1 x.Credential#persistAndFlush [virtual]")
                .add(
                        "1 com.fasterxml.jackson.databind.ObjectMapper#readValue(java.io.InputStream, java.lang.Class):java.lang.Object [external]");
        fact.putArray("referenced_members")
                .add("mapper: " + "m".repeat(200))
                .add("logger: " + "l".repeat(200));
        fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 700));
        PrefetchContext.boundContext(data);
        org.junit.jupiter.api.Assertions.assertEquals(
                3,
                data.path("facts").get(0).path("calls").size(),
                data.toString().substring(0, 600));
    }

    // Apollo AQ-005 probe: same_type_providers was halved before the 25-line
    // produced_bean_injected_into, losing the LDAP provider.
    @Test
    void theBudgetCutsConsumersBeforeTheOtherProvidersOfAProducedType() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var fact =
                data.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "x.A");
        fact.putArray("same_type_providers")
                .add("x.Ldap#holder profiles [ldap] " + "p".repeat(80))
                .add("x.Oidc#holder profiles [oidc] " + "p".repeat(80))
                .add(
                        "x.Default#holder class @ConditionalOnMissingProfile([auth]) "
                                + "p".repeat(80))
                .add("test sources only: x.T#holder");
        var consumers = fact.putArray("produced_bean_injected_into");
        for (int i = 0; i < 25; i++) consumers.add("x.C" + i + "#<init> " + "c".repeat(120));
        int size = data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        // A small overrun: half the consumers frees far more than needed, half the providers about
        // enough.
        fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - size + 150));
        PrefetchContext.boundContext(data);
        org.junit.jupiter.api.Assertions.assertEquals(
                4,
                data.path("facts").get(0).path("same_type_providers").size(),
                data.toString().substring(0, 900));
    }

    // Apollo AQ-011 probe: a producer's 16-line factory calls were kept whole while its consumers
    // and framework lookups went.
    @Test
    void aLongCallListIsHalvedBeforeAProducersConsumersGo() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var fact =
                data.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "x.A");
        var calls = fact.putArray("calls");
        for (int i = 0; i < 16; i++)
            calls.add((i < 4 ? "1 " : "2 ") + "x.Factory#step" + i + " [static] " + "c".repeat(80));
        fact.putArray("framework_lookups")
                .add(
                        "Encoder: x.Configurer#configure looks it up by type via getBeanOrNull "
                                + "f".repeat(60));
        var consumers = fact.putArray("produced_bean_injected_into");
        for (int i = 0; i < 3; i++)
            consumers.add("x.C" + i + "#encoder [] [RESOLVED] " + "p".repeat(60));
        fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 1700));
        PrefetchContext.boundContext(data);
        var kept = data.path("facts").get(0);
        org.junit.jupiter.api.Assertions.assertTrue(
                kept.has("framework_lookups"), kept.toString().substring(0, 400));
        org.junit.jupiter.api.Assertions.assertEquals(
                3,
                kept.path("produced_bean_injected_into").size(),
                kept.toString().substring(0, 400));
        org.junit.jupiter.api.Assertions.assertTrue(
                kept.path("calls").size() < 16, kept.toString().substring(0, 400));
    }

    // V68 CQ-039: application_key_phase grew the packet and the budget halved the key's references,
    // the answer itself.
    @Test
    void theBudgetKeepsAConfigurationKeysReferences() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var evidence =
                data.putArray("facts")
                        .addObject()
                        .put("kind", "configuration")
                        .putObject("evidence");
        var references = evidence.putArray("references");
        for (int i = 0; i < 4; i++)
            references.add(
                    json.createObjectNode()
                            .put("entry_type", i < 2 ? "definition" : "usage")
                            .put("file", "f" + i + ".properties")
                            .put("note", "r".repeat(150)));
        evidence.putObject("key_constants").put("items", "k".repeat(250));
        evidence.putObject("configuration_resources").put("scope", "c".repeat(180));
        evidence.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 1400));
        PrefetchContext.boundContext(data);
        org.junit.jupiter.api.Assertions.assertEquals(
                4,
                data.path("facts").get(0).path("evidence").path("references").size(),
                data.toString().substring(0, 300));
    }

    // Apollo AQ-041 probe: the budget dropped expression_references, the @PreAuthorize callers the
    // question asks about.
    @Test
    void theBudgetKeepsAMethodsAnnotationExpressionCallers() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var fact =
                data.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "x.A");
        var expressions = fact.putObject("expression_references").put("total", 2);
        expressions
                .putArray("references")
                .add("x.C#create @PreAuthorize " + "e".repeat(120))
                .add("x.D#merge @PreAuthorize " + "e".repeat(120));
        fact.putArray("interface_contracts").add("x.I#check " + "i".repeat(300));
        fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 500));
        PrefetchContext.boundContext(data);
        org.junit.jupiter.api.Assertions.assertTrue(
                data.path("facts").get(0).has("expression_references"),
                data.path("context_omitted_fields").toString());
    }

    // Apollo AQ-041 probe: protecting every "references" list kept 1.4 KB of SpEL calls and dropped
    // the Java callers and tests.
    @Test
    void onlyAConfigurationKeysReferencesAreLastResort() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var fact =
                data.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "x.A");
        var refs = fact.putObject("expression_references").put("total", 8).putArray("references");
        for (int i = 0; i < 8; i++)
            refs.add("x.C" + i + "#create @PreAuthorize " + "e".repeat(140));
        fact.putArray("production_callers").add("x.Caller#run " + "p".repeat(60));
        fact.putArray("test_callers").add("x.ATest#t " + "t".repeat(60));
        fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 1500));
        PrefetchContext.boundContext(data);
        var kept = data.path("facts").get(0);
        org.junit.jupiter.api.Assertions.assertTrue(
                kept.has("production_callers") && kept.has("test_callers"),
                data.path("context_omitted_fields").toString());
        org.junit.jupiter.api.Assertions.assertTrue(
                kept.has("expression_references"), data.path("context_omitted_fields").toString());
        org.junit.jupiter.api.Assertions.assertTrue(
                kept.path("expression_references").path("references").size() < 8,
                kept.toString().substring(0, 300));
    }

    // Budget plan item 13: the fields a question asks about are cut last; the shared context block
    // above it does not count.
    @Test
    void theBudgetKeepsTheFieldsTheQuestionAsksAbout() throws Exception {
        java.util.function.Function<String, com.fasterxml.jackson.databind.JsonNode> bound =
                prompt -> {
                    var json = new com.fasterxml.jackson.databind.ObjectMapper();
                    var data = json.createObjectNode();
                    var fact =
                            data.putArray("facts")
                                    .addObject()
                                    .put("kind", "CLASS")
                                    .put("class", "x.A");
                    var into = fact.putArray("injected_into");
                    for (int i = 0; i < 4; i++) into.add("x.C" + i + "#a " + "i".repeat(150));
                    var tests = fact.putArray("declaring_class_tests");
                    for (int i = 0; i < 4; i++) tests.add("x.T" + i + "Test " + "t".repeat(150));
                    fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 1100));
                    PrefetchContext.boundContext(data, prompt);
                    return data.path("facts").get(0);
                };
        String context =
                "Context: Enumerations are bounded to application-owned production/test source.\nProfile: prod.\n";
        var aboutTests = bound.apply(context + "Which tests should run after changing x.A?");
        org.junit.jupiter.api.Assertions.assertEquals(
                4,
                aboutTests.path("declaring_class_tests").size(),
                aboutTests.toString().substring(0, 200));
        org.junit.jupiter.api.Assertions.assertTrue(aboutTests.path("injected_into").size() < 4);
        var aboutInjection = bound.apply(context + "Where is x.A injected?");
        org.junit.jupiter.api.Assertions.assertEquals(
                4,
                aboutInjection.path("injected_into").size(),
                aboutInjection.toString().substring(0, 200));
        org.junit.jupiter.api.Assertions.assertTrue(
                aboutInjection.path("declaring_class_tests").size() < 4);
    }

    // Apollo AQ-044 probe: a question touching scope, consumers and the bean name protected so much
    // that the bean name went.
    @Test
    void fieldsTheQuestionAsksAboutOutliveTheStaticLastResortOnes() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var fact =
                data.putArray("facts").addObject().put("kind", "CLASS").put("class", "x.Validator");
        fact.putArray("bean_name_expressions")
                .add(
                        "bean name validator: 129 annotation expressions (@PreAuthorize) "
                                + "b".repeat(200));
        fact.putObject("framework_contract").put("rules", "f".repeat(400));
        fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 500));
        PrefetchContext.boundContext(
                data,
                "How can x.Validator be replaced by a @Bean method without losing its bean name?");
        var kept = data.path("facts").get(0);
        org.junit.jupiter.api.Assertions.assertTrue(
                kept.has("bean_name_expressions"), data.path("context_omitted_fields").toString());
    }

    // Apollo AQ-046 probe: a test question kept 21 declaring_class_tests lines and dropped every
    // caller.
    @Test
    void aLongListTheQuestionAsksAboutIsStillHalved() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var fact =
                data.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "x.A");
        var tests = fact.putArray("declaring_class_tests");
        for (int i = 0; i < 20; i++) tests.add("x.T" + i + "Test " + "t".repeat(100));
        fact.putArray("production_callers").add("x.Caller#run " + "p".repeat(200));
        fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 2300));
        PrefetchContext.boundContext(data, "Which tests should run after changing x.A#run?");
        var kept = data.path("facts").get(0);
        org.junit.jupiter.api.Assertions.assertTrue(
                kept.has("production_callers"), data.path("context_omitted_fields").toString());
        org.junit.jupiter.api.Assertions.assertTrue(kept.path("declaring_class_tests").size() < 20);
        org.junit.jupiter.api.Assertions.assertTrue(
                kept.path("declaring_class_tests").size() >= 5, kept.toString().substring(0, 200));
    }

    // Apollo AQ-041 probe: asked about five things, the budget removed the one SpEL reference whole
    // while lists stayed long.
    @Test
    void amongAskedFieldsListsAreHalvedBeforeAFieldGoesWhole() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var fact =
                data.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "x.A");
        fact.putObject("expression_references")
                .put("total", 1)
                .putArray("references")
                .add("C#create @PreAuthorize(@v.can(#a)) " + "e".repeat(150));
        var tests = fact.putArray("declaring_class_tests");
        for (int i = 0; i < 5; i++) tests.add("x.T" + i + "Test " + "t".repeat(120));
        var callers = fact.putArray("production_callers");
        for (int i = 0; i < 4; i++) callers.add("x.C" + i + "#run " + "p".repeat(120));
        fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 1300));
        PrefetchContext.boundContext(
                data,
                "Separate the Java callers, references outside Java code and tests for x.A#check.");
        var kept = data.path("facts").get(0);
        org.junit.jupiter.api.Assertions.assertTrue(
                kept.has("expression_references"), data.path("context_omitted_fields").toString());
    }

    // Probe of V66 CQ-027 with calls kept: the cut moved to framework_contract.rules and lost the
    // lambda and rollback rules.
    @Test
    void aListInsideALastResortFieldIsAlsoCutLast() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var fact =
                data.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "x.A");
        fact.putObject("framework_contract")
                .putArray("rules")
                .add("r".repeat(150))
                .add("s".repeat(150))
                .add("t".repeat(150))
                .add("u".repeat(150));
        fact.putArray("referenced_members")
                .add("mapper: " + "m".repeat(200))
                .add("logger: " + "l".repeat(200));
        fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 1000));
        PrefetchContext.boundContext(data);
        org.junit.jupiter.api.Assertions.assertEquals(
                4,
                data.path("facts").get(0).path("framework_contract").path("rules").size(),
                data.path("facts").get(0).path("referenced_members_truncated").toString());
    }

    // V63 CQ-046: callers' result use pushed the impact over budget and halved test_callers, the
    // answer to a which-tests question.
    @Test
    void theBudgetDropsCallerResultUseBeforeHalvingTestCallers() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.createObjectNode();
        var fact =
                data.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("target", "x.A");
        fact.putArray("test_callers")
                .add("x.ATest " + "t".repeat(600))
                .add("x.BTest " + "t".repeat(600));
        fact.putArray("caller_result_use")
                .add(
                        "x.B#run line 3: var r = a.resolve(n); then r.get() (line 4) "
                                + "r".repeat(300));
        fact.put("other", "z".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES - 1450));
        PrefetchContext.boundContext(data);
        org.junit.jupiter.api.Assertions.assertEquals(
                2,
                data.path("facts").get(0).path("test_callers").size(),
                data.path("context_omitted_fields").toString());
        org.junit.jupiter.api.Assertions.assertFalse(
                data.path("facts").get(0).has("caller_result_use"));
    }
}
