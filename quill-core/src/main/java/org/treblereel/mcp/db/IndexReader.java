package org.treblereel.mcp.db;

import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.UsageRepository.FieldAccessView;
import org.treblereel.mcp.db.UsageRepository.InvokedMemberView;
import org.treblereel.mcp.model.*;

/**
 * Static {@link Jdbi} entry points kept for query code written before the repository split.
 * Each call opens its own handle; code that issues several reads should use
 * {@link ReadQueryContext} so they share one snapshot.
 */
public final class IndexReader {

    private IndexReader() {}

    public static List<KotlinDeclarationRecord> findKotlinDeclarations(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h -> new SymbolRepository(h).findKotlinDeclarations(classId));
    }

    public static List<ClassRecord> findAllClasses(Jdbi jdbi) {
        return jdbi.withHandle(h -> new SymbolRepository(h).findAllClasses());
    }

    public static Optional<ClassRecord> findClassByName(Jdbi jdbi, String className) {
        return jdbi.withHandle(h -> new SymbolRepository(h).findClassByName(className));
    }

    public static List<ClassRecord> findClassesByName(Jdbi jdbi, String className) {
        return jdbi.withHandle(h -> new SymbolRepository(h).findClassesByName(className));
    }

    public static List<ClassMemberRecord> findClassMembers(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h -> new SymbolRepository(h).findClassMembers(classId));
    }

    public static List<ClassMemberRecord> findJvmClassMembers(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h -> new SymbolRepository(h).findJvmClassMembers(classId));
    }

    public static Map<Integer, List<ClassMemberRecord>> findClassMembers(Jdbi jdbi, Collection<Integer> classIds) {
        return jdbi.withHandle(h -> new SymbolRepository(h).findClassMembers(classIds));
    }

    public static Map<Integer, List<ClassMemberRecord>> findJvmClassMembers(Jdbi jdbi, Collection<Integer> classIds) {
        return jdbi.withHandle(h -> new SymbolRepository(h).findJvmClassMembers(classIds));
    }

    public static List<FieldAccessView> findExactFieldUsages(Jdbi jdbi, int classId, String field, String descriptor, String accessKind, int limit, int offset) {
        return jdbi.withHandle(h -> new UsageRepository(h).findExactFieldUsages(classId, field, descriptor, accessKind, limit, offset));
    }

    public static int countExactFieldUsages(Jdbi jdbi, int classId, String field, String descriptor, String accessKind) {
        return jdbi.withHandle(h -> new UsageRepository(h).countExactFieldUsages(classId, field, descriptor, accessKind));
    }

    public static List<BeanRecord> findBeans(Jdbi jdbi, Map<String, String> filter) {
        return jdbi.withHandle(h -> new BeanRepository(h).findBeans(filter));
    }

    public static List<ExternalBeanRecord> findExternalBeans(Jdbi jdbi, Map<String, String> filter) {
        return jdbi.withHandle(h -> new BeanRepository(h).findExternalBeans(filter));
    }

    public static Map<String, String> getMetadata(Jdbi jdbi) {
        return jdbi.withHandle(h -> new IndexRepository(h).getMetadata());
    }

    public static List<GitFileStats> findHotspots(Jdbi jdbi, int limit, String since) {
        return jdbi.withHandle(h -> new GitRepository(h).findHotspots(limit, since));
    }

    public static List<GitCommitRecord> findFileHistory(Jdbi jdbi, int classId, int limit) {
        return findFileHistory(jdbi, classId, limit, 0);
    }

    public static List<GitCommitRecord> findFileHistory(Jdbi jdbi, int classId, int limit, int offset) {
        return jdbi.withHandle(h -> new GitRepository(h).findFileHistory(classId, limit, offset));
    }

    public static List<GitCommitRecord> findFileHistoryByPath(Jdbi jdbi, String filePath, int limit) {
        return findFileHistoryByPath(jdbi, filePath, limit, 0);
    }

    public static List<GitCommitRecord> findFileHistoryByPath(Jdbi jdbi, String filePath, int limit, int offset) {
        return jdbi.withHandle(h -> new GitRepository(h).findFileHistoryByPath(filePath, limit, offset));
    }

    public static List<CoChangeRecord> findCoChanges(Jdbi jdbi, int classId, int limit) {
        return jdbi.withHandle(h -> new GitRepository(h).findCoChanges(classId, limit));
    }

    public static List<CoChangeRecord> findCoChangesByPath(Jdbi jdbi, String filePath, int limit) {
        return jdbi.withHandle(h -> new GitRepository(h).findCoChangesByPath(filePath, limit));
    }

    public static List<GitCommitRecord> findRecentCommits(Jdbi jdbi, int limit) {
        return jdbi.withHandle(h -> new GitRepository(h).findRecentCommits(limit));
    }

    public static List<GitCommitFile> findCommitFiles(Jdbi jdbi, int commitId) {
        return jdbi.withHandle(h -> new GitRepository(h).findCommitFiles(commitId));
    }

    public static Map<Integer, List<GitCommitFile>> findCommitFiles(Jdbi jdbi, Collection<Integer> commitIds, int limit) {
        return jdbi.withHandle(h -> new GitRepository(h).findCommitFiles(commitIds, limit));
    }

    public static Map<Integer, List<GitCommitFile>> findCommitFiles(Jdbi jdbi, Collection<Integer> commitIds, int limit, int offset) {
        return jdbi.withHandle(h -> new GitRepository(h).findCommitFiles(commitIds, limit, offset));
    }

    public static int countCommitFiles(Jdbi jdbi, Collection<Integer> commitIds) {
        return jdbi.withHandle(h -> new GitRepository(h).countCommitFiles(commitIds));
    }

    public static Optional<GitFileStats> findFileStatsByClassId(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h -> new GitRepository(h).findFileStatsByClassId(classId));
    }

    public static Optional<GitFileStats> findFileStatsByPath(Jdbi jdbi, String filePath) {
        return jdbi.withHandle(h -> new GitRepository(h).findFileStatsByPath(filePath));
    }

    public static boolean hasGitData(Jdbi jdbi) {
        return jdbi.withHandle(h -> new GitRepository(h).hasGitData());
    }

    public static Map<Integer, List<InvokedMemberView>> findInvokedMembers(
            Jdbi jdbi, int targetClassId, Collection<Integer> callerClassIds) {
        return jdbi.withHandle(h -> new UsageRepository(h).findInvokedMembers(targetClassId, callerClassIds));
    }

    public static boolean matchesBeanType(List<String> types, String requested) {
        return BeanRepository.matchesBeanType(types, requested);
    }

    public static boolean matchesQualifier(List<String> available, String requested) {
        return BeanRepository.matchesQualifier(available, requested);
    }
}
