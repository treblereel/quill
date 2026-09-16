package org.treblereel.mcp.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import java.util.*;
import java.util.function.IntConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.treblereel.mcp.core.TokenCounter;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.CdiProblem;
import org.treblereel.mcp.model.*;

public final class QuillToolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_GRAPH_NODES = 200;
    private static final int MAX_RECENT_CHANGE_FILES = 200;
    private static final int MAX_PROBLEM_DETAILS = 50;

    String getBeans(Jdbi jdbi, String className, String scope, String kind, String profile, String qualifier) {
        return getBeans(jdbi, className, scope, kind, profile, qualifier, 50);
    }

    String searchClasses(Jdbi jdbi, String pattern, int limit) {
        List<ClassRecord> classes = IndexReader.searchClasses(jdbi, pattern, limit + 1);
        int total = classes.size() > limit ? classes.size() : classes.size();
        boolean hasMore = classes.size() > limit;
        List<ClassRecord> limited = hasMore ? classes.subList(0, limit) : classes;
        Map<Integer, BeanRecord> beansByClass = IndexReader.findBeansByClassIds(
                jdbi, limited.stream().map(ClassRecord::id).toList());

        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("classes");
        int naiveTokens = 0;
        for (ClassRecord c : limited) {
            ObjectNode node = arr.addObject();
            node.put("class", c.className());
            node.put("source", c.sourceFile() + ":" + c.sourceLine());
            node.put("origin", c.origin());
            node.put("lifecycle", c.lifecycle());
            node.put("is_bean", c.isBean());
            BeanRecord bean = beansByClass.get(c.id());
            if (bean != null) node.put("scope", bean.scope());
            node.put("source_tokens", c.sourceTokens());
            naiveTokens += c.sourceTokens();
        }
        root.put("showing", limited.size());
        if (hasMore) {
            root.put("total", ">" + limit + " (use a more specific pattern)");
        } else {
            root.put("total", limited.size());
        }
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind, String profile, String qualifier, int limit) {
        Map<String, String> filter = new HashMap<>();
        if (className != null) filter.put("class_name", className);
        if (scope != null) filter.put("scope", scope);
        if (kind != null) filter.put("kind", kind);
        if (profile != null) filter.put("profile", profile);
        if (qualifier != null) filter.put("qualifier", qualifier);

        List<BeanRecord> beans = IndexReader.findBeans(jdbi, filter.isEmpty() ? null : filter);
        int total = beans.size();
        List<BeanRecord> limited = beans.size() > limit ? beans.subList(0, limit) : beans;
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(
                jdbi, limited.stream().map(BeanRecord::classId).toList());

        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("beans");
        int[] naiveTokensWrapper = {0};

        for (BeanRecord b : limited) {
            ObjectNode node = arr.addObject();
            ClassRecord beanClass = classesById.get(b.classId());
            String fqcn = beanClass != null ? beanClass.className() : "unknown";
            node.put("class", fqcn);
            if (b.memberName() != null) {
                node.put("member", b.memberName());
            }
            if (isProducer(b.kind()) && b.beanTypes() != null && !b.beanTypes().isEmpty()) {
                node.put("produced_type", b.beanTypes().get(0));
            }
            node.put("kind", b.kind());
            node.put("scope", b.scope());
            node.set("qualifiers", JSON.valueToTree(b.qualifiers()));
            node.set("bean_types", JSON.valueToTree(b.beanTypes()));
            node.set("profiles", JSON.valueToTree(b.profiles()));
            if (beanClass != null) {
                node.put("source", beanClass.sourceFile() + ":" + beanClass.sourceLine());
                naiveTokensWrapper[0] += beanClass.sourceTokens();
            }
        }
        root.put("showing", limited.size());
        root.put("total", total);

        appendMeta(root, jdbi, naiveTokensWrapper[0]);
        return root.toString();
    }

    String getDependencies(Jdbi jdbi, String target, String direction, int depth) {
        var lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord cls = lookup.cls();

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("is_bean", cls.isBean());
        root.put("origin", cls.origin());
        root.put("lifecycle", cls.lifecycle());

        ObjectNode metrics = root.putObject("metrics");
        metrics.put("fan_in", IndexReader.countDependents(jdbi, cls.id()));
        metrics.put("incoming_edges", IndexReader.countDependencyEdges(jdbi, cls.id(), true));
        metrics.put("fan_out", IndexReader.countDependencies(jdbi, cls.id()));
        metrics.put("outgoing_edges", IndexReader.countDependencyEdges(jdbi, cls.id(), false));
        writeDependencyBreakdown(metrics.putObject("fan_in_breakdown"),
                IndexReader.dependencyBreakdown(jdbi, cls.id(), true));
        writeDependencyBreakdown(metrics.putObject("fan_out_breakdown"),
                IndexReader.dependencyBreakdown(jdbi, cls.id(), false));

        if (cls.isBean()) {
            IndexReader.findBeanByClassId(jdbi, cls.id()).ifPresent(b -> {
                root.put("scope", b.scope());
            });
        }

        int[] naiveTokens = {cls.sourceTokens()};
        Set<Integer> visited = new HashSet<>();
        visited.add(cls.id());
        GraphBudget budget = new GraphBudget(MAX_GRAPH_NODES);

        expandDependencies(jdbi, cls.id(), direction, depth, root, visited,
                t -> naiveTokens[0] += t, budget);
        if (budget.truncated) {
            root.put("truncated", true);
            root.put("node_limit", MAX_GRAPH_NODES);
        }

        appendMeta(root, jdbi, naiveTokens[0]);
        return root.toString();
    }

    private void expandDependencies(Jdbi jdbi, int classId, String direction, int depth,
                                     ObjectNode node, Set<Integer> visited, IntConsumer tokenAccum,
                                     GraphBudget budget) {
        List<DependencyRecord> deps = IndexReader.findDependencies(jdbi, classId, direction);

        ArrayNode dependsOn = node.putArray("depends_on");
        ArrayNode dependedBy = node.putArray("depended_by");

        for (DependencyRecord d : deps) {
            if (d.fromClassId() == classId) {
                if (!budget.claim()) break;
                IndexReader.findClassById(jdbi, d.toClassId()).ifPresent(c -> {
                    ObjectNode child = dependsOn.addObject();
                    child.put("class", c.className());
                    child.put("kind", d.kind());
                    child.put("occurrences", d.occurrenceCount());
                    tokenAccum.accept(c.sourceTokens());
                    if (depth > 1 && visited.add(c.id())) {
                        expandDependencies(jdbi, c.id(), direction, depth - 1, child,
                                visited, tokenAccum, budget);
                    }
                });
            }
            if (d.toClassId() == classId) {
                if (!budget.claim()) break;
                IndexReader.findClassById(jdbi, d.fromClassId()).ifPresent(c -> {
                    ObjectNode child = dependedBy.addObject();
                    child.put("class", c.className());
                    child.put("kind", d.kind());
                    child.put("occurrences", d.occurrenceCount());
                    tokenAccum.accept(c.sourceTokens());
                    if (depth > 1 && visited.add(c.id())) {
                        expandDependencies(jdbi, c.id(), direction, depth - 1, child,
                                visited, tokenAccum, budget);
                    }
                });
            }
        }
    }

    String getInjectionPoints(Jdbi jdbi, String target) {
        var lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord cls = lookup.cls();

        var beanOpt = IndexReader.findBeanByClassId(jdbi, cls.id());
        if (beanOpt.isEmpty()) return errorResponse("Not a bean: " + target);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());

        List<InjectionPointRecord> ips = IndexReader.findInjectionPoints(jdbi, beanOpt.get().id());
        Map<Integer, BeanRecord> resolvedBeans = IndexReader.findBeansByIds(jdbi, ips.stream()
                .map(InjectionPointRecord::resolvedBeanId).filter(Objects::nonNull).toList());
        Map<Integer, ClassRecord> resolvedClasses = IndexReader.findClassesByIds(jdbi, resolvedBeans.values()
                .stream().map(BeanRecord::classId).toList());

        ArrayNode arr = root.putArray("injection_points");
        ArrayNode unsatisfied = root.putArray("unsatisfied");
        ArrayNode ambiguous = root.putArray("ambiguous");

        for (InjectionPointRecord ip : ips) {
            ObjectNode node = arr.addObject();
            node.put("kind", ip.kind());
            node.put("field", ip.fieldName());
            node.put("required_type", ip.targetType());
            node.set("qualifiers", JSON.valueToTree(ip.qualifiers()));

            if (ip.isAmbiguous()) {
                node.put("resolved_to", (String) null);
                node.put("resolution", "ambiguous");
                ambiguous.add(ip.fieldName());
            } else if (ip.resolvedBeanId() != null) {
                BeanRecord resolved = resolvedBeans.get(ip.resolvedBeanId());
                if (resolved != null) {
                    ClassRecord resolvedClass = resolvedClasses.get(resolved.classId());
                    if (resolvedClass != null) node.put("resolved_to", resolvedClass.className());
                    if (resolved.memberName() != null) {
                        node.put("resolved_member", resolved.memberName());
                    }
                    if (isProducer(resolved.kind()) && resolved.beanTypes() != null
                            && !resolved.beanTypes().isEmpty()) {
                        node.put("resolved_produced_type", resolved.beanTypes().get(0));
                    }
                }
                node.put("resolution", "unique");
            } else {
                node.put("resolved_to", (String) null);
                node.put("resolution", "unsatisfied");
                unsatisfied.add(ip.fieldName());
            }
        }

        appendMeta(root, jdbi, cls.sourceTokens());
        return root.toString();
    }

    private static final String NO_GIT_MESSAGE = "No git data available. "
            + "Initialize a git repository and re-run 'quill init' to enable git intelligence: "
            + "git init && git add -A && git commit -m 'initial'";

    String getHotspots(Jdbi jdbi, int limit, String since) {
        return getHotspots(jdbi, limit, since, false);
    }

    String getHotspots(Jdbi jdbi, int limit, String since, boolean includeHistorical) {
        Map<String, String> metadata = IndexReader.getMetadata(jdbi);
        WorktreeInspector.Snapshot worktree = worktreeSnapshot(metadata);
        boolean hasGit = IndexReader.hasGitData(jdbi);
        if (!hasGit && !worktree.dirty()) return errorResponse(NO_GIT_MESSAGE);

        HotspotSelection selection = hasGit
                ? selectHotspots(jdbi, worktree, limit, since, includeHistorical)
                : new HotspotSelection(List.of(), 0);
        List<GitFileStats> hotspots = selection.shown();
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(jdbi, hotspots.stream()
                .map(GitFileStats::classId).filter(Objects::nonNull).toList());
        Map<String, String> worktreeStatuses = worktree.statusesByRepositoryPath();
        ObjectNode root = JSON.createObjectNode();
        ArrayNode changes = root.putArray("worktree_changes");
        for (WorktreeInspector.Change change : worktree.changes()) {
            ObjectNode node = changes.addObject();
            node.put("file", change.repositoryPath());
            node.put("project_file", change.projectPath());
            node.put("status", change.status());
        }
        ArrayNode arr = root.putArray("hotspots");

        for (GitFileStats s : hotspots) {
            ObjectNode node = arr.addObject();
            node.put("file", s.filePath());
            String worktreeStatus = worktreeStatuses.get(s.filePath());
            if (worktreeStatus != null) node.put("worktree_status", worktreeStatus);
            boolean exists = worktree.repositoryRoot() == null
                    || Files.exists(worktree.repositoryRoot().resolve(s.filePath()).normalize());
            node.put("lifecycle", exists ? "current" : "historical");
            if (s.classId() != null) {
                Optional.ofNullable(classesById.get(s.classId())).ifPresent(c -> {
                    node.put("class", c.className());
                    node.put("is_bean", c.isBean());
                });
            }
            node.put("commit_count", s.commitCount());
            node.put("distinct_authors", s.distinctAuthors());
            node.put("last_modified", s.lastModified());
            node.put("last_author", s.lastAuthor());
        }
        root.put("showing", hotspots.size());
        root.put("total", selection.total());
        root.put("truncated", selection.total() > hotspots.size());
        root.put("worktree_total", worktree.changes().size());
        appendMeta(root, jdbi, 0);
        return root.toString();
    }

    private WorktreeInspector.Snapshot worktreeSnapshot(Map<String, String> metadata) {
        String projectRoot = metadata.get("project_root");
        if (projectRoot == null) return WorktreeInspector.Snapshot.empty();
        try {
            return WorktreeSnapshotCache.shared().get(Path.of(projectRoot));
        } catch (RuntimeException e) {
            return WorktreeInspector.Snapshot.empty();
        }
    }

    private record HotspotSelection(List<GitFileStats> shown, int total) {}

    private HotspotSelection selectHotspots(Jdbi jdbi, WorktreeInspector.Snapshot worktree,
            int limit, String since, boolean includeHistorical) {
        List<GitFileStats> matching = IndexReader.findHotspots(jdbi, Integer.MAX_VALUE, since);
        if (!includeHistorical) {
            matching = matching.stream()
                    .filter(stats -> isCurrentHotspot(jdbi, worktree, stats.filePath()))
                    .toList();
        }
        int total = matching.size();
        List<GitFileStats> shown = total > limit ? matching.subList(0, limit) : matching;
        return new HotspotSelection(shown, total);
    }

    private boolean isCurrentHotspot(Jdbi jdbi, WorktreeInspector.Snapshot worktree,
            String repositoryPath) {
        if (worktree.repositoryRoot() != null) {
            Path candidate = worktree.repositoryRoot().resolve(repositoryPath).normalize();
            return candidate.startsWith(worktree.repositoryRoot()) && Files.exists(candidate);
        }
        return IndexReader.findFileByPath(jdbi, repositoryPath)
                .map(file -> file.lifecycle().equals("current"))
                .orElse(true);
    }

    String getFileHistory(Jdbi jdbi, String target, int limit) {
        if (!IndexReader.hasGitData(jdbi)) return errorResponse(NO_GIT_MESSAGE);

        var lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) {
            String filePath = resolveGitPath(jdbi, target);
            List<GitCommitRecord> commits = IndexReader.findFileHistoryByPath(jdbi, filePath, limit);
            if (commits.isEmpty()) return classLookupError(jdbi, lookup, target);
            ObjectNode root = JSON.createObjectNode();
            root.put("target", filePath);
            root.put("file", filePath);
            root.put("lifecycle", currentFileExists(jdbi, filePath) ? "current" : "historical");
            appendCommits(root, commits);
            IndexReader.findFileStatsByPath(jdbi, filePath)
                    .ifPresent(stats -> root.put("total_commits", stats.commitCount()));
            appendMeta(root, jdbi, 0);
            return root.toString();
        }
        ClassRecord cls = lookup.cls();

        List<GitCommitRecord> commits = IndexReader.findFileHistory(jdbi, cls.id(), limit);
        var statsOpt = IndexReader.findFileStatsByClassId(jdbi, cls.id());

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("file", cls.sourceFile());

        appendCommits(root, commits);
        statsOpt.ifPresent(s -> root.put("total_commits", s.commitCount()));
        appendMeta(root, jdbi, cls.sourceTokens());
        return root.toString();
    }

    String getCoChanges(Jdbi jdbi, String target, int limit) {
        if (!IndexReader.hasGitData(jdbi)) return errorResponse(NO_GIT_MESSAGE);

        var lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) {
            String filePath = resolveGitPath(jdbi, target);
            List<CoChangeRecord> coChanges = IndexReader.findCoChangesByPath(jdbi, filePath, limit);
            if (coChanges.isEmpty() && IndexReader.findFileStatsByPath(jdbi, filePath).isEmpty()) {
                return classLookupError(jdbi, lookup, target);
            }
            int targetCommitCount = IndexReader.findFileStatsByPath(jdbi, filePath)
                    .map(GitFileStats::commitCount).orElse(1);
            return coChangeResponse(jdbi, filePath, coChanges, targetCommitCount);
        }
        ClassRecord cls = lookup.cls();

        var statsOpt = IndexReader.findFileStatsByClassId(jdbi, cls.id());
        int targetCommitCount = statsOpt.map(GitFileStats::commitCount).orElse(1);

        List<CoChangeRecord> coChanges = IndexReader.findCoChanges(jdbi, cls.id(), limit);
        return coChangeResponse(jdbi, cls.className(), coChanges, targetCommitCount);
    }

    private String coChangeResponse(Jdbi jdbi, String target,
            List<CoChangeRecord> coChanges, int targetCommitCount) {
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(jdbi, coChanges.stream()
                .map(CoChangeRecord::classId).filter(Objects::nonNull).toList());

        ObjectNode root = JSON.createObjectNode();
        root.put("target", target);
        ArrayNode arr = root.putArray("co_changes");
        for (CoChangeRecord co : coChanges) {
            ObjectNode node = arr.addObject();
            node.put("file", co.filePath());
            if (co.classId() != null) {
                Optional.ofNullable(classesById.get(co.classId())).ifPresent(c -> {
                    node.put("class", c.className());
                });
            }
            node.put("co_change_count", co.coChangeCount());
            double ratio = (double) co.coChangeCount() / targetCommitCount;
            node.put("coupling_ratio", Math.round(ratio * 100.0) / 100.0);
        }
        appendMeta(root, jdbi, 0);
        return root.toString();
    }

    private void appendCommits(ObjectNode root, List<GitCommitRecord> commits) {
        ArrayNode arr = root.putArray("commits");
        for (GitCommitRecord commit : commits) {
            ObjectNode node = arr.addObject();
            node.put("hash", commit.shortHash());
            node.put("author", commit.author());
            node.put("date", commit.committedAt());
            node.put("message", commit.message());
        }
    }

    private String resolveGitPath(Jdbi jdbi, String target) {
        return IndexReader.findFileByPath(jdbi, target)
                .map(FileRecord::repositoryPath)
                .orElse(target.replace('\\', '/'));
    }

    private boolean currentFileExists(Jdbi jdbi, String repositoryPath) {
        WorktreeInspector.Snapshot snapshot = worktreeSnapshot(IndexReader.getMetadata(jdbi));
        if (snapshot.repositoryRoot() != null) {
            return Files.exists(snapshot.repositoryRoot().resolve(repositoryPath).normalize());
        }
        return IndexReader.findFileByPath(jdbi, repositoryPath)
                .map(file -> file.lifecycle().equals("current"))
                .orElse(false);
    }

    String getRecentChanges(Jdbi jdbi, int commitCount) {
        if (!IndexReader.hasGitData(jdbi)) return errorResponse(NO_GIT_MESSAGE);

        List<GitCommitRecord> commits = IndexReader.findRecentCommits(jdbi, commitCount);
        Map<Integer, List<GitCommitFile>> filesByCommit = IndexReader.findCommitFiles(jdbi,
                commits.stream().map(GitCommitRecord::id).toList(), MAX_RECENT_CHANGE_FILES + 1);
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(jdbi, filesByCommit.values()
                .stream().flatMap(Collection::stream).map(GitCommitFile::classId)
                .filter(Objects::nonNull).toList());
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("recent_changes");
        int fileCount = 0;
        boolean truncated = false;

        recentCommits:
        for (GitCommitRecord c : commits) {
            ObjectNode node = arr.addObject();
            node.put("commit", c.shortHash());
            node.put("author", c.author());
            node.put("date", c.committedAt());
            node.put("message", c.message());

            List<GitCommitFile> files = filesByCommit.getOrDefault(c.id(), List.of());
            ArrayNode filesArr = node.putArray("files");
            for (GitCommitFile f : files) {
                if (fileCount >= MAX_RECENT_CHANGE_FILES) {
                    truncated = true;
                    break recentCommits;
                }
                ObjectNode fNode = filesArr.addObject();
                fileCount++;
                fNode.put("file", f.filePath());
                fNode.put("change_type", f.changeType());
                if (f.classId() != null) {
                    Optional.ofNullable(classesById.get(f.classId())).ifPresent(cl -> {
                        fNode.put("class", cl.className());
                        fNode.put("is_bean", cl.isBean());
                    });
                }
            }
        }
        if (truncated) {
            root.put("truncated", true);
            root.put("file_limit", MAX_RECENT_CHANGE_FILES);
        }
        appendMeta(root, jdbi, 0);
        return root.toString();
    }

    String getOverview(Jdbi jdbi) {
        Map<String, String> meta = IndexReader.getMetadata(jdbi);
        ObjectNode root = JSON.createObjectNode();

        ObjectNode project = root.putObject("project");
        int classCount = IndexReader.countClasses(jdbi);
        int beanCount = IndexReader.countBeans(jdbi);
        int totalTokens = IndexReader.sumSourceTokens(jdbi);
        project.put("framework", meta.getOrDefault("framework", "CDI"));
        project.put("classes", classCount);
        project.put("beans", beanCount);
        project.put("total_source_tokens", totalTokens);
        project.put("index_id", meta.get("index_id"));
        project.put("indexed_at", meta.getOrDefault("indexed_at", "unknown"));
        project.put("last_commit", meta.getOrDefault("last_commit", "unknown"));
        project.put("dependency_index", meta.getOrDefault("dependency_index", "unknown"));
        if (meta.containsKey("dependency_index_detail")) {
            project.put("dependency_index_detail", meta.get("dependency_index_detail"));
        }

        ObjectNode scopeNode = root.putObject("beans_by_scope");
        IndexReader.countBeansByScope(jdbi).forEach(scopeNode::put);

        ObjectNode kindNode = root.putObject("beans_by_kind");
        IndexReader.countBeansByKind(jdbi).forEach(kindNode::put);

        ArrayNode hubs = root.putArray("architecture_hubs");
        List<Map.Entry<Integer, Integer>> hubEntries =
                IndexReader.findMostDependedOn(jdbi, 5);
        Map<Integer, ClassRecord> hubClasses = IndexReader.findClassesByIds(jdbi,
                hubEntries.stream().map(Map.Entry::getKey).toList());
        for (var entry : hubEntries) {
            Optional.ofNullable(hubClasses.get(entry.getKey())).ifPresent(c -> {
                ObjectNode hub = hubs.addObject();
                hub.put("class", c.className());
                hub.put("dependents", entry.getValue());
                hub.put("is_bean", c.isBean());
            });
        }

        ObjectNode problems = root.putObject("problems");
        List<InjectionPointRecord> unsatisfied = IndexReader.findUnsatisfiedInjectionPoints(jdbi);
        List<InjectionPointRecord> ambiguous = IndexReader.findAmbiguousInjectionPoints(jdbi);
        List<InjectionPointRecord> problemSample = new ArrayList<>();
        problemSample.addAll(unsatisfied.stream().limit(10).toList());
        problemSample.addAll(ambiguous.stream().limit(10).toList());
        Map<Integer, BeanRecord> problemBeans = IndexReader.findBeansByIds(jdbi,
                problemSample.stream().map(InjectionPointRecord::beanId).toList());
        Map<Integer, ClassRecord> problemClasses = IndexReader.findClassesByIds(jdbi,
                problemBeans.values().stream().map(BeanRecord::classId).toList());
        problems.put("unsatisfied_count", unsatisfied.size());
        if (!unsatisfied.isEmpty()) {
            List<InjectionPointRecord> unsatLimited = unsatisfied.size() > 10
                    ? unsatisfied.subList(0, 10) : unsatisfied;
            ArrayNode unsatArr = problems.putArray("unsatisfied_injection_points_sample");
            for (InjectionPointRecord ip : unsatLimited) {
                ObjectNode node = unsatArr.addObject();
                BeanRecord bean = problemBeans.get(ip.beanId());
                if (bean != null && problemClasses.containsKey(bean.classId())) {
                    node.put("bean", problemClasses.get(bean.classId()).className());
                }
                node.put("field", ip.fieldName());
                node.put("type", ip.targetType());
            }
        }
        problems.put("ambiguous_count", ambiguous.size());
        if (!ambiguous.isEmpty()) {
            List<InjectionPointRecord> ambLimited = ambiguous.size() > 10
                    ? ambiguous.subList(0, 10) : ambiguous;
            ArrayNode ambArr = problems.putArray("ambiguous_injection_points_sample");
            for (InjectionPointRecord ip : ambLimited) {
                ObjectNode node = ambArr.addObject();
                BeanRecord bean = problemBeans.get(ip.beanId());
                if (bean != null && problemClasses.containsKey(bean.classId())) {
                    node.put("bean", problemClasses.get(bean.classId()).className());
                }
                node.put("field", ip.fieldName());
                node.put("type", ip.targetType());
            }
        }

        List<CdiProblem> cdiProblems = IndexReader.findCdiProblems(jdbi);
        if (!cdiProblems.isEmpty()) {
            problems.put("cdi_spec_violation_count", cdiProblems.size());
            ArrayNode cdiArr = problems.putArray("cdi_spec_violations");
            for (CdiProblem p : cdiProblems.stream().limit(MAX_PROBLEM_DETAILS).toList()) {
                ObjectNode node = cdiArr.addObject();
                node.put("class", p.className());
                node.put("type", p.problemType());
                node.put("message", p.message());
            }
            if (cdiProblems.size() > MAX_PROBLEM_DETAILS) {
                problems.put("cdi_spec_violations_truncated", true);
            }
        }

        if (IndexReader.hasExternalDeps(jdbi)) {
            ArrayNode libsArr = root.putArray("top_libraries");
            for (var entry : IndexReader.findExternalDepsByLibrary(jdbi, 5)) {
                ObjectNode lib = libsArr.addObject();
                lib.put("package", entry.getKey());
                lib.put("used_by_classes", entry.getValue());
            }
        }

        if (IndexReader.hasGitData(jdbi)) {
            ObjectNode gitSummary = root.putObject("git_summary");
            gitSummary.put("total_commits_indexed", IndexReader.countCommits(jdbi));
            ArrayNode hotspotsArr = gitSummary.putArray("top_hotspots");
            WorktreeInspector.Snapshot worktree = worktreeSnapshot(meta);
            for (GitFileStats s : selectHotspots(jdbi, worktree, 3, null, false).shown()) {
                ObjectNode h = hotspotsArr.addObject();
                h.put("file", s.filePath());
                h.put("commit_count", s.commitCount());
            }
        } else {
            root.putNull("git_summary");
        }

        appendMeta(root, jdbi, totalTokens);
        return root.toString();
    }

    String getRisk(Jdbi jdbi, String target) {
        var lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) {
            if ("Class not found".equals(lookup.error())) {
                Optional<FileRiskTarget> file = resolveFileRiskTarget(jdbi, target);
                if (file.isPresent()) return getFileRisk(jdbi, file.get());
            }
            return classLookupError(jdbi, lookup, target);
        }
        return getClassRisk(jdbi, lookup.cls());
    }

    private String getClassRisk(Jdbi jdbi, ClassRecord cls) {
        int fanIn = IndexReader.countDependents(jdbi, cls.id());
        int fanOut = IndexReader.countDependencies(jdbi, cls.id());
        int incomingEdges = IndexReader.countDependencyEdges(jdbi, cls.id(), true);
        int outgoingEdges = IndexReader.countDependencyEdges(jdbi, cls.id(), false);

        boolean hasGit = IndexReader.hasGitData(jdbi);
        var statsOpt = hasGit ? IndexReader.findFileStatsByClassId(jdbi, cls.id()) : Optional.<GitFileStats>empty();
        int churn = statsOpt.map(GitFileStats::commitCount).orElse(0);
        int authors = statsOpt.map(GitFileStats::distinctAuthors).orElse(0);
        int coChangeCount = 0;
        if (hasGit) {
            coChangeCount = IndexReader.findCoChanges(jdbi, cls.id(), 100).size();
        }

        double fanInScore = scaleScore(fanIn, 0, 3, 8, 15);
        double fanOutScore = scaleScore(fanOut, 0, 3, 5, 8);
        double churnScore = hasGit ? scaleScore(churn, 2, 10, 30, 50) : 0;
        double busFactorScore = hasGit ? busFactorScore(authors) : 0;
        double couplingScore = hasGit ? scaleScore(coChangeCount, 0, 3, 5, 8) : 0;

        double score = fanInScore * 0.30
                + fanOutScore * 0.10
                + churnScore * 0.25
                + busFactorScore * 0.20
                + couplingScore * 0.15;
        score = Math.round(score * 10.0) / 10.0;

        String level = riskLevel(score);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("target_type", "class");
        if (cls.sourceFile() != null) root.put("file", cls.sourceFile());
        root.put("risk_score", score);
        root.put("risk_level", level);

        ObjectNode signals = root.putObject("signals");
        addSignal(signals, "fan_in", fanIn, fanInScore, 0.30,
                fanIn + " unique classes depend on this");
        addSignal(signals, "fan_out", fanOut, fanOutScore, 0.10,
                "depends on " + fanOut + " unique classes");
        ObjectNode fanInNode = (ObjectNode) signals.get("fan_in");
        fanInNode.put("edges", incomingEdges);
        appendDependencyBreakdown(fanInNode, IndexReader.dependencyBreakdown(jdbi, cls.id(), true));
        ObjectNode fanOutNode = (ObjectNode) signals.get("fan_out");
        fanOutNode.put("edges", outgoingEdges);
        appendDependencyBreakdown(fanOutNode, IndexReader.dependencyBreakdown(jdbi, cls.id(), false));
        if (hasGit) {
            addSignal(signals, "git_churn", churn, churnScore, 0.25,
                    churn + " commits — " + (churn >= 30 ? "high" : churn >= 10 ? "moderate" : "low") + " change frequency");
            addSignal(signals, "bus_factor", authors, busFactorScore, 0.20,
                    authors <= 1 ? "only 1 author — single point of knowledge"
                            : authors + " authors");
            addSignal(signals, "coupling", coChangeCount, couplingScore, 0.15,
                    coChangeCount + " files frequently co-change");
        } else {
            ObjectNode gitNote = signals.putObject("git");
            gitNote.put("note", "Git data unavailable — git signals excluded from score. Run 'quill init' in a git repository.");
        }

        root.put("recommendation", buildRecommendation(cls, fanIn, churn, authors, level, hasGit));

        appendMeta(root, jdbi, cls.sourceTokens());
        return root.toString();
    }

    private record FileRiskTarget(String projectPath, String repositoryPath, String kind,
            String origin, String lifecycle, String worktreeStatus) {}

    private record FileCriticality(int score, String note) {}

    private Optional<FileRiskTarget> resolveFileRiskTarget(Jdbi jdbi, String target) {
        if (target == null || target.isBlank()) return Optional.empty();
        String normalized = target.strip().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        Map<String, String> metadata = IndexReader.getMetadata(jdbi);
        WorktreeInspector.Snapshot worktree = worktreeSnapshot(metadata);

        String projectPath = normalized;
        String repositoryPath = normalized;
        try {
            Path supplied = Path.of(target).toAbsolutePath().normalize();
            if (Path.of(target).isAbsolute()) {
                String projectRootValue = metadata.get("project_root");
                if (projectRootValue != null) {
                    Path projectRoot = Path.of(projectRootValue).toAbsolutePath().normalize();
                    if (supplied.startsWith(projectRoot)) {
                        projectPath = normalizePath(projectRoot.relativize(supplied));
                    }
                }
                if (worktree.repositoryRoot() != null && supplied.startsWith(worktree.repositoryRoot())) {
                    repositoryPath = normalizePath(worktree.repositoryRoot().relativize(supplied));
                }
            }
        } catch (RuntimeException ignored) {
            // The exact database/worktree lookup below still handles portable path strings.
        }

        Optional<FileRecord> indexed = IndexReader.findFileByPath(jdbi, projectPath);
        if (indexed.isEmpty() && !repositoryPath.equals(projectPath)) {
            indexed = IndexReader.findFileByPath(jdbi, repositoryPath);
        }
        if (indexed.isPresent()) {
            FileRecord file = indexed.get();
            String status = worktree.statusesByRepositoryPath().get(file.repositoryPath());
            String lifecycle = status != null && status.equals("deleted")
                    ? "deleted" : liveLifecycle(worktree, file.repositoryPath(), file.lifecycle());
            String inferredKind = fileKind(file.projectPath());
            return Optional.of(new FileRiskTarget(file.projectPath(), file.repositoryPath(),
                    inferredKind.equals("file") ? file.kind() : inferredKind,
                    file.origin(), lifecycle, status));
        }

        for (WorktreeInspector.Change change : worktree.changes()) {
            if (change.projectPath().equals(projectPath)
                    || change.repositoryPath().equals(repositoryPath)
                    || change.repositoryPath().equals(projectPath)) {
                return Optional.of(new FileRiskTarget(change.projectPath(), change.repositoryPath(),
                        fileKind(change.projectPath()), fileOrigin(change.projectPath()),
                        change.status().equals("deleted") ? "deleted" : "current",
                        change.status()));
            }
        }

        String gitPath = repositoryPath;
        Optional<GitFileStats> stats = IndexReader.findFileStatsByPath(jdbi, gitPath);
        if (stats.isEmpty() && !projectPath.equals(gitPath)) {
            stats = IndexReader.findFileStatsByPath(jdbi, projectPath);
            if (stats.isPresent()) gitPath = projectPath;
        }
        if (stats.isPresent()) {
            return Optional.of(new FileRiskTarget(projectPath, gitPath, fileKind(projectPath),
                    fileOrigin(projectPath), liveLifecycle(worktree, gitPath, "historical"), null));
        }

        String projectRootValue = metadata.get("project_root");
        if (projectRootValue != null) {
            try {
                Path projectRoot = Path.of(projectRootValue).toAbsolutePath().normalize();
                Path candidate = projectRoot.resolve(projectPath).normalize();
                if (candidate.startsWith(projectRoot) && Files.isRegularFile(candidate)) {
                    String liveRepositoryPath = worktree.repositoryRoot() != null
                            && candidate.startsWith(worktree.repositoryRoot())
                            ? normalizePath(worktree.repositoryRoot().relativize(candidate))
                            : projectPath;
                    return Optional.of(new FileRiskTarget(projectPath, liveRepositoryPath,
                            fileKind(projectPath), fileOrigin(projectPath), "current", null));
                }
            } catch (RuntimeException ignored) {
                // Invalid or inaccessible paths are reported through the regular lookup error.
            }
        }
        return Optional.empty();
    }

    private String getFileRisk(Jdbi jdbi, FileRiskTarget file) {
        Optional<GitFileStats> stats = IndexReader.findFileStatsByPath(jdbi, file.repositoryPath());
        boolean hasFileHistory = stats.isPresent();
        int churn = stats.map(GitFileStats::commitCount).orElse(0);
        int authors = stats.map(GitFileStats::distinctAuthors).orElse(0);
        List<CoChangeRecord> coChanges = hasFileHistory
                ? IndexReader.findCoChangesByPath(jdbi, file.repositoryPath(), 100) : List.of();
        int coupling = coChanges.size();

        FileCriticality criticality = fileCriticality(file.projectPath());
        double churnScore = scaleScore(churn, 2, 10, 30, 50);
        double authorScore = busFactorScore(authors);
        double couplingScore = scaleScore(coupling, 0, 3, 5, 8);
        double criticalityWeight = hasFileHistory ? 0.50 : 1.0;
        double score = criticality.score() * criticalityWeight;
        if (hasFileHistory) {
            score += churnScore * 0.20 + authorScore * 0.15 + couplingScore * 0.15;
        }
        score = Math.round(score * 10.0) / 10.0;
        String level = riskLevel(score);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", file.repositoryPath());
        root.put("target_type", "file");
        root.put("file", file.repositoryPath());
        root.put("project_file", file.projectPath());
        root.put("kind", file.kind());
        root.put("origin", file.origin());
        root.put("lifecycle", file.lifecycle());
        if (file.worktreeStatus() != null) root.put("worktree_status", file.worktreeStatus());
        root.put("risk_score", score);
        root.put("risk_level", level);

        ObjectNode signals = root.putObject("signals");
        addSignal(signals, "file_criticality", criticality.score(), criticality.score(),
                criticalityWeight, criticality.note());
        if (hasFileHistory) {
            addSignal(signals, "git_churn", churn, churnScore, 0.20,
                    churn + " commits — " + (churn >= 30 ? "high" : churn >= 10 ? "moderate" : "low")
                            + " change frequency");
            addSignal(signals, "bus_factor", authors, authorScore, 0.15,
                    authors <= 1 ? "only 1 author — single point of knowledge" : authors + " authors");
            addSignal(signals, "coupling", coupling, couplingScore, 0.15,
                    coupling + " files frequently co-change");
            ArrayNode related = ((ObjectNode) signals.get("coupling")).putArray("top_files");
            for (CoChangeRecord coChange : coChanges.stream().limit(10).toList()) {
                ObjectNode node = related.addObject();
                node.put("file", coChange.filePath());
                node.put("co_change_count", coChange.coChangeCount());
            }
        } else {
            ObjectNode git = signals.putObject("git");
            git.put("note", "No Git history for this file; the score is based on file criticality only.");
        }

        root.put("recommendation", buildFileRecommendation(file, criticality, churn, authors,
                coupling, level, hasFileHistory));
        appendMeta(root, jdbi, 0);
        return root.toString();
    }

    private static String liveLifecycle(
            WorktreeInspector.Snapshot worktree, String repositoryPath, String fallback) {
        if (worktree.repositoryRoot() == null) return fallback;
        Path candidate = worktree.repositoryRoot().resolve(repositoryPath).normalize();
        return candidate.startsWith(worktree.repositoryRoot()) && Files.exists(candidate)
                ? "current" : fallback;
    }

    private static String normalizePath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static String fileKind(String path) {
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (normalized.contains("/META-INF/services/") || normalized.startsWith("META-INF/services/")) {
            return "service_descriptor";
        }
        if (isBuildConfiguration(normalized, name)) return "build_configuration";
        if (normalized.startsWith(".github/workflows/") || normalized.contains("/.github/workflows/")) {
            return "ci_configuration";
        }
        if (normalized.contains("/resources/") || normalized.startsWith("src/main/resources/")) {
            return "resource";
        }
        if (name.endsWith(".java") || name.endsWith(".kt")) return "source";
        if (normalized.startsWith("src/test/") || normalized.contains("/src/test/")) return "test";
        if (name.endsWith(".md") || name.endsWith(".adoc")) return "documentation";
        return "file";
    }

    private static String fileOrigin(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.contains("/generated/") || normalized.contains("/generated-sources/")) {
            return "generated";
        }
        if (fileKind(path).equals("resource") || fileKind(path).equals("service_descriptor")) {
            return "resource";
        }
        return "source";
    }

    private static FileCriticality fileCriticality(String path) {
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (normalized.contains("/META-INF/services/") || normalized.startsWith("META-INF/services/")) {
            return new FileCriticality(10,
                    "Service-provider registration and ordering can change compilation or runtime discovery globally");
        }
        if (isBuildConfiguration(normalized, name)) {
            return new FileCriticality(9,
                    "Build configuration can affect dependency resolution and every compiled module");
        }
        if (normalized.startsWith(".github/workflows/") || normalized.contains("/.github/workflows/")) {
            return new FileCriticality(8,
                    "CI configuration controls repository-wide validation and release behavior");
        }
        if (normalized.contains("/src/main/resources/") || normalized.startsWith("src/main/resources/")) {
            return new FileCriticality(7,
                    "Runtime resource changes can affect behavior without Java dependency edges");
        }
        if (normalized.startsWith("src/main/") || normalized.contains("/src/main/")) {
            return new FileCriticality(5,
                    "Production source without a resolved class graph has moderate structural impact");
        }
        if (normalized.startsWith("src/test/") || normalized.contains("/src/test/")) {
            return new FileCriticality(3, "Test-only file has limited production blast radius");
        }
        if (name.endsWith(".md") || name.endsWith(".adoc")) {
            return new FileCriticality(1, "Documentation does not directly affect compiled behavior");
        }
        return new FileCriticality(4, "General project file has no static class dependency graph");
    }

    private static boolean isBuildConfiguration(String path, String name) {
        return name.equals("pom.xml") || name.equals("build.gradle")
                || name.equals("build.gradle.kts") || name.equals("settings.gradle")
                || name.equals("settings.gradle.kts") || name.equals("gradle.properties")
                || name.equals("maven-wrapper.properties") || name.equals("gradle-wrapper.properties")
                || path.startsWith("buildSrc/") || path.contains("/buildSrc/")
                || path.startsWith("gradle/libs.versions.") || path.contains("/gradle/libs.versions.");
    }

    private static String buildFileRecommendation(FileRiskTarget file,
            FileCriticality criticality, int churn, int authors, int coupling,
            String level, boolean hasFileHistory) {
        List<String> parts = new ArrayList<>();
        parts.add(("HIGH".equals(level) || "CRITICAL".equals(level))
                ? level + "-risk file change." : "MEDIUM".equals(level)
                        ? "Moderate-risk file change." : "Low-risk file change.");
        parts.add(criticality.note() + ".");
        if (file.worktreeStatus() != null) {
            parts.add("The file is currently " + file.worktreeStatus() + " in the worktree.");
        }
        if (hasFileHistory && authors <= 1) parts.add("Single author — ensure review coverage.");
        if (churn >= 30) parts.add("Frequently changed — inspect get_file_history for recent context.");
        if (coupling > 0) parts.add("Review the co-changing files before modification.");
        if (!hasFileHistory) parts.add("No file history is available, so validate with focused tests.");
        if ("service_descriptor".equals(file.kind())) {
            parts.add("Verify provider membership and ordering with annotation-processing or ServiceLoader tests.");
        }
        return String.join(" ", parts);
    }

    String getExternalDeps(Jdbi jdbi, String target, String library, int limit) {
        if (!IndexReader.hasExternalDeps(jdbi)) {
            return errorResponse("No external dependency data. Re-run 'quill init' to index external dependencies.");
        }

        ObjectNode root = JSON.createObjectNode();

        if (target != null) {
            var lookup = ClassTargetResolver.resolve(jdbi, target);
            if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
            ClassRecord cls = lookup.cls();

            root.put("target", cls.className());
            var deps = IndexReader.findExternalDeps(jdbi, cls.id());

            Map<String, List<ExternalDepRecord>> byKind = new LinkedHashMap<>();
            for (ExternalDepRecord d : deps) {
                byKind.computeIfAbsent(d.usageKind(), k -> new ArrayList<>()).add(d);
            }

            ObjectNode depsNode = root.putObject("external_dependencies");
            for (var entry : byKind.entrySet()) {
                ArrayNode arr = depsNode.putArray(entry.getKey().toLowerCase());
                for (ExternalDepRecord d : entry.getValue()) {
                    arr.add(d.externalType());
                }
            }
            root.put("total_external_types", deps.size());
            appendMeta(root, jdbi, cls.sourceTokens());
        } else if (library != null) {
            root.put("library_filter", library);
            var classes = IndexReader.findClassesUsingType(jdbi, library + ".%");
            ArrayNode arr = root.putArray("classes_using_library");
            for (var entry : classes) {
                arr.add(entry.getValue());
            }
            root.put("total_classes", classes.size());
            appendMeta(root, jdbi, 0);
        } else {
            ArrayNode arr = root.putArray("libraries");
            for (var entry : IndexReader.findExternalDepsByLibrary(jdbi, limit)) {
                ObjectNode node = arr.addObject();
                node.put("package", entry.getKey());
                node.put("used_by_classes", entry.getValue());
            }
            appendMeta(root, jdbi, 0);
        }

        return root.toString();
    }

    private static String riskLevel(double score) {
        if (score >= 8) return "CRITICAL";
        if (score >= 6) return "HIGH";
        if (score >= 3) return "MEDIUM";
        return "LOW";
    }

    private static double scaleScore(int value, int low, int mid, int high, int max) {
        if (value <= low) return 0;
        if (value >= max) return 10;
        if (value <= mid) return 5.0 * (value - low) / (mid - low);
        if (value <= high) return 5.0 + 3.0 * (value - mid) / (high - mid);
        return 8.0 + 2.0 * (value - high) / (max - high);
    }

    private static double busFactorScore(int authors) {
        if (authors <= 0) return 0;
        if (authors == 1) return 10;
        if (authors == 2) return 5;
        if (authors == 3) return 2;
        return 0;
    }

    private static void addSignal(ObjectNode signals, String name, int value, double score, double weight, String note) {
        ObjectNode s = signals.putObject(name);
        s.put("value", value);
        s.put("score", Math.round(score * 10.0) / 10.0);
        s.put("weight", weight);
        s.put("note", note);
    }

    private static void appendDependencyBreakdown(ObjectNode signal,
            List<IndexReader.DependencyBreakdown> breakdown) {
        ObjectNode result = signal.putObject("breakdown");
        writeDependencyBreakdown(result, breakdown);
    }

    private static void writeDependencyBreakdown(ObjectNode result,
            List<IndexReader.DependencyBreakdown> breakdown) {
        for (IndexReader.DependencyBreakdown entry : breakdown) {
            ObjectNode origin = result.putObject(entry.origin());
            origin.put("classes", entry.classes());
            origin.put("edges", entry.edges());
            if (entry.origin().equals("orphan_output")) origin.put("excluded_from_score", true);
        }
    }

    private static String buildRecommendation(ClassRecord cls, int fanIn, int churn, int authors, String level, boolean hasGit) {
        List<String> parts = new ArrayList<>();
        String shortName = cls.className().contains(".")
                ? cls.className().substring(cls.className().lastIndexOf('.') + 1) : cls.className();

        if ("CRITICAL".equals(level) || "HIGH".equals(level)) {
            parts.add(level + "-risk change target.");
        } else if ("MEDIUM".equals(level)) {
            parts.add("Moderate risk.");
        } else {
            parts.add("Low risk — safe to modify.");
        }

        if (fanIn > 0) {
            parts.add(fanIn + " dependents will be affected. Review with get_dependencies(\"" + shortName + "\", direction=\"inbound\").");
        }
        if (hasGit && authors <= 1) {
            parts.add("Single author — ensure review coverage.");
        }
        if (hasGit && churn >= 30) {
            parts.add("Frequently changed — check get_file_history(\"" + shortName + "\") for recent context.");
        }
        if (!hasGit) {
            parts.add("Git data unavailable — risk may be underestimated.");
        }
        return String.join(" ", parts);
    }

    private void appendMeta(ObjectNode root, Jdbi jdbi, int naiveTokens) {
        String responseJson = root.toString();
        int responseTokens = TokenCounter.count(responseJson);
        MetaEnvelope meta = MetaEnvelope.from(jdbi, responseTokens, naiveTokens);
        ObjectNode metaNode = root.putObject("_meta");
        metaNode.put("index_id", meta.indexId());
        metaNode.put("indexed_at", meta.indexedAt());
        metaNode.put("indexed_commit", meta.lastCommit());
        // Kept for MCP clients built against the original envelope.
        metaNode.put("last_commit", meta.lastCommit());
        if (meta.currentCommit() == null) metaNode.putNull("current_commit");
        else metaNode.put("current_commit", meta.currentCommit());
        metaNode.put("commit_stale", meta.commitStale());
        metaNode.put("worktree_dirty", meta.worktreeDirty());
        metaNode.put("worktree_changed_files", meta.worktreeChangedFiles());
        metaNode.put("structural_changed_files", meta.structuralChangedFiles());
        metaNode.put("structure_stale", meta.structureStale());
        metaNode.set("stale_reasons", JSON.valueToTree(meta.staleReasons()));
        metaNode.put("stale_warning", meta.staleWarning());
        metaNode.put("response_tokens", meta.responseTokens());
        metaNode.put("naive_tokens", meta.naiveTokens());
        metaNode.put("compression", meta.compression());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class GraphBudget {
        private int remaining;
        private boolean truncated;

        private GraphBudget(int limit) {
            this.remaining = limit;
        }

        private boolean claim() {
            if (remaining == 0) {
                truncated = true;
                return false;
            }
            remaining--;
            return true;
        }
    }

    private static boolean isProducer(String kind) {
        return "PRODUCER_METHOD".equals(kind) || "PRODUCER_FIELD".equals(kind);
    }

    private String errorResponse(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    private String classLookupError(
            Jdbi jdbi, ClassTargetResolver.Lookup lookup, String target) {
        ObjectNode root = ClassTargetResolver.errorResponse(JSON, lookup, target);
        appendMeta(root, jdbi, 0);
        return root.toString();
    }
}
