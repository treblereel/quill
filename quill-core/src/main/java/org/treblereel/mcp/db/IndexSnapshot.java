package org.treblereel.mcp.db;

import java.util.List;
import java.util.Map;
import org.treblereel.mcp.core.ConfigurationScanner;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.CdiProblem;
import org.treblereel.mcp.model.ClassAnnotationRecord;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassOccurrenceRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.ExternalDepRecord;
import org.treblereel.mcp.model.FieldAccessRecord;
import org.treblereel.mcp.model.FileRecord;
import org.treblereel.mcp.model.GitCommitFile;
import org.treblereel.mcp.model.GitCommitRecord;
import org.treblereel.mcp.model.GitFileStats;
import org.treblereel.mcp.model.InjectionPointRecord;
import org.treblereel.mcp.model.MethodCallRecord;

/** Complete immutable logical state published as one SQLite index generation. */
public record IndexSnapshot(
        List<ClassRecord> classes,
        List<BeanRecord> beans,
        List<InjectionPointRecord> injectionPoints,
        List<DependencyRecord> dependencies,
        Map<String, String> metadata,
        List<ExternalDepRecord> externalDependencies,
        List<CdiProblem> problems,
        List<GitFileStats> gitFileStats,
        List<GitCommitRecord> gitCommits,
        List<GitCommitFile> gitCommitFiles,
        List<FileRecord> files,
        List<ClassOccurrenceRecord> classOccurrences,
        List<ClassAnnotationRecord> classAnnotations,
        List<ClassMemberRecord> classMembers,
        List<MethodCallRecord> methodCalls,
        List<FieldAccessRecord> fieldAccesses,
        ConfigurationScanner.Result configuration) {

    public IndexSnapshot {
        classes = List.copyOf(classes);
        beans = List.copyOf(beans);
        injectionPoints = List.copyOf(injectionPoints);
        dependencies = List.copyOf(dependencies);
        metadata = Map.copyOf(metadata);
        externalDependencies = List.copyOf(externalDependencies);
        problems = List.copyOf(problems);
        gitFileStats = List.copyOf(gitFileStats);
        gitCommits = List.copyOf(gitCommits);
        gitCommitFiles = List.copyOf(gitCommitFiles);
        files = List.copyOf(files);
        classOccurrences = List.copyOf(classOccurrences);
        classAnnotations = List.copyOf(classAnnotations);
        classMembers = List.copyOf(classMembers);
        methodCalls = List.copyOf(methodCalls);
        fieldAccesses = List.copyOf(fieldAccesses);
        configuration = configuration == null
                ? new ConfigurationScanner.Result(List.of(), List.of())
                : new ConfigurationScanner.Result(
                        List.copyOf(configuration.definitions()),
                        List.copyOf(configuration.usages()),
                        List.copyOf(configuration.resourceUsages()));
    }
}
