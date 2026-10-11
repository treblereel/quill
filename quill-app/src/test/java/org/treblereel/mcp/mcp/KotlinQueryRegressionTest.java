package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class KotlinQueryRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    Jdbi db;

    @BeforeEach void setup() {
        db = QuillDatabase.create(temp.resolve("index.db"));
        type(1, "acme.Owner", "CLASS"); type(2, "acme.Client", null);
    }

    private void type(int id, String name, String kotlinKind) {
        db.useHandle(h -> h.createUpdate("INSERT INTO classes(id,class_name,kind,interfaces,source_file,source_line,origin,lifecycle) VALUES (:id,:name,'CLASS','[]',:source,1,'source','current')")
                .bind("id", id).bind("name", name).bind("source", "Owner.kt").execute());
        if (kotlinKind != null) declaration(id, kotlinKind, name, name, "", false, false, false);
    }

    private void declaration(int owner, String kind, String name, String jvm, String desc,
            boolean suspend, boolean extension, boolean defaults) {
        db.useHandle(h -> h.createUpdate("INSERT INTO kotlin_declarations(class_id,kind,name,jvm_name,descriptor,semantic_model,is_suspend,is_extension,has_default_parameters) VALUES (:id,:kind,:name,:jvm,:desc,'kotlin_metadata',:suspend,:extension,:defaults)")
                .bind("id", owner).bind("kind", kind).bind("name", name).bind("jvm", jvm).bind("desc", desc)
                .bind("suspend", suspend).bind("extension", extension).bind("defaults", defaults).execute());
    }

    private void member(int owner, String kind, String name, String desc, String params) {
        db.useHandle(h -> h.createUpdate("INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) VALUES (:id,:kind,:name,:desc,:desc,'java.lang.String',:params,'public','[]')")
                .bind("id", owner).bind("kind", kind).bind("name", name).bind("desc", desc).bind("params", params).execute());
    }

    private void call(int from, String method, String desc, int to, String target, String targetDesc, int line) {
        db.useHandle(h -> h.createUpdate("INSERT INTO method_calls(from_class_id,from_method,from_descriptor,to_class_id,to_method,to_descriptor,invocation_kind,evidence_lines) VALUES (:from,:method,:desc,:to,:target,:td,'static',:lines)")
                .bind("from", from).bind("method", method).bind("desc", desc).bind("to", to)
                .bind("target", target).bind("td", targetDesc).bind("lines", "[" + line + "]").execute());
    }

    @Test void defaultArgumentsReportOriginalCallerAndPreserveBytecodeEvidence() throws Exception {
        String desc = "(Ljava/lang/String;)Ljava/lang/String;";
        String bridge = "(Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;";
        member(1, "METHOD", "greeting", desc, "[\"java.lang.String\"]");
        declaration(1, "FUNCTION", "greeting", "greeting", desc, false, false, true);
        call(1, "greeting$default", bridge, 1, "greeting", desc, 0);
        call(2, "run", "()V", 1, "greeting$default", bridge, 42);
        var result = JSON.readTree(new SymbolUsageQueries().findSymbolUsages(db, "acme.Owner", "greeting", "method", desc, "all", 1, 0));
        assertEquals(1, result.path("total").asInt(), result.toPrettyString());
        var usage = result.path("usages").get(0);
        assertEquals("acme.Client", usage.path("caller").path("class").asText());
        assertEquals(42, usage.path("evidence_lines").get(0).asInt());
        assertEquals(2, usage.path("bytecode_path").size());
        assertTrue(usage.path("normalized").asBoolean());
        assertEquals(0, JSON.readTree(new SymbolUsageQueries().findSymbolUsages(db, "acme.Owner", "greeting", "method", desc, "all", 1, 1)).path("showing").asInt());
    }

    @Test void unusedDefaultBridgeDoesNotCreateSourceUsage() {
        String desc = "(I)V", bridge = "(IILjava/lang/Object;)V";
        declaration(1, "FUNCTION", "f", "f", desc, false, false, true);
        call(1, "f$default", bridge, 1, "f", desc, 0);
        assertTrue(KotlinUsageNormalizer.inbound(db, 1, "f", desc).usages().isEmpty());
    }

    @Test void preservesRealSourceFunctionsAndRejectsInvalidDefaultDescriptor() {
        String desc = "(I)V", bridge = "(IILjava/lang/Object;)V";
        declaration(1, "FUNCTION", "f", "f", desc, false, false, true);
        declaration(1, "FUNCTION", "custom", "f$default", bridge, false, false, false);
        call(1, "f$default", bridge, 1, "f", desc, 5);
        call(1, "f$default", "(II)V", 1, "f", desc, 6);
        assertEquals(2, KotlinUsageNormalizer.inbound(db, 1, "f", desc).usages().size());
        assertTrue(KotlinUsageNormalizer.inbound(db, 1, "f", desc).usages().stream().allMatch(u -> u.path().size() == 1));
    }

    @Test void handlesConstructorDefaultsAndTwoMasksAtParameterBoundary() {
        String constructor = "(I)V", defaultConstructor = "(IILkotlin/jvm/internal/DefaultConstructorMarker;)V";
        call(1, "<init>", defaultConstructor, 1, "<init>", constructor, 0);
        call(2, "construct", "()V", 1, "<init>", defaultConstructor, 12);
        assertEquals("construct", KotlinUsageNormalizer.inbound(db, 1, "<init>", constructor).usages().getFirst().call().fromMethod());
        String args = "I".repeat(33), desc = "(" + args + ")V", bridge = "(" + args + "IILjava/lang/Object;)V";
        declaration(1, "FUNCTION", "many", "many", desc, false, false, true);
        call(1, "many$default", bridge, 1, "many", desc, 0);
        call(2, "many", "()V", 1, "many$default", bridge, 13);
        assertEquals(2, KotlinUsageNormalizer.inbound(db, 1, "many", desc).usages().getFirst().path().size());
    }

    @Test void suspendContinuationAndExtensionReceiverDoNotConsumeDefaultMaskBits() {
        String args = "Ljava/lang/String;" + "I".repeat(32) + "Lkotlin/coroutines/Continuation;";
        String desc = "(" + args + ")Ljava/lang/Object;";
        String bridge = "(" + args + "ILjava/lang/Object;)Ljava/lang/Object;";
        declaration(1, "FUNCTION", "f", "f", desc, true, true, true);
        call(1, "f$default", bridge, 1, "f", desc, 0);
        call(2, "run", "()V", 1, "f$default", bridge, 4);
        assertEquals(2, KotlinUsageNormalizer.inbound(db, 1, "f", desc).usages().getFirst().path().size());
    }

    @Test void expandsMultifileAndCompanionForwardingButKeepsOrdinaryForwarders() {
        type(3, "acme.Api", "MULTIFILE_FACADE"); type(4, "acme.Api__PartKt", "MULTIFILE_PART");
        declaration(4, "FUNCTION", "f", "f", "()V", false, false, false);
        call(3, "f", "()V", 4, "f", "()V", 0); call(2, "run", "()V", 3, "f", "()V", 7);
        assertEquals("run", KotlinUsageNormalizer.inbound(db, 4, "f", "()V").usages().getFirst().call().fromMethod());
        type(5, "acme.Owner$Companion", "COMPANION_OBJECT");
        declaration(5, "FUNCTION", "g", "g", "()V", false, false, false);
        call(1, "g", "()V", 5, "g", "()V", 0); call(2, "companion", "()V", 1, "g", "()V", 8);
        assertEquals("companion", KotlinUsageNormalizer.inbound(db, 5, "g", "()V").usages().getFirst().call().fromMethod());
        declaration(1, "FUNCTION", "g", "g", "()V", false, false, false);
        assertEquals("g", KotlinUsageNormalizer.inbound(db, 5, "g", "()V").usages().getFirst().call().fromMethod());
    }

    @Test void propertyUnifiesAccessorsAndFieldWithReadWriteFiltersAndStableId() throws Exception {
        declaration(1, "PROPERTY", "state", "state", "Ljava/lang/String;", false, false, false);
        declaration(1, "PROPERTY", "state", "getState", "()Ljava/lang/String;", false, false, false);
        declaration(1, "PROPERTY", "state", "setState", "(Ljava/lang/String;)V", false, false, false);
        member(1, "FIELD", "state", "Ljava/lang/String;", "[]");
        call(2, "read", "()V", 1, "getState", "()Ljava/lang/String;", 10);
        call(2, "write", "()V", 1, "setState", "(Ljava/lang/String;)V", 11);
        field(2, "direct", "read_instance", 12); field(1, "getState", "read_instance", 1);
        var search = JSON.readTree(new SymbolSearchQueries().searchSymbols(db, "state", "property", "kotlin", 10, 0));
        assertEquals(1, search.path("total").asInt());
        String id = search.path("symbols").get(0).path("symbol_id").asText();
        assertEquals("PROPERTY", SymbolContract.parse(id).orElseThrow().kind());
        var query = new SymbolUsageQueries();
        assertEquals(3, JSON.readTree(query.findSymbolUsages(db, id, null, null, null, "all", 10, 0)).path("total").asInt());
        assertEquals(2, JSON.readTree(query.findSymbolUsages(db, id, null, null, null, "read", 10, 0)).path("total").asInt());
        assertEquals(1, JSON.readTree(query.findSymbolUsages(db, id, null, null, null, "write", 10, 0)).path("total").asInt());
        assertEquals(1, JSON.readTree(query.findSymbolUsages(db, id, null, null, null, "all", 1, 2)).path("showing").asInt());
        assertEquals(0, JSON.readTree(new SymbolSearchQueries().searchSymbols(db, "state", "property", "java", 10, 0)).path("total").asInt());
    }

    private void field(int from, String method, String access, int line) {
        db.useHandle(h -> h.createUpdate("INSERT INTO field_accesses(from_class_id,from_method,from_descriptor,to_class_id,field_name,field_descriptor,access_kind,evidence_lines) VALUES (:from,:method,:desc,1,'state','Ljava/lang/String;',:access,:lines)")
                .bind("from", from).bind("method", method).bind("desc", method.equals("getState") ? "()Ljava/lang/String;" : "()V")
                .bind("access", access).bind("lines", "["+line+"]").execute());
    }

    @Test void distinguishesOverloadedExtensionPropertiesAndKeepsUnboundProperties() throws Exception {
        declaration(1, "PROPERTY", "size", "size", "(Ljava/lang/String;)I", false, true, false);
        declaration(1, "PROPERTY", "size", "size", "(Ljava/util/List;)I", false, true, false);
        declaration(1, "PROPERTY", "unbound", null, "", false, false, false);
        var query = new SymbolUsageQueries();
        assertEquals("AMBIGUOUS_SYMBOL", JSON.readTree(query.findSymbolUsages(db, "acme.Owner", "size", "property", null, "all", 10, 0)).path("error_code").asText());
        assertEquals(0, JSON.readTree(query.findSymbolUsages(db, "acme.Owner", "size", "property", "(Ljava/lang/String;)I", "all", 10, 0)).path("total").asInt());
        var search = JSON.readTree(new SymbolSearchQueries().searchSymbols(db, "unbound", "property", null, 10, 0));
        String id = search.path("symbols").get(0).path("symbol_id").asText();
        assertTrue(SymbolContract.parse(id).isPresent());
        assertEquals(0, JSON.readTree(query.findSymbolUsages(db, id, null, null, null, "all", 10, 0)).path("total").asInt());
    }

    @Test void mapsCompanionPropertyToPhysicalOuterField() {
        type(3, "acme.Owner$Companion", "COMPANION_OBJECT");
        declaration(3, "PROPERTY", "state", "state", "Ljava/lang/String;", false, false, false);
        member(1, "FIELD", "state", "Ljava/lang/String;", "[]");
        var property = KotlinProperties.list(db, org.treblereel.mcp.db.IndexReader.findClassByName(db, "acme.Owner$Companion").orElseThrow()).getFirst();
        assertEquals(1, property.bindings().getFirst().owner().id());
        assertEquals("acme.Owner$Companion", SymbolContract.parse(property.symbolId()).orElseThrow().className());
    }

    private JsonNode position(String source, String token) throws Exception {
        Files.writeString(temp.resolve("Owner.kt"), source);
        int offset = source.lastIndexOf(token), line = 1, column = 1;
        for (int i = 0; i < offset; i++) { if (source.charAt(i) == '\n') { line++; column=1; } else column++; }
        return JSON.readTree(new PositionSymbolQueries().getSymbolAtPosition(db, temp, "Owner.kt", line, column));
    }

    @Test void resolvesKotlinConstructorsWithoutNewAndBacktickNames() throws Exception {
        member(1, "CONSTRUCTOR", "Owner", "(I)V", "[\"int\"]");
        assertEquals("CONSTRUCTOR", position("package acme\nval value = Owner(1)", "Owner").path("selected").path("kind").asText());
        member(1, "METHOD", "an odd name", "()V", "[]");
        declaration(1, "FUNCTION", "an odd name", "an odd name", "()V", false, false, false);
        assertEquals("an odd name", position("fun caller() { `an odd name`() }", "odd").path("selected").path("source_name").asText());
    }

    @Test void countsSourceParametersAndHandlesDefaultMultilineNestedArguments() throws Exception {
        String desc = "(Ljava/lang/String;ILkotlin/coroutines/Continuation;)Ljava/lang/Object;";
        member(1, "METHOD", "load", desc, "[\"java.lang.String\",\"int\",\"kotlin.coroutines.Continuation\"]");
        declaration(1, "FUNCTION", "load", "load", desc, true, true, true);
        var result = position("fun caller() = receiver.load(\n limit = listOf(1, 2).size,\n)", "load");
        assertEquals(1, result.path("argument_count").asInt());
        assertEquals(1, result.path("selected").path("parameter_count").asInt());
        assertEquals("medium", result.path("confidence").asText());
        assertEquals(0, position("fun caller() = receiver.load()", "load").path("argument_count").asInt());
        assertEquals(1, position("fun caller() = receiver.load({ x, y -> x })", "load").path("argument_count").asInt());
    }

    @Test void aliasesRestrictCandidatesToImportedOwnerAndIgnoreCommentsStrings() throws Exception {
        member(1, "METHOD", "submit", "()V", "[]");
        type(3, "other.Owner", "CLASS"); member(3, "METHOD", "submit", "()V", "[]");
        var alias = position("import acme.Owner.submit as send\nfun caller() = send()", "send");
        assertEquals("acme.Owner", alias.path("selected").path("class_name").asText());
        assertEquals("SYMBOL_NOT_FOUND", position("// submit()", "submit").path("error_code").asText());
        assertEquals("SYMBOL_NOT_FOUND", position("val text = \"submit()\"", "submit").path("error_code").asText());
        assertEquals("ambiguous", position("fun caller() = submit()", "submit").path("resolution").asText());
    }
    @Test void commentQuotesAreNotArgumentsButStringCharAndRawLiteralsAre() throws Exception {
        member(1, "METHOD", "f", "(I)V", "[\"int\"]");
        assertEquals(0, position("fun run() = f(/* \"fake\" */)", "f(").path("argument_count").asInt());
        assertEquals(0, position("fun run() = f(// \"fake\"\n)", "f(").path("argument_count").asInt());
        assertEquals(2, position("fun run() = f(\"a,b\", 'x')", "f(").path("argument_count").asInt());
        assertEquals(1, position("fun run() = f(\"\"\"a,b\ntext\"\"\",)", "f(").path("argument_count").asInt());
        assertTrue(position("fun run() = f(a < b)", "f(").path("argument_count").isNull());
    }

    @Test void topLevelImportAliasUsesMetadataRatherThanKtSuffixAndPropertyNavigationIsLogical() throws Exception {
        type(3, "other.CustomFacade", "FILE_FACADE");
        member(3, "METHOD", "submit", "()V", "[]");
        declaration(3, "FUNCTION", "submit", "submit", "()V", false, false, false);
        member(1, "METHOD", "submit", "()V", "[]");
        assertEquals("other.CustomFacade", position("import other.submit as send\nfun run() = send()", "send").path("selected").path("class_name").asText());
        declaration(1, "PROPERTY", "state", "getState", "()Ljava/lang/String;", false, false, false);
        declaration(1, "PROPERTY", "state", "setState", "(Ljava/lang/String;)V", false, false, false);
        member(1, "METHOD", "getState", "()Ljava/lang/String;", "[]");
        member(1, "METHOD", "setState", "(Ljava/lang/String;)V", "[\"java.lang.String\"]");
        var selected = position("fun run() = owner.state", "state").path("selected");
        assertEquals("PROPERTY", selected.path("kind").asText());
        assertEquals("PROPERTY", SymbolContract.parse(selected.path("symbol_id").asText()).orElseThrow().kind());
    }

    @Test void instanceDefaultsExcludeDispatchReceiverAndRejectWrongMasks() {
        String original = "(I)V", bridge = "(Lacme/Owner;IILjava/lang/Object;)V";
        declaration(1, "FUNCTION", "f", "f", original, false, false, true);
        call(1, "f$default", bridge, 1, "f", original, 0);
        db.useHandle(h -> h.execute("UPDATE method_calls SET invocation_kind = 'virtual'"));
        call(2, "run", "()V", 1, "f$default", bridge, 14);
        assertEquals("run", KotlinUsageNormalizer.inbound(db, 1, "f", original).usages().getFirst().call().fromMethod());
        call(1, "f$default", "(Lacme/Owner;IJLjava/lang/Object;)V", 1, "f", original, 0);
        assertEquals(2, KotlinUsageNormalizer.inbound(db, 1, "f", original).usages().size());
    }

    @Test void delegatedFieldIsNotConfusedWithPropertyValue() {
        declaration(1, "PROPERTY", "lazyValue", "lazyValue$delegate", "Lkotlin/Lazy;", false, false, false);
        declaration(1, "PROPERTY", "lazyValue", "getLazyValue", "()Ljava/lang/String;", false, false, false);
        db.useHandle(h -> h.execute("UPDATE kotlin_declarations SET is_delegated = 1 WHERE kind = 'PROPERTY'"));
        var properties = KotlinProperties.list(db, org.treblereel.mcp.db.IndexReader.findClassByName(db, "acme.Owner").orElseThrow());
        assertEquals(1, properties.size());
        assertEquals("()Ljava/lang/String;", properties.getFirst().descriptor());
        assertEquals(1, properties.getFirst().bindings().size());
        assertFalse(properties.getFirst().bindings().getFirst().field());
    }

    @Test void normalizationCapIsExplicitAndOrdinaryJavaCallsStayUnnormalized() throws Exception {
        member(1, "METHOD", "f", "()V", "[]");
        declaration(1, "FUNCTION", "f", "f", "()V", false, false, false);
        db.useHandle(h -> {
            var batch = h.prepareBatch("INSERT INTO method_calls(from_class_id,from_method,from_descriptor,to_class_id,to_method,to_descriptor,invocation_kind) VALUES (2,:name,'()V',1,'f','()V','static')");
            for (int i = 0; i < 5001; i++) batch.bind("name", "run" + i).add();
            batch.execute();
        });
        var result = JSON.readTree(new SymbolUsageQueries().findSymbolUsages(db, "acme.Owner", "f", "method", null, "all", 1, 0));
        assertTrue(result.path("normalization_truncated").asBoolean());
        assertFalse(result.path("answer_complete").asBoolean());
        assertEquals(5000, result.path("total").asInt());
        db.useHandle(h -> h.execute("DELETE FROM kotlin_declarations"));
        var java = JSON.readTree(new SymbolUsageQueries().findSymbolUsages(db, "acme.Owner", "f", "method", null, "all", 1, 5000));
        assertEquals(5001, java.path("total").asInt());
        assertEquals(1, java.path("showing").asInt());
        assertFalse(java.path("normalization_truncated").asBoolean());
    }

    @Test void jvmStaticOverloadThroughCompanionOverloadResolvesToJavaCaller() {
        type(3, "acme.Owner$Companion", "COMPANION_OBJECT");
        String original = "(I)V", overload = "()V", bridge = "(Lacme/Owner$Companion;IILjava/lang/Object;)V";
        declaration(3, "FUNCTION", "f", "f", original, false, false, true);
        member(3, "METHOD", "f", overload, "[]");
        db.useHandle(h -> h.execute("UPDATE class_members SET annotations = '[\"kotlin.jvm.JvmOverloads\"]'"));
        call(3, "f$default", bridge, 3, "f", original, 0);
        db.useHandle(h -> h.execute("UPDATE method_calls SET invocation_kind = 'virtual'"));
        call(3, "f", overload, 3, "f$default", bridge, 0);
        call(1, "f", overload, 3, "f", overload, 0);
        call(2, "run", "()V", 1, "f", overload, 17);
        var result = KotlinUsageNormalizer.inbound(db, 3, "f", original);
        assertEquals(1, result.usages().size());
        assertEquals("run", result.usages().getFirst().call().fromMethod());
        assertEquals(4, result.usages().getFirst().path().size());
    }

    private void parameters(int owner, String kind, String jvm, String descriptor, String json) {
        db.useHandle(h -> h.createUpdate("UPDATE kotlin_declarations SET value_parameters=:json WHERE class_id=:id AND kind=:kind AND jvm_name=:name AND descriptor=:desc")
                .bind("json", json).bind("id", owner).bind("kind", kind).bind("name", jvm).bind("desc", descriptor).execute());
    }

    @Test void requiredParametersAfterDefaultsNeedNamedArgumentsWhenSkipped() throws Exception {
        String desc = "(II)V";
        member(1, "METHOD", "f", desc, "[\"int\",\"int\"]");
        declaration(1, "FUNCTION", "f", "f", desc, false, false, true);
        parameters(1, "FUNCTION", "f", desc, "[{\"name\":\"optional\",\"hasDefault\":true,\"vararg\":false},{\"name\":\"required\",\"hasDefault\":false,\"vararg\":false}]");
        assertFalse(position("fun run() = f()", "f(").has("selected"));
        assertEquals("no_applicable_candidate", position("fun run() = f()", "f(").path("resolution").asText());
        assertEquals("low", position("fun run() = f()", "f(").path("confidence").asText());
        assertFalse(position("fun run() = f(1)", "f(").has("selected"));
        assertTrue(position("fun run() = f(required = 1)", "f(").has("selected"));
        assertTrue(position("fun run() = f(1, required = 2)", "f(").has("selected"));
        assertTrue(position("fun run() = f(required = 2, optional = 1)", "f(").has("selected"));
        assertFalse(position("fun run() = f(unknown = 1)", "f(").has("selected"));
        assertFalse(position("fun run() = f(required = 1, required = 2)", "f(").has("selected"));
        assertFalse(position("fun run() = f(1, 2, 3)", "f(").has("selected"));
        assertEquals(1, position("fun run() = f(required = 1)", "f(").path("selected").path("required_parameter_count").asInt());
    }

    @Test void varargsAllowEmptyMultipleAndSpreadButNonLastVarargNeedsNamedSuffix() throws Exception {
        String desc = "([II)V";
        member(1, "METHOD", "f", desc, "[\"int[]\",\"int\"]");
        declaration(1, "FUNCTION", "f", "f", desc, false, false, false);
        parameters(1, "FUNCTION", "f", desc, "[{\"name\":\"values\",\"hasDefault\":false,\"vararg\":true},{\"name\":\"tail\",\"hasDefault\":false,\"vararg\":false}]");
        assertTrue(position("fun run() = f(tail = 1)", "f(").has("selected"));
        assertTrue(position("fun run() = f(1, 2, 3, tail = 4)", "f(").has("selected"));
        assertTrue(position("fun run() = f(*values, tail = 4)", "f(").has("selected"));
        assertFalse(position("fun run() = f(1, 2, 3)", "f(").has("selected"));
        assertFalse(position("fun run() = f(*values, tail = *other)", "f(").has("selected"));
        assertEquals(2, position("fun run() = f(1) { work() }", "f(").path("argument_count").asInt());
    }

    @Test void constructorsUseSourceParametersInsteadOfHiddenJvmReceiver() throws Exception {
        String desc = "(Lacme/Outer;I)V";
        member(1, "CONSTRUCTOR", "Owner", desc, "[\"acme.Outer\",\"int\"]");
        declaration(1, "CONSTRUCTOR", "<init>", "<init>", desc, false, false, true);
        parameters(1, "CONSTRUCTOR", "<init>", desc, "[{\"name\":\"count\",\"hasDefault\":true,\"vararg\":false}]");
        var selected = position("fun run() = outer.Owner()", "Owner(").path("selected");
        assertEquals("CONSTRUCTOR", selected.path("kind").asText());
        assertEquals(1, selected.path("parameter_count").asInt());
        assertTrue(position("fun run() = outer.Owner(count = 1)", "Owner(").has("selected"));
        assertFalse(position("fun run() = outer.Owner(1, 2)", "Owner(").has("selected"));
    }

    @Test void twoApplicableDefaultOverloadsRemainAmbiguousWithoutTypeInference() throws Exception {
        for (String desc : List.of("(I)V", "(Ljava/lang/String;)V")) {
            member(1, "METHOD", "f", desc, "[\"parameter\"]");
            declaration(1, "FUNCTION", "f", "f", desc, false, false, true);
            parameters(1, "FUNCTION", "f", desc, "[{\"name\":\"value\",\"hasDefault\":true,\"vararg\":false}]");
        }
        var result = position("fun run() = f()", "f(");
        assertEquals("ambiguous", result.path("resolution").asText());
        assertFalse(result.has("selected"));
        assertEquals(2, result.path("candidate_count").asInt());
    }

    @Test void defaultConstructorMaskCountExcludesHiddenOuterParameterAt32Boundary() {
        String original = "(Lacme/Outer;" + "I".repeat(32) + ")V";
        String bridge = "(Lacme/Outer;" + "I".repeat(32) + "ILkotlin/jvm/internal/DefaultConstructorMarker;)V";
        declaration(1, "CONSTRUCTOR", "<init>", "<init>", original, false, false, true);
        var values = new java.util.ArrayList<org.treblereel.mcp.model.KotlinValueParameter>();
        for (int i=0; i<32; i++) values.add(new org.treblereel.mcp.model.KotlinValueParameter("p"+i, true, false));
        parameters(1, "CONSTRUCTOR", "<init>", original, JSON.valueToTree(values).toString());
        call(1, "<init>", bridge, 1, "<init>", original, 0);
        call(2, "run", "()V", 1, "<init>", bridge, 3);
        assertEquals("run", KotlinUsageNormalizer.inbound(db, 1, "<init>", original).usages().getFirst().call().fromMethod());
    }

    private void generatedFunction() {
        member(1, "METHOD", "f", "(I)V", "[\"int\"]");
        member(1, "METHOD", "f", "()V", "[]");
        declaration(1, "FUNCTION", "f", "f", "(I)V", false, false, true);
        parameters(1, "FUNCTION", "f", "(I)V", "[{\"name\":\"value\",\"hasDefault\":true,\"vararg\":false}]");
        db.useHandle(h -> h.execute("UPDATE class_members SET annotations='[\"kotlin.jvm.JvmOverloads\"]' WHERE descriptor='()V'"));
        call(1, "f", "()V", 1, "f$default", "(IILjava/lang/Object;)V", 0);
        call(1, "f$default", "(IILjava/lang/Object;)V", 1, "f", "(I)V", 0);
    }

    @Test void generatedOverloadNavigationSelectsOriginalDescriptor() throws Exception {
        generatedFunction();
        for (String arguments : List.of("", "1", "value = 1")) {
            var result = position("fun run() = f(" + arguments + ")", "f(");
            assertEquals("resolved", result.path("resolution").asText(), result.toPrettyString());
            assertEquals("(I)V", result.path("selected").path("jvm_descriptor").asText());
            assertEquals(1, result.path("candidate_count").asInt());
        }
    }

    @Test void realSourceOverloadRemainsEvenWhenAnnotatedAndForwarding() throws Exception {
        generatedFunction();
        declaration(1, "FUNCTION", "f", "f", "()V", false, false, false);
        parameters(1, "FUNCTION", "f", "()V", "[]");
        var result = position("fun run() = f()", "f(");
        assertEquals(2, result.path("candidate_count").asInt());
        assertEquals("ambiguous", result.path("resolution").asText());
        assertFalse(result.has("selected"));
    }

    @Test void missingAnnotationOrMetadataOrUniqueEdgeKeepsPhysicalCandidate() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) {
            db.useHandle(h -> { h.execute("DELETE FROM class_members"); h.execute("DELETE FROM method_calls");
                h.execute("DELETE FROM kotlin_declarations WHERE kind='FUNCTION'"); });
            generatedFunction();
            if (scenario == 0) db.useHandle(h -> h.execute("UPDATE class_members SET annotations='[]'"));
            if (scenario == 1) db.useHandle(h -> h.execute("DELETE FROM kotlin_declarations WHERE kind='FUNCTION'"));
            if (scenario == 2) call(1, "f", "()V", 2, "other", "()V", 1);
            if (scenario == 3) db.useHandle(h -> h.execute("UPDATE method_calls SET to_descriptor='(II)V' WHERE from_method='f$default'"));
            assertEquals(2, position("fun run() = f()", "f(").path("candidate_count").asInt(), "scenario " + scenario);
            assertTrue(KotlinUsageNormalizer.sourceTarget(db, 1, "f", "()V").isEmpty());
        }
    }

    @Test void generatedConstructorOverloadNavigatesAndNormalizesToSource() throws Exception {
        member(1, "CONSTRUCTOR", "Owner", "(I)V", "[\"int\"]");
        member(1, "CONSTRUCTOR", "Owner", "()V", "[]");
        declaration(1, "CONSTRUCTOR", "Owner", "<init>", "(I)V", false, false, true);
        parameters(1, "CONSTRUCTOR", "<init>", "(I)V", "[{\"name\":\"value\",\"hasDefault\":true,\"vararg\":false}]");
        db.useHandle(h -> h.execute("UPDATE class_members SET annotations='[\"kotlin.jvm.JvmOverloads\"]' WHERE descriptor='()V'"));
        String bridge = "(IILkotlin/jvm/internal/DefaultConstructorMarker;)V";
        call(1, "<init>", "()V", 1, "<init>", bridge, 0);
        call(1, "<init>", bridge, 1, "<init>", "(I)V", 0);
        call(2, "run", "()V", 1, "<init>", "()V", 10);
        var result = position("fun run() = Owner()", "Owner(");
        assertEquals("(I)V", result.path("selected").path("jvm_descriptor").asText(), result.toPrettyString());
        assertEquals("run", KotlinUsageNormalizer.inbound(db, 1, "<init>", "(I)V").usages().getFirst().call().fromMethod());
        declaration(1, "CONSTRUCTOR", "Owner", "<init>", "()V", false, false, false);
        assertTrue(KotlinUsageNormalizer.sourceTarget(db, 1, "<init>", "()V").isEmpty());
    }

    @Test void javaNavigationKeepsJvmOverloads() throws Exception {
        generatedFunction();
        String source = "class Caller { void run() { acme.Owner.f(); } }";
        Files.writeString(temp.resolve("Caller.java"), source);
        var result = JSON.readTree(new PositionSymbolQueries().getSymbolAtPosition(db, temp,
                "Caller.java", 1, source.indexOf("f();") + 1));
        assertEquals(2, result.path("candidate_count").asInt());
        assertEquals("()V", result.path("selected").path("jvm_descriptor").asText(), result.toPrettyString());
    }

    @Test void forwardingCyclesAndRepeatedEdgesDoNotHideCandidates() throws Exception {
        generatedFunction();
        db.useHandle(h -> h.execute("UPDATE method_calls SET occurrence_count=2 WHERE from_method='f'"));
        assertTrue(KotlinUsageNormalizer.sourceTarget(db, 1, "f", "()V").isEmpty());
        db.useHandle(h -> h.execute("DELETE FROM method_calls"));
        member(1, "METHOD", "f", "(J)V", "[\"long\"]");
        db.useHandle(h -> h.execute("UPDATE class_members SET annotations='[\"kotlin.jvm.JvmOverloads\"]'"));
        call(1, "f", "()V", 1, "f", "(J)V", 0);
        call(1, "f", "(J)V", 1, "f", "()V", 0);
        assertTrue(KotlinUsageNormalizer.sourceTarget(db, 1, "f", "()V").isEmpty());
        assertEquals(3, position("fun run() = f()", "f(").path("candidate_count").asInt());
    }

    private void lambdaFunction(String descriptor, String parametersJson) {
        member(1, "METHOD", "action", descriptor, "[]");
        declaration(1, "FUNCTION", "action", "action", descriptor, false, false, true);
        parameters(1, "FUNCTION", "action", descriptor, parametersJson);
    }

    @Test void trailingLambdaBindsLastParameterAndCountsWithoutParentheses() throws Exception {
        lambdaFunction("(ILkotlin/jvm/functions/Function0;)V",
                "[{\"name\":\"optional\",\"hasDefault\":true,\"vararg\":false},{\"name\":\"body\",\"hasDefault\":false,\"vararg\":false}]");
        for (String expression : List.of("action { nested { 1 }; \"},(\" }", "action() { 1 }", "action(1) label@ { 1 }",
                "action(optional = 1) /* comment */ { 1 }", "action\n{ 1 }")) {
            var result = position("fun run() = " + expression, "action");
            assertEquals("resolved", result.path("resolution").asText(), result.toPrettyString());
            assertTrue(result.path("selected").path("ranking_reasons").toString().contains("metadata_arguments_match"));
        }
        assertEquals(1, position("fun run() = action { 1 }", "action").path("argument_count").asInt());
        assertFalse(position("fun run() = action(body = { 1 }) { 2 }", "action").has("selected"));
        assertFalse(position("fun run() = action()", "action").has("selected"));
        assertTrue(position("fun run() = action { nested { 1 }", "action").path("argument_count").isNull());
        assertTrue(position("fun run() = action {} {}", "action").path("argument_count").isNull());
    }

    @Test void escapedNamedArgumentsDoNotSplitOnPunctuationOrKeyword() throws Exception {
        lambdaFunction("(II)V", "[{\"name\":\"when\",\"hasDefault\":false,\"vararg\":false},{\"name\":\"a,b=(x)\",\"hasDefault\":false,\"vararg\":false}]");
        assertTrue(position("fun run() = action(`when` = 1, `a,b=(x)` = 2)", "action").has("selected"));
        assertTrue(position("fun run() = action(`a,b=(x)` = 2, `when` = 1)", "action").has("selected"));
        assertFalse(position("fun run() = action(`when` = 1, `when` = 2)", "action").has("selected"));
        assertFalse(position("fun run() = action(`unknown` = 1, `a,b=(x)` = 2)", "action").has("selected"));
        assertTrue(position("fun run() = action(`when = 1)", "action").path("argument_count").isNull());
    }

    @Test void mixedArgumentsRequireUnbrokenDeclarationOrder() throws Exception {
        lambdaFunction("(III)V", "[{\"name\":\"first\",\"hasDefault\":false,\"vararg\":false},{\"name\":\"second\",\"hasDefault\":false,\"vararg\":false},{\"name\":\"third\",\"hasDefault\":false,\"vararg\":false}]");
        for (String args : List.of("first = 1, 2, 3", "1, second = 2, 3", "first = 1, second = 2, 3", "third = 3, first = 1, second = 2"))
            assertTrue(position("fun run() = action(" + args + ")", "action").has("selected"), args);
        for (String args : List.of("second = 2, 1, third = 3", "1, third = 3, 2", "first = 1, first = 2, 3"))
            assertFalse(position("fun run() = action(" + args + ")", "action").has("selected"), args);
    }

    @Test void lambdaAfterVarargAndDefaultLambdaBindsLastSlot() throws Exception {
        lambdaFunction("([ILkotlin/jvm/functions/Function0;)V", "[{\"name\":\"values\",\"hasDefault\":false,\"vararg\":true},{\"name\":\"body\",\"hasDefault\":false,\"vararg\":false}]");
        assertTrue(position("fun run() = action(1, 2, *values) { 1 }", "action").has("selected"));
        assertTrue(position("fun run() = action(values = values) { 1 }", "action").has("selected"));
        assertFalse(position("fun run() = action(values = values, 1) { 1 }", "action").has("selected"));
        db.useHandle(h -> { h.execute("DELETE FROM class_members"); h.execute("DELETE FROM kotlin_declarations WHERE kind='FUNCTION'"); });
        lambdaFunction("(Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;)V", "[{\"name\":\"first\",\"hasDefault\":true,\"vararg\":false},{\"name\":\"last\",\"hasDefault\":false,\"vararg\":false}]");
        assertTrue(position("fun run() = action { 1 }", "action").has("selected"));
        assertFalse(position("fun run() = action(last = {}) {}", "action").has("selected"));
    }

}
