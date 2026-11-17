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
                        "Context: production source.\nКакие зависимости нужны X#y(A,B), чтобы создать результат? Покажи параметры @Bean-метода, явные depends-on и зависимости его declaring class.");
        assertTrue(fields.contains("producer_parameters"), fields.toString());
        assertTrue(fields.contains("declaring_class_injects"), fields.toString());
    }

    @Test
    void anUnrelatedQuestionDoesNotAskForParameters() {
        assertFalse(QuestionFocus.fields("Кто вызывает X#y?").contains("producer_parameters"));
    }

    /**
     * Apollo AQ-016: "what does X#y create, its type, name, qualifiers and scope" is answered by
     * produces; asking qualifiers kept producer_parameters and cut produces.
     */
    @Test
    void askingWhatABeanMethodCreatesKeepsWhatItProduces() {
        var fields =
                QuestionFocus.fields(
                        "Что создаёт X#y(A,B), какие у результата type, имя, qualifiers и scope? Не смешивай их со свойствами declaring class.");
        assertTrue(fields.contains("produces"), fields.toString());
    }

    /** Apollo AQ-039: a restart question asks where a value can change while running. */
    @Test
    void askingAboutChangingWithoutRestartKeepsProgrammaticSources() {
        var fields =
                QuestionFocus.fields(
                        "Можно ли изменить emergencyPublish.supported.envs у работающего apollo-portal без пересборки и перезапуска? Покажи доказательства поведения для этой версии.");
        assertTrue(fields.contains("programmatic_property_sources"), fields.toString());
    }

    /** Apollo AQ-045 asks about consumers; candidates are the class's own dependencies. */
    @Test
    void askingAboutConsumersDoesNotAskForAmbiguousCandidates() {
        assertFalse(
                QuestionFocus.fields(
                                "Что изменится? Проверь состояние, proxy, потребителей-singletons.")
                        .contains("ambiguous_candidates"));
        assertTrue(
                QuestionFocus.fields("Что будет внедрено в параметр userInfoHolder?")
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
                        "Какие beans может вернуть X через свой ObjectProvider<UserService>? Проследи lookup и условия, из-за которых результат зависит от runtime.");
        assertTrue(fields.contains("created_by_bean_methods"), fields.toString());
        assertTrue(fields.contains("ambiguous_candidates"), fields.toString());
        assertTrue(fields.contains("contract_beans"), fields.toString());
    }
}
