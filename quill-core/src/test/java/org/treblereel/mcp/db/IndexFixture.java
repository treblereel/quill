package org.treblereel.mcp.db;

import java.util.*;
import java.util.stream.IntStream;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.ConfigurationScanner;
import org.treblereel.mcp.core.KotlinSourceDebug;
import org.treblereel.mcp.model.*;

/** Test data construction; not part of Quill's runtime artifact. */
public final class IndexFixture {
    private List<ClassRecord> classes = List.of();
    private List<BeanRecord> beans = List.of();
    private List<InjectionPointRecord> injectionPoints = List.of();
    private List<DependencyRecord> dependencies = List.of();
    private Map<String,String> metadata = Map.of();
    private List<ExternalDepRecord> externalDependencies = List.of();
    private List<CdiProblem> problems = List.of();
    private List<GitFileStats> gitFileStats = List.of();
    private List<GitCommitRecord> gitCommits = List.of();
    private List<GitCommitFile> gitCommitFiles = List.of();
    private List<FileRecord> files = List.of();
    private List<ClassOccurrenceRecord> classOccurrences = List.of();
    private List<ClassAnnotationRecord> classAnnotations = List.of();
    private List<ClassMemberRecord> classMembers = List.of();
    private List<MethodCallRecord> methodCalls = List.of();
    private List<FieldAccessRecord> fieldAccesses = List.of();
    private ConfigurationScanner.Result configuration = null;
    private List<ResourceUsageRecord> resourceUsages = List.of();
    private Map<Integer,String> jvmApi = Map.of();
    private List<ExternalBeanRecord> externalBeans = List.of();
    private List<ModuleClasspathRecord> moduleClasspath = List.of();
    private List<KotlinDeclarationRecord> kotlinDeclarations = List.of();
    private Map<Integer,KotlinSourceDebug.Info> kotlinSourceDebug = Map.of();

    public IndexFixture classes(List<ClassRecord> value) { classes = value; return this; }
    public IndexFixture beans(List<BeanRecord> value) { beans = value; return this; }
    public IndexFixture injectionPoints(List<InjectionPointRecord> value) { injectionPoints = value; return this; }
    public IndexFixture dependencies(List<DependencyRecord> value) { dependencies = value; return this; }
    public IndexFixture metadata(Map<String,String> value) { metadata = value; return this; }
    public IndexFixture externalDependencies(List<ExternalDepRecord> value) { externalDependencies = value; return this; }
    public IndexFixture problems(List<CdiProblem> value) { problems = value; return this; }
    public IndexFixture gitFileStats(List<GitFileStats> value) { gitFileStats = value; return this; }
    public IndexFixture gitCommits(List<GitCommitRecord> value) { gitCommits = value; return this; }
    public IndexFixture gitCommitFiles(List<GitCommitFile> value) { gitCommitFiles = value; return this; }
    public IndexFixture files(List<FileRecord> value) { files = value; return this; }
    public IndexFixture classOccurrences(List<ClassOccurrenceRecord> value) { classOccurrences = value; return this; }
    public IndexFixture classAnnotations(List<ClassAnnotationRecord> value) { classAnnotations = value; return this; }
    public IndexFixture classMembers(List<ClassMemberRecord> value) { classMembers = value; return this; }
    public IndexFixture methodCalls(List<MethodCallRecord> value) { methodCalls = value; return this; }
    public IndexFixture fieldAccesses(List<FieldAccessRecord> value) { fieldAccesses = value; return this; }
    public IndexFixture configuration(ConfigurationScanner.Result value) { configuration = value; return this; }
    public IndexFixture resourceUsages(List<ResourceUsageRecord> value) { resourceUsages = value; return this; }
    public IndexFixture jvmApi(Map<Integer,String> value) { jvmApi = value; return this; }
    public IndexFixture externalBeans(List<ExternalBeanRecord> value) { externalBeans = value; return this; }
    public IndexFixture moduleClasspath(List<ModuleClasspathRecord> value) { moduleClasspath = value; return this; }
    public IndexFixture kotlinDeclarations(List<KotlinDeclarationRecord> value) { kotlinDeclarations = value; return this; }
    public IndexFixture kotlinSourceDebug(Map<Integer,KotlinSourceDebug.Info> value) { kotlinSourceDebug = value; return this; }

    public IndexSnapshot snapshot() {
        var numberedClasses = IntStream.range(0, classes.size())
                .mapToObj(i -> classes.get(i).id() == 0 ? classes.get(i).withId(i + 1) : classes.get(i)).toList();
        var numberedBeans = IntStream.range(0, beans.size())
                .mapToObj(i -> beans.get(i).id() == 0 ? beans.get(i).withId(i + 1) : beans.get(i)).toList();
        var numberedPoints = IntStream.range(0, injectionPoints.size())
                .mapToObj(i -> injectionPoints.get(i).id() == 0 ? injectionPoints.get(i).withId(i + 1) : injectionPoints.get(i)).toList();
        return new IndexSnapshot(numberedClasses, numberedBeans, numberedPoints,
                dependencies, metadata, externalDependencies, problems, gitFileStats, gitCommits,
                gitCommitFiles, files, classOccurrences, classAnnotations, classMembers,
                methodCalls, fieldAccesses, configuration, resourceUsages, jvmApi,
                externalBeans, moduleClasspath, kotlinDeclarations, kotlinSourceDebug);
    }

    public static void writeGitData(Jdbi db, List<GitFileStats> stats,
            List<GitCommitRecord> commits, List<GitCommitFile> files) {
        db.useTransaction(h -> {
            h.execute("DELETE FROM git_commit_files"); h.execute("DELETE FROM git_commits");
            h.execute("DELETE FROM git_file_stats");
            IndexWriter.insertFileStats(h, false, stats);
            IndexWriter.insertCommits(h, false, commits);
            IndexWriter.insertCommitFiles(h, false, files);
        });
    }

    public static void writeExternalDeps(Jdbi db, List<ExternalDepRecord> values) {
        db.useTransaction(h -> {
            h.execute("DELETE FROM class_external_deps");
            IndexWriter.insertExternalDeps(h, false, values);
        });
    }

    public static void writeProblems(Jdbi db, List<CdiProblem> values) {
        db.useTransaction(h -> {
            h.execute("DELETE FROM cdi_problems");
            IndexWriter.insertProblems(h, false, values);
        });
    }

    public static void writeModuleClasspath(Jdbi db, List<ModuleClasspathRecord> values) {
        db.useTransaction(h -> {
            h.execute("DELETE FROM module_classpath");
            IndexWriter.insertModuleClasspath(h, false, values);
        });
    }

    public static void writeKotlinSourceDebug(Jdbi db, Map<Integer,KotlinSourceDebug.Info> values) {
        db.useTransaction(h -> {
            h.execute("DELETE FROM kotlin_source_debug");
            IndexWriter.insertKotlinSourceDebug(h, false, values);
        });
    }

    public static void writeKotlinDeclarations(Jdbi db, List<KotlinDeclarationRecord> values) {
        db.useTransaction(h -> {
            h.execute("DELETE FROM kotlin_declarations");
            IndexWriter.insertKotlinDeclarations(h, false, values);
        });
    }

    public static void writeExternalBeans(Jdbi db, List<ExternalBeanRecord> values) {
        db.useTransaction(h -> {
            h.execute("DELETE FROM external_beans");
            IndexWriter.insertExternalBeans(h, false, values);
        });
    }
}
