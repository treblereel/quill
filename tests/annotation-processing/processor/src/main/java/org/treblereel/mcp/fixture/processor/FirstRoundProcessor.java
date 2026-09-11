package org.treblereel.mcp.fixture.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;

@SupportedAnnotationTypes("org.treblereel.mcp.fixture.processor.GenerateFirstRound")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class FirstRoundProcessor extends AbstractProcessor {
    private boolean generated;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (generated || annotations.isEmpty()) return false;
        generated = true;
        try {
            JavaFileObject file = processingEnv.getFiler()
                    .createSourceFile("org.treblereel.mcp.fixture.generated.FirstGenerated");
            try (Writer writer = file.openWriter()) {
                writer.write("""
                        package org.treblereel.mcp.fixture.generated;
                        import org.treblereel.mcp.fixture.processor.GenerateSecondRound;
                        @GenerateSecondRound
                        public final class FirstGenerated {}
                        """);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return false;
    }
}
