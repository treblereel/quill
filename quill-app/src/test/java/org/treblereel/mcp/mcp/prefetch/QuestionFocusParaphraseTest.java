package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Questions written apart from the benchmarks, so the stems answer the topic, not one phrasing.
 * The stems were first taken from benchmark questions; on these paraphrases six of twelve missed.
 * Add a paraphrase here with every new stem group.
 */
class QuestionFocusParaphraseTest {

    private record Case(String field, boolean expected, String question) {}

    private static final List<Case> CASES =
            List.of(
                    new Case("entry_contexts", true, "Can I make OrderCache @RequestScoped? Who calls it from scheduled jobs or listeners?"),
                    new Case("entry_contexts", true, "If FooService becomes request scoped, which callers run without an active HTTP request?"),
                    new Case("entry_contexts", true, "We make BarService @SessionScoped: where is it called from outside a web request?"),
                    new Case("entry_contexts", true, "What breaks if we switch the scope of bean Baz to request?"),
                    new Case("entry_contexts", true, "Is it safe to use RequestScoped for TokenStore given the background workers?"),
                    new Case("entry_contexts", true, "What happens if we change the scope of AuditTrail to session?"),
                    new Case("entry_contexts", false, "What is the scope of bean Baz and why?"),
                    new Case("entry_contexts", false, "Who injects FooService and with which qualifiers?"),
                    new Case("entry_contexts", false, "Which tests cover the request handler of /orders?"),
                    new Case("spring_candidate_selection", true, "Why does Spring choose LdapUserService over the default one here?"),
                    new Case("spring_candidate_selection", true, "Of the two UserService beans, which one does Spring pick and why?"),
                    new Case("spring_candidate_selection", true, "Which DataSource wins when both beans are present?"),
                    new Case("spring_candidate_selection", false, "Choose narrow tests for this plan."),
                    new Case("created_by_bean_methods", true, "What does provider.getIfUnique() return in Holder#get()?"),
                    new Case("created_by_bean_methods", true, "Who constructs SpringHolder and with what arguments?"),
                    new Case("created_by_bean_methods", true, "Where is SpringHolder instantiated and which arguments does it get?"),
                    new Case("created_by_bean_methods", false, "Rename SpringHolder#get to fetch and update the callers."));

    @Test
    void stemsAnswerParaphrasesOutsideTheBenchmarks() {
        List<String> misses = new ArrayList<>();
        for (Case c : CASES)
            if (QuestionFocus.fields(c.question()).contains(c.field()) != c.expected())
                misses.add((c.expected() ? "missed " : "wrongly asked ") + c.field() + ": " + c.question());
        assertEquals(List.of(), misses);
    }
}
