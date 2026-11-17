package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QuestionFocusTest {

    /**
     * Apollo AQ-018: asked which dependencies a @Bean method needs and to show its parameters, the
     * budget cut producer_parameters because no stem asked for it.
     */
    @Test
    void askingForABeanMethodsDependenciesKeepsItsParameters() {
        var fields =
                QuestionFocus.fields(
                        "Context: production source.\nWhich dependencies does X#y(A,B) need to create its result? Show the @Bean method parameters, explicit depends-on and the dependencies of its declaring class.");
        assertTrue(fields.contains("producer_parameters"), fields.toString());
        assertTrue(fields.contains("declaring_class_injects"), fields.toString());
    }

    @Test
    void anUnrelatedQuestionDoesNotAskForParameters() {
        assertFalse(QuestionFocus.fields("Who calls X#y?").contains("producer_parameters"));
    }

    /**
     * Apollo AQ-016: "what does X#y create, its type, name, qualifiers and scope" is answered by
     * produces; asking qualifiers kept producer_parameters and cut produces.
     */
    @Test
    void askingWhatABeanMethodCreatesKeepsWhatItProduces() {
        var fields =
                QuestionFocus.fields(
                        "What does X#y(A,B) create, and what are the type, name, qualifiers and scope of the result? Do not mix them with the declaring class.");
        assertTrue(fields.contains("produces"), fields.toString());
    }

    /** Apollo AQ-039: a restart question asks where a value can change while running. */
    @Test
    void askingAboutChangingWithoutRestartKeepsProgrammaticSources() {
        var fields =
                QuestionFocus.fields(
                        "Can emergencyPublish.supported.envs change in a running apollo-portal without a rebuild or restart? Show the evidence for this version.");
        assertTrue(fields.contains("programmatic_property_sources"), fields.toString());
    }

    /** Apollo AQ-045 asks about consumers; candidates are the class's own dependencies. */
    @Test
    void askingAboutConsumersDoesNotAskForAmbiguousCandidates() {
        assertFalse(
                QuestionFocus.fields(
                                "What changes? Check the state, the proxy and the singleton consumers.")
                        .contains("ambiguous_candidates"));
        assertTrue(
                QuestionFocus.fields("What will be injected into the userInfoHolder parameter?")
                        .contains("ambiguous_candidates"));
    }

    /**
     * Apollo AQ-014: asked which beans an ObjectProvider can return, the budget kept consumers and
     * contract callers and cut the creating @Bean method and its candidates.
     */
    @Test
    void askingWhatALazyProviderReturnsKeepsTheCreatorAndItsCandidates() {
        var fields =
                QuestionFocus.fields(
                        "Which beans can X return through its ObjectProvider<UserService>? Trace the lookup and the conditions that make the result depend on runtime.");
        assertTrue(fields.contains("created_by_bean_methods"), fields.toString());
        assertTrue(fields.contains("ambiguous_candidates"), fields.toString());
        assertTrue(fields.contains("contract_beans"), fields.toString());
    }
}
