package org.treblereel.mcp.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.jboss.jandex.*;
import org.treblereel.mcp.model.ClassRecord;

public final class JandexScanner {

    private static final Set<DotName> BEAN_DEFINING_ANNOTATIONS = Set.of(
            DotName.createSimple("jakarta.enterprise.context.ApplicationScoped"),
            DotName.createSimple("jakarta.enterprise.context.RequestScoped"),
            DotName.createSimple("jakarta.enterprise.context.SessionScoped"),
            DotName.createSimple("jakarta.enterprise.context.Dependent"),
            DotName.createSimple("jakarta.inject.Singleton"),
            DotName.createSimple("jakarta.enterprise.inject.Model")
    );

    private JandexScanner() {}

    public record ScanResult(Index index, List<ClassRecord> classes) {}

    public static ScanResult scan(Path classesDir) {
        return scan(List.of(classesDir));
    }

    public static ScanResult scan(List<Path> classesDirs) {
        Indexer indexer = new Indexer();
        for (Path classesDir : classesDirs) {
            try (Stream<Path> files = Files.walk(classesDir)) {
                files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    try (InputStream is = Files.newInputStream(p)) {
                        indexer.index(is);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to index " + p, e);
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan " + classesDir, e);
            }
        }
        Index index = indexer.complete();
        return new ScanResult(index, extractClasses(index));
    }

    public static List<ClassRecord> extractClasses(Index index) {
        List<ClassRecord> result = new ArrayList<>();
        for (ClassInfo ci : index.getKnownClasses()) {
            result.add(new ClassRecord(
                    0,
                    ci.name().toString(),
                    classKind(ci),
                    ci.superName() != null ? ci.superName().toString() : null,
                    ci.interfaceNames().stream().map(DotName::toString).toList(),
                    null, // source_file not available from .class
                    0,
                    isBean(ci),
                    0
            ));
        }
        return result;
    }

    private static String classKind(ClassInfo ci) {
        if (ci.isAnnotation()) return "ANNOTATION";
        if (ci.isInterface()) return "INTERFACE";
        if (ci.isEnum()) return "ENUM";
        if (ci.isRecord()) return "RECORD";
        return "CLASS";
    }

    private static boolean isBean(ClassInfo ci) {
        for (DotName ann : BEAN_DEFINING_ANNOTATIONS) {
            if (ci.hasDeclaredAnnotation(ann)) return true;
        }
        return false;
    }
}
