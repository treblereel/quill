package org.treblereel.mcp.mcp.prefetch;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The prefetch fields a question asks about, so the budget cuts them last. Rules guarding single
 * fields kept moving the loss to another one (Apollo AQ-041/AQ-046/AQ-011: callers, tests and SpEL
 * callers traded places); what the question names decides instead. Only the question's last lines
 * count: a shared context block above it ("production/test source", "Profile:") would otherwise
 * switch every focus on.
 */
final class QuestionFocus {

    private static final int QUESTION_LINES = 2;

    /** "Context: ...", "Profile: ...": labelled preamble lines, not the question. */
    private static final java.util.regex.Pattern LABEL =
            java.util.regex.Pattern.compile("^[\\p{L}][\\p{L} -]{0,20}:\\s");

    /** English word stems to the fields that answer them. */
    private static final Map<List<String>, List<String>> FOCUS = new LinkedHashMap<>();

    static {
        FOCUS.put(
                List.of("test"),
                List.of("test_callers", "declaring_class_tests", "delegate_tests", "test_classes"));
        FOCUS.put(
                List.of("inject", "qualifier", "consumer"),
                List.of(
                        "produced_bean_injected_into",
                        "injected_into",
                        "injects",
                        "framework_lookups",
                        "producer_parameters",
                        "bean"));
        // A class's own ambiguous dependencies: not its consumers (Apollo AQ-045 asks for those).
        FOCUS.put(List.of("inject", "qualifier", "candidate"), List.of("ambiguous_candidates"));
        FOCUS.put(
                List.of("caller", "usage", "reference", "rename"),
                List.of(
                        "production_callers",
                        "expression_references",
                        "bean_name_expressions",
                        "test_callers"));
        // Apollo AQ-018: which dependencies a @Bean method needs, with its parameters.
        FOCUS.put(
                List.of("dependenc", "parameter", "depends-on"),
                List.of("producer_parameters", "declaring_class_injects", "ambiguous_candidates"));
        FOCUS.put(List.of("indirect", "transitive"), List.of("indirect_production"));
        FOCUS.put(
                List.of("register", "profile", "condition", "remov"),
                List.of(
                        "same_type_providers",
                        "declaring_class_conditions",
                        "produces",
                        "registration_conditions",
                        "arc_removal",
                        "candidate_conditions",
                        "registered_by"));
        FOCUS.put(
                List.of("contract", "implement", "interface"),
                List.of(
                        "direct_implementations",
                        "contract_callers",
                        "contract_beans",
                        "interface_contracts",
                        "implementations"));
        // Apollo AQ-014: what a lazy provider can return.
        FOCUS.put(
                List.of(
                        "objectprovider",
                        "objectfactory",
                        "provider<",
                        "lookup",
                        "getobject",
                        "getifavailable",
                        "getifunique",
                        "orderedstream"),
                List.of("created_by_bean_methods", "ambiguous_candidates", "contract_beans"));
        // Apollo AQ-006/AQ-013: why one candidate wins (see also the choice rule in fields()).
        FOCUS.put(
                List.of("qualifier", "primary", "parameter name"),
                List.of("spring_candidate_selection", "ambiguous_candidates"));
        FOCUS.put(List.of("bean name"), List.of("bean_name_expressions", "bean"));
        FOCUS.put(
                List.of("transaction", "propagation"),
                List.of("calls", "framework_contract", "dependency_interceptors"));
        FOCUS.put(List.of("async", "thread", "request context"), List.of("thread_hops"));
        // Apollo AQ-039: whether a value can change without a rebuild or restart.
        FOCUS.put(
                List.of("restart", "reload", "on the fly", "without rebuild"),
                List.of(
                        "programmatic_property_sources",
                        "source_precedence",
                        "application_key_phase"));
        // Apollo AQ-038: a source added in code (addFirst/addLast) decides which value wins.
        FOCUS.put(
                List.of("config", "propert", "precedence", "priority", "property source"),
                List.of(
                        "references",
                        "source_precedence",
                        "programmatic_property_sources",
                        "application_key_phase",
                        "framework_key_phases",
                        "reader_callers"));
        // Apollo AQ-045, Cryostat CQ-045: what a narrower scope breaks outside a request (see
        // also the scope rule in fields()).
        FOCUS.put(
                List.of(
                        "request scope",
                        "request-scope",
                        "requestscope",
                        "requestscoped",
                        "session scope",
                        "sessionscoped",
                        "call context",
                        "outside a request",
                        "outside the request",
                        "outside an http request"),
                List.of("entry_contexts"));
        FOCUS.put(
                List.of("scope", "lifecycle", "state", "callback"),
                List.of("instance_state", "lifecycle_callbacks", "bean", "produces"));
        // Apollo AQ-016: what X#y creates, its type, name, qualifiers and scope.
        FOCUS.put(
                List.of(
                        "produce",
                        "creates",
                        "created by",
                        "who creates",
                        "construct",
                        "instantiat"),
                List.of("produces", "created_by_bean_methods"));
    }

    /** Words of choosing; with a bean word they ask why one candidate wins. */
    private static final List<String> CHOICE =
            List.of("choose", "chosen", "choice", "pick", " wins", "prefer", "selected");

    private static final List<String> BEAN_WORDS = List.of("bean", "candidate", "spring", "inject");

    private QuestionFocus() {}

    /** The fields the question's last lines ask about; empty without a prompt. */
    static Set<String> fields(String prompt) {
        Set<String> fields = new LinkedHashSet<>();
        if (prompt == null || prompt.isBlank()) return fields;
        List<String> lines =
                prompt.lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !LABEL.matcher(line).find())
                        .toList();
        String question =
                String.join(
                                " ",
                                lines.subList(
                                        Math.max(0, lines.size() - QUESTION_LINES), lines.size()))
                        .toLowerCase(Locale.ROOT);
        FOCUS.forEach(
                (stems, answers) -> {
                    if (stems.stream().anyMatch(question::contains)) fields.addAll(answers);
                });
        // "change the scope of X to session": the two words, apart.
        if (question.contains("scope")
                && (question.contains("request") || question.contains("session")))
            fields.add("entry_contexts");
        // "which of the two beans does Spring pick": a choice about beans, not "choose the tests".
        if (CHOICE.stream().anyMatch(question::contains)
                && BEAN_WORDS.stream().anyMatch(question::contains))
            fields.addAll(List.of("spring_candidate_selection", "ambiguous_candidates"));
        return fields;
    }
}
