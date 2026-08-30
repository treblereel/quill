package org.treblereel.mcp.mcp;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.function.IntConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import org.treblereel.mcp.core.ProjectRootFinder;
import org.treblereel.mcp.core.TokenCounter;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.JokerDatabase;
import org.treblereel.mcp.model.*;
import org.treblereel.mcp.model.CoChangeRecord;
import org.treblereel.mcp.model.GitCommitFile;
import org.treblereel.mcp.model.GitCommitRecord;
import org.treblereel.mcp.model.GitFileStats;

@ApplicationScoped
public class JokerTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Tool(description = "List CDI beans with optional filtering by class_name, scope, kind, profile, qualifier")
    public String get_beans(
            @ToolArg(description = "Class name filter (supports * wildcard)") Optional<String> class_name,
            @ToolArg(description = "Scope filter, e.g. @ApplicationScoped") Optional<String> scope,
            @ToolArg(description = "Bean kind: CLASS, PRODUCER_METHOD, PRODUCER_FIELD, INTERCEPTOR, DECORATOR") Optional<String> kind,
            @ToolArg(description = "Build profile filter, e.g. dev") Optional<String> profile,
            @ToolArg(description = "Qualifier filter, e.g. @Premium") Optional<String> qualifier) {
        Path root = ProjectRootFinder.find(null);
        try (Connection conn = JokerDatabase.open(root.resolve(".joker/index.db"))) {
            return getBeans(conn, class_name.orElse(null), scope.orElse(null), kind.orElse(null),
                    profile.orElse(null), qualifier.orElse(null));
        } catch (Exception e) {
            return errorResponse(e.getMessage());
        }
    }

    @Tool(description = "Get dependency graph for a specific bean or class. Shows what it depends on and what depends on it.")
    public String get_dependencies(
            @ToolArg(description = "Class name (short or FQCN)") String target,
            @ToolArg(description = "Direction: inbound, outbound, or both (default: both)") Optional<String> direction,
            @ToolArg(description = "Graph traversal depth (default: 1)") Optional<Integer> depth) {
        Path root = ProjectRootFinder.find(null);
        try (Connection conn = JokerDatabase.open(root.resolve(".joker/index.db"))) {
            return getDependencies(conn, target, direction.orElse("both"), depth.orElse(1));
        } catch (Exception e) {
            return errorResponse(e.getMessage());
        }
    }

    @Tool(description = "Get detailed injection point information for a bean, showing all candidates and resolution status")
    public String get_injection_points(
            @ToolArg(description = "Bean class name (short or FQCN)") String target) {
        Path root = ProjectRootFinder.find(null);
        try (Connection conn = JokerDatabase.open(root.resolve(".joker/index.db"))) {
            return getInjectionPoints(conn, target);
        } catch (Exception e) {
            return errorResponse(e.getMessage());
        }
    }

    // Package-private for testing with injected connection
    String getBeans(Connection conn, String className, String scope, String kind, String profile, String qualifier) {
        Map<String, String> filter = new HashMap<>();
        if (className != null) filter.put("class_name", className);
        if (scope != null) filter.put("scope", scope);
        if (kind != null) filter.put("kind", kind);
        if (profile != null) filter.put("profile", profile);
        if (qualifier != null) filter.put("qualifier", qualifier);

        List<BeanRecord> beans = IndexReader.findBeans(conn, filter.isEmpty() ? null : filter);
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("beans");
        int[] naiveTokensWrapper = {0};

        for (BeanRecord b : beans) {
            ObjectNode node = arr.addObject();
            var classOpt = IndexReader.findClassById(conn, b.classId());
            String fqcn = classOpt.map(ClassRecord::className).orElse("unknown");
            node.put("class", fqcn);
            node.put("kind", b.kind());
            node.put("scope", b.scope());
            node.set("qualifiers", JSON.valueToTree(b.qualifiers()));
            node.set("bean_types", JSON.valueToTree(b.beanTypes()));
            node.set("profiles", JSON.valueToTree(b.profiles()));
            classOpt.ifPresent(c -> {
                node.put("source", c.sourceFile() + ":" + c.sourceLine());
                naiveTokensWrapper[0] += c.sourceTokens();
            });
        }
        root.put("total", beans.size());

        appendMeta(root, conn, naiveTokensWrapper[0]);
        return root.toString();
    }

    String getDependencies(Connection conn, String target, String direction, int depth) {
        var classOpt = IndexReader.findClassByName(conn, target);
        if (classOpt.isEmpty()) return errorResponse("Class not found: " + target);
        ClassRecord cls = classOpt.get();

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("is_bean", cls.isBean());

        if (cls.isBean()) {
            IndexReader.findBeanByClassId(conn, cls.id()).ifPresent(b -> {
                root.put("scope", b.scope());
            });
        }

        int[] naiveTokens = {cls.sourceTokens()};
        Set<Integer> visited = new HashSet<>();
        visited.add(cls.id());

        expandDependencies(conn, cls.id(), direction, depth, root, visited, t -> naiveTokens[0] += t);

        appendMeta(root, conn, naiveTokens[0]);
        return root.toString();
    }

    private void expandDependencies(Connection conn, int classId, String direction, int depth,
                                     ObjectNode node, Set<Integer> visited, IntConsumer tokenAccum) {
        List<DependencyRecord> deps = IndexReader.findDependencies(conn, classId, direction);

        ArrayNode dependsOn = node.putArray("depends_on");
        ArrayNode dependedBy = node.putArray("depended_by");

        for (DependencyRecord d : deps) {
            if (d.fromClassId() == classId) {
                IndexReader.findClassById(conn, d.toClassId()).ifPresent(c -> {
                    ObjectNode child = dependsOn.addObject();
                    child.put("class", c.className());
                    child.put("kind", d.kind());
                    tokenAccum.accept(c.sourceTokens());
                    if (depth > 1 && visited.add(c.id())) {
                        expandDependencies(conn, c.id(), direction, depth - 1, child, visited, tokenAccum);
                    }
                });
            }
            if (d.toClassId() == classId) {
                IndexReader.findClassById(conn, d.fromClassId()).ifPresent(c -> {
                    ObjectNode child = dependedBy.addObject();
                    child.put("class", c.className());
                    child.put("kind", d.kind());
                    tokenAccum.accept(c.sourceTokens());
                    if (depth > 1 && visited.add(c.id())) {
                        expandDependencies(conn, c.id(), direction, depth - 1, child, visited, tokenAccum);
                    }
                });
            }
        }
    }

    String getInjectionPoints(Connection conn, String target) {
        var classOpt = IndexReader.findClassByName(conn, target);
        if (classOpt.isEmpty()) return errorResponse("Class not found: " + target);
        ClassRecord cls = classOpt.get();

        var beanOpt = IndexReader.findBeanByClassId(conn, cls.id());
        if (beanOpt.isEmpty()) return errorResponse("Not a CDI bean: " + target);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());

        List<InjectionPointRecord> ips = IndexReader.findInjectionPoints(conn, beanOpt.get().id());

        ArrayNode arr = root.putArray("injection_points");
        ArrayNode unsatisfied = root.putArray("unsatisfied");
        ArrayNode ambiguous = root.putArray("ambiguous");

        for (InjectionPointRecord ip : ips) {
            ObjectNode node = arr.addObject();
            node.put("kind", ip.kind());
            node.put("field", ip.fieldName());
            node.put("required_type", ip.targetType());
            node.set("qualifiers", JSON.valueToTree(ip.qualifiers()));

            if (ip.resolvedBeanId() != null) {
                IndexReader.findBeanById(conn, ip.resolvedBeanId()).ifPresent(resolved -> {
                    IndexReader.findClassById(conn, resolved.classId()).ifPresent(c -> {
                        node.put("resolved_to", c.className());
                    });
                });
                node.put("resolution", ip.isAmbiguous() ? "ambiguous" : "unique");
            } else {
                node.put("resolved_to", (String) null);
                node.put("resolution", "unsatisfied");
                unsatisfied.add(ip.fieldName());
            }

            if (ip.isAmbiguous()) {
                ambiguous.add(ip.fieldName());
            }
        }

        appendMeta(root, conn, cls.sourceTokens());
        return root.toString();
    }

    private static final String NO_GIT_MESSAGE = "No git data available. "
            + "Initialize a git repository and re-run 'joker init' to enable git intelligence: "
            + "git init && git add -A && git commit -m 'initial'";

    @Tool(description = "Get most frequently changed files/classes by git commit count. Helps identify volatile areas of the codebase.")
    public String get_hotspots(
            @ToolArg(description = "Max results (default: 10)") Optional<Integer> limit,
            @ToolArg(description = "Only commits after this date, ISO format YYYY-MM-DD") Optional<String> since) {
        Path root = ProjectRootFinder.find(null);
        try (Connection conn = JokerDatabase.open(root.resolve(".joker/index.db"))) {
            return getHotspots(conn, limit.orElse(10), since.orElse(null));
        } catch (Exception e) {
            return errorResponse(e.getMessage());
        }
    }

    @Tool(description = "Get git commit history for a specific class or file")
    public String get_file_history(
            @ToolArg(description = "Class name (short or FQCN)") String target,
            @ToolArg(description = "Max commits to return (default: 10)") Optional<Integer> limit) {
        Path root = ProjectRootFinder.find(null);
        try (Connection conn = JokerDatabase.open(root.resolve(".joker/index.db"))) {
            return getFileHistory(conn, target, limit.orElse(10));
        } catch (Exception e) {
            return errorResponse(e.getMessage());
        }
    }

    @Tool(description = "Find files that frequently change together with a given class. Reveals hidden coupling not visible in the dependency graph.")
    public String get_co_changes(
            @ToolArg(description = "Class name (short or FQCN)") String target,
            @ToolArg(description = "Max results (default: 10)") Optional<Integer> limit) {
        Path root = ProjectRootFinder.find(null);
        try (Connection conn = JokerDatabase.open(root.resolve(".joker/index.db"))) {
            return getCoChanges(conn, target, limit.orElse(10));
        } catch (Exception e) {
            return errorResponse(e.getMessage());
        }
    }

    @Tool(description = "Get recently changed beans/classes from git history. Useful for understanding what was recently modified.")
    public String get_recent_changes(
            @ToolArg(description = "Number of recent commits to inspect (default: 10)") Optional<Integer> commits) {
        Path root = ProjectRootFinder.find(null);
        try (Connection conn = JokerDatabase.open(root.resolve(".joker/index.db"))) {
            return getRecentChanges(conn, commits.orElse(10));
        } catch (Exception e) {
            return errorResponse(e.getMessage());
        }
    }

    String getHotspots(Connection conn, int limit, String since) {
        if (!IndexReader.hasGitData(conn)) return errorResponse(NO_GIT_MESSAGE);

        List<GitFileStats> hotspots = IndexReader.findHotspots(conn, limit, since);
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("hotspots");

        for (GitFileStats s : hotspots) {
            ObjectNode node = arr.addObject();
            node.put("file", s.filePath());
            if (s.classId() != null) {
                IndexReader.findClassById(conn, s.classId()).ifPresent(c -> {
                    node.put("class", c.className());
                    node.put("is_bean", c.isBean());
                });
            }
            node.put("commit_count", s.commitCount());
            node.put("distinct_authors", s.distinctAuthors());
            node.put("last_modified", s.lastModified());
            node.put("last_author", s.lastAuthor());
        }
        root.put("total", hotspots.size());
        appendMeta(root, conn, 0);
        return root.toString();
    }

    String getFileHistory(Connection conn, String target, int limit) {
        if (!IndexReader.hasGitData(conn)) return errorResponse(NO_GIT_MESSAGE);

        var classOpt = IndexReader.findClassByName(conn, target);
        if (classOpt.isEmpty()) return errorResponse("Class not found: " + target);
        ClassRecord cls = classOpt.get();

        List<GitCommitRecord> commits = IndexReader.findFileHistory(conn, cls.id(), limit);
        var statsOpt = IndexReader.findFileStatsByClassId(conn, cls.id());

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("file", cls.sourceFile());

        ArrayNode arr = root.putArray("commits");
        for (GitCommitRecord c : commits) {
            ObjectNode node = arr.addObject();
            node.put("hash", c.shortHash());
            node.put("author", c.author());
            node.put("date", c.committedAt());
            node.put("message", c.message());
        }
        statsOpt.ifPresent(s -> root.put("total_commits", s.commitCount()));
        appendMeta(root, conn, cls.sourceTokens());
        return root.toString();
    }

    String getCoChanges(Connection conn, String target, int limit) {
        if (!IndexReader.hasGitData(conn)) return errorResponse(NO_GIT_MESSAGE);

        var classOpt = IndexReader.findClassByName(conn, target);
        if (classOpt.isEmpty()) return errorResponse("Class not found: " + target);
        ClassRecord cls = classOpt.get();

        var statsOpt = IndexReader.findFileStatsByClassId(conn, cls.id());
        int targetCommitCount = statsOpt.map(GitFileStats::commitCount).orElse(1);

        List<CoChangeRecord> coChanges = IndexReader.findCoChanges(conn, cls.id(), limit);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        ArrayNode arr = root.putArray("co_changes");
        for (CoChangeRecord co : coChanges) {
            ObjectNode node = arr.addObject();
            node.put("file", co.filePath());
            if (co.classId() != null) {
                IndexReader.findClassById(conn, co.classId()).ifPresent(c -> {
                    node.put("class", c.className());
                });
            }
            node.put("co_change_count", co.coChangeCount());
            double ratio = (double) co.coChangeCount() / targetCommitCount;
            node.put("coupling_ratio", Math.round(ratio * 100.0) / 100.0);
        }
        appendMeta(root, conn, cls.sourceTokens());
        return root.toString();
    }

    String getRecentChanges(Connection conn, int commitCount) {
        if (!IndexReader.hasGitData(conn)) return errorResponse(NO_GIT_MESSAGE);

        List<GitCommitRecord> commits = IndexReader.findRecentCommits(conn, commitCount);
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("recent_changes");

        for (GitCommitRecord c : commits) {
            ObjectNode node = arr.addObject();
            node.put("commit", c.shortHash());
            node.put("author", c.author());
            node.put("date", c.committedAt());
            node.put("message", c.message());

            List<GitCommitFile> files = IndexReader.findCommitFiles(conn, c.id());
            ArrayNode filesArr = node.putArray("files");
            for (GitCommitFile f : files) {
                ObjectNode fNode = filesArr.addObject();
                fNode.put("file", f.filePath());
                fNode.put("change_type", f.changeType());
                if (f.classId() != null) {
                    IndexReader.findClassById(conn, f.classId()).ifPresent(cl -> {
                        fNode.put("class", cl.className());
                        fNode.put("is_bean", cl.isBean());
                    });
                }
            }
        }
        appendMeta(root, conn, 0);
        return root.toString();
    }

    @Tool(description = "Get a high-level overview of the project: bean counts, scope breakdown, architecture hubs, problems, and git activity. Call this first to orient before diving into specifics.")
    public String get_overview() {
        Path root = ProjectRootFinder.find(null);
        try (Connection conn = JokerDatabase.open(root.resolve(".joker/index.db"))) {
            return getOverview(conn);
        } catch (Exception e) {
            return errorResponse(e.getMessage());
        }
    }

    @Tool(description = "Assess the risk of changing a specific class. Combines CDI dependency fan-in/out, git churn, author count, and coupling to produce a risk score with explanation.")
    public String get_risk(
            @ToolArg(description = "Class name (short or FQCN)") String target) {
        Path root = ProjectRootFinder.find(null);
        try (Connection conn = JokerDatabase.open(root.resolve(".joker/index.db"))) {
            return getRisk(conn, target);
        } catch (Exception e) {
            return errorResponse(e.getMessage());
        }
    }

    String getOverview(Connection conn) {
        Map<String, String> meta = IndexReader.getMetadata(conn);
        ObjectNode root = JSON.createObjectNode();

        ObjectNode project = root.putObject("project");
        int classCount = IndexReader.countClasses(conn);
        int beanCount = IndexReader.countBeans(conn);
        List<ClassRecord> allClasses = IndexReader.findAllClasses(conn);
        int totalTokens = allClasses.stream().mapToInt(ClassRecord::sourceTokens).sum();
        project.put("classes", classCount);
        project.put("beans", beanCount);
        project.put("total_source_tokens", totalTokens);
        project.put("indexed_at", meta.getOrDefault("indexed_at", "unknown"));
        project.put("last_commit", meta.getOrDefault("last_commit", "unknown"));

        ObjectNode scopeNode = root.putObject("beans_by_scope");
        IndexReader.countBeansByScope(conn).forEach(scopeNode::put);

        ObjectNode kindNode = root.putObject("beans_by_kind");
        IndexReader.countBeansByKind(conn).forEach(kindNode::put);

        ArrayNode hubs = root.putArray("architecture_hubs");
        for (var entry : IndexReader.findMostDependedOn(conn, 5)) {
            IndexReader.findClassById(conn, entry.getKey()).ifPresent(c -> {
                ObjectNode hub = hubs.addObject();
                hub.put("class", c.className());
                hub.put("dependents", entry.getValue());
                hub.put("is_bean", c.isBean());
            });
        }

        ObjectNode problems = root.putObject("problems");
        List<InjectionPointRecord> unsatisfied = IndexReader.findUnsatisfiedInjectionPoints(conn);
        ArrayNode unsatArr = problems.putArray("unsatisfied_injection_points");
        for (InjectionPointRecord ip : unsatisfied) {
            ObjectNode node = unsatArr.addObject();
            IndexReader.findBeanById(conn, ip.beanId()).ifPresent(b ->
                    IndexReader.findClassById(conn, b.classId()).ifPresent(c ->
                            node.put("bean", c.className())));
            node.put("field", ip.fieldName());
            node.put("type", ip.targetType());
        }
        List<InjectionPointRecord> ambiguous = IndexReader.findAmbiguousInjectionPoints(conn);
        ArrayNode ambArr = problems.putArray("ambiguous_injection_points");
        for (InjectionPointRecord ip : ambiguous) {
            ObjectNode node = ambArr.addObject();
            IndexReader.findBeanById(conn, ip.beanId()).ifPresent(b ->
                    IndexReader.findClassById(conn, b.classId()).ifPresent(c ->
                            node.put("bean", c.className())));
            node.put("field", ip.fieldName());
            node.put("type", ip.targetType());
        }

        if (IndexReader.hasGitData(conn)) {
            ObjectNode gitSummary = root.putObject("git_summary");
            gitSummary.put("total_commits_indexed", IndexReader.countCommits(conn));
            ArrayNode hotspotsArr = gitSummary.putArray("top_hotspots");
            for (GitFileStats s : IndexReader.findHotspots(conn, 3, null)) {
                ObjectNode h = hotspotsArr.addObject();
                h.put("file", s.filePath());
                h.put("commit_count", s.commitCount());
            }
        } else {
            root.putNull("git_summary");
        }

        appendMeta(root, conn, totalTokens);
        return root.toString();
    }

    String getRisk(Connection conn, String target) {
        var classOpt = IndexReader.findClassByName(conn, target);
        if (classOpt.isEmpty()) return errorResponse("Class not found: " + target);
        ClassRecord cls = classOpt.get();

        int fanIn = IndexReader.countDependents(conn, cls.id());
        int fanOut = IndexReader.countDependencies(conn, cls.id());

        boolean hasGit = IndexReader.hasGitData(conn);
        var statsOpt = hasGit ? IndexReader.findFileStatsByClassId(conn, cls.id()) : Optional.<GitFileStats>empty();
        int churn = statsOpt.map(GitFileStats::commitCount).orElse(0);
        int authors = statsOpt.map(GitFileStats::distinctAuthors).orElse(0);
        int coChangeCount = 0;
        if (hasGit) {
            coChangeCount = IndexReader.findCoChanges(conn, cls.id(), 100).size();
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

        String level;
        if (score >= 8) level = "CRITICAL";
        else if (score >= 6) level = "HIGH";
        else if (score >= 3) level = "MEDIUM";
        else level = "LOW";

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("risk_score", score);
        root.put("risk_level", level);

        ObjectNode signals = root.putObject("signals");
        addSignal(signals, "fan_in", fanIn, fanInScore, 0.30,
                fanIn + " classes depend on this");
        addSignal(signals, "fan_out", fanOut, fanOutScore, 0.10,
                "depends on " + fanOut + " classes");
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
            gitNote.put("note", "Git data unavailable — git signals excluded from score. Run 'joker init' in a git repository.");
        }

        root.put("recommendation", buildRecommendation(cls, fanIn, churn, authors, level, hasGit));

        appendMeta(root, conn, cls.sourceTokens());
        return root.toString();
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

    private void appendMeta(ObjectNode root, Connection conn, int naiveTokens) {
        String responseJson = root.toString();
        int responseTokens = TokenCounter.count(responseJson);
        MetaEnvelope meta = MetaEnvelope.from(conn, null, responseTokens, naiveTokens);
        ObjectNode metaNode = root.putObject("_meta");
        metaNode.put("indexed_at", meta.indexedAt());
        metaNode.put("last_commit", meta.lastCommit());
        metaNode.put("stale_warning", meta.staleWarning());
        metaNode.put("response_tokens", meta.responseTokens());
        metaNode.put("naive_tokens", meta.naiveTokens());
        metaNode.put("compression", meta.compression());
    }

    private String errorResponse(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }
}
