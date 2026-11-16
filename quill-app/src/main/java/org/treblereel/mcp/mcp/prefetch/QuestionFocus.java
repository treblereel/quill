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

    /** Word stems (Russian and English) to the fields that answer them. */
    private static final Map<List<String>, List<String>> FOCUS = new LinkedHashMap<>();

    static {
        FOCUS.put(
                List.of("тест", "test"),
                List.of("test_callers", "declaring_class_tests", "delegate_tests", "test_classes"));
        FOCUS.put(
                List.of(
                        "внедр",
                        "инъекц",
                        "inject",
                        "qualifier",
                        "квалификатор",
                        "потребител",
                        "consumer"),
                List.of(
                        "produced_bean_injected_into",
                        "injected_into",
                        "injects",
                        "framework_lookups",
                        "producer_parameters",
                        "bean"));
        FOCUS.put(
                List.of(
                        "вызыва",
                        "caller",
                        "usage",
                        "использ",
                        "ссылк",
                        "reference",
                        "переимен",
                        "rename"),
                List.of(
                        "production_callers",
                        "expression_references",
                        "bean_name_expressions",
                        "test_callers"));
        // Apollo AQ-018: "какие зависимости нужны ... покажи параметры @Bean-метода".
        FOCUS.put(
                List.of("зависимост", "dependenc", "параметр", "parameter", "depends-on"),
                List.of("producer_parameters", "declaring_class_injects"));
        FOCUS.put(
                List.of("косвенн", "indirect", "транзитив", "transitive"),
                List.of("indirect_production"));
        FOCUS.put(
                List.of(
                        "регистр",
                        "register",
                        "профил",
                        "profile",
                        "услов",
                        "condition",
                        "удал",
                        "remov"),
                List.of(
                        "same_type_providers",
                        "declaring_class_conditions",
                        "produces",
                        "registration_conditions",
                        "arc_removal",
                        "candidate_conditions",
                        "registered_by"));
        FOCUS.put(
                List.of("контракт", "contract", "реализац", "implement", "интерфейс", "interface"),
                List.of(
                        "direct_implementations",
                        "contract_callers",
                        "contract_beans",
                        "interface_contracts",
                        "implementations"));
        FOCUS.put(
                List.of("имя bean", "имени bean", "bean name", "имя бина", "имени бина"),
                List.of("bean_name_expressions", "bean"));
        FOCUS.put(
                List.of("транзакц", "transaction", "propagation"),
                List.of("calls", "framework_contract", "dependency_interceptors"));
        FOCUS.put(
                List.of(
                        "асинхрон",
                        "async",
                        "поток",
                        "thread",
                        "контекст запрос",
                        "request context"),
                List.of("thread_hops"));
        FOCUS.put(
                List.of("настройк", "конфигурац", "config", "propert", "ключ"),
                List.of(
                        "references",
                        "source_precedence",
                        "application_key_phase",
                        "framework_key_phases"));
        FOCUS.put(
                List.of("scope", "lifecycle", "жизненн", "состояни", "callback"),
                List.of("instance_state", "lifecycle_callbacks", "bean", "produces"));
        // Apollo AQ-016: "что создаёт X#y, какие type, имя, qualifiers и scope".
        FOCUS.put(List.of("создаёт", "создает", "produce", "creates"), List.of("produces"));
    }

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
        return fields;
    }
}
