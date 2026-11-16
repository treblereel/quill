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
}
