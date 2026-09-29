package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.IndexReader.MethodCallView;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;

/** Reconstructs the emitted bytecode order of calls inside one indexed method. */
final class ExecutionOrderQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PERSISTENCE = Set.of(
            "persist", "save", "store", "repository", "persistence", "entitymanager", "dao");
    private static final Set<String> DISPATCH = Set.of(
            "dispatch", "submit", "publish", "send", "enqueue", "worker", "invoke", "execute");

    String analyze(Jdbi jdbi, String target, String method, String signature) {
        return analyze(jdbi, target, method, signature, PERSISTENCE, DISPATCH);
    }

    String analyze(Jdbi jdbi, String target, String method, String signature,
            Set<String> beforeTerms, Set<String> afterTerms) {
        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord cls = lookup.cls();
        List<ClassMemberRecord> candidates = IndexReader.findClassMembers(jdbi, cls.id()).stream()
                .filter(member -> member.kind().equals("METHOD")
                        || member.kind().equals("CONSTRUCTOR"))
                .filter(member -> indexedName(member).equals(method))
                .filter(member -> signature == null || signature.isBlank()
                        || member.descriptor().equals(signature)
                        || member.signature().equals(signature))
                .toList();
        if (candidates.size() != 1) return selectionError(jdbi, cls, method, signature, candidates);

        ClassMemberRecord selected = candidates.getFirst();
        String indexedMethod = indexedName(selected);
        List<MethodCallView> calls = IndexReader.findMethodCalls(jdbi, cls.id(), indexedMethod,
                selected.descriptor(), "outbound", 10_000, 0);
        List<Event> events = new ArrayList<>();
        for (MethodCallView call : calls) {
            String category = category(call, beforeTerms, afterTerms);
            for (int ordinal : call.instructionOrdinals()) {
                events.add(new Event(ordinal, call, category));
            }
        }
        events.sort(Comparator.comparingInt(Event::ordinal));

        List<Event> before = events.stream()
                .filter(event -> event.category().equals("before")).toList();
        List<Event> after = events.stream()
                .filter(event -> event.category().equals("after")).toList();
        boolean comparable = !before.isEmpty() && !after.isEmpty();
        boolean allBeforeAfter = comparable
                && before.stream().mapToInt(Event::ordinal).max().orElseThrow()
                < after.stream().mapToInt(Event::ordinal).min().orElseThrow();
        int branchCount = calls.stream().mapToInt(MethodCallView::callerBranchCount)
                .max().orElse(0);
        int exceptionHandlerCount = calls.stream()
                .mapToInt(MethodCallView::callerExceptionHandlerCount).max().orElse(0);
        boolean straightLine = branchCount == 0 && exceptionHandlerCount == 0;
        List<String> cfgEdges = calls.stream().map(MethodCallView::callerControlFlowEdges)
                .filter(edges -> !edges.isEmpty()).findFirst().orElse(List.of());
        boolean cfgDominanceProven = comparable && exceptionHandlerCount == 0
                && dominatesEveryAfter(before, after, cfgEdges);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("method", indexedMethod);
        root.put("signature", selected.signature());
        root.put("descriptor", selected.descriptor());
        root.put("event_count", events.size());
        ArrayNode sequence = root.putArray("bytecode_sequence");
        for (Event event : events) appendEvent(sequence, event);
        ObjectNode relation = root.putObject("ordering_analysis");
        relation.set("before_terms", JSON.valueToTree(beforeTerms));
        relation.set("after_terms", JSON.valueToTree(afterTerms));
        relation.put("instruction_order_status", comparable
                ? (allBeforeAfter ? "proven" : "disproven") : "unknown");
        relation.put("runtime_order_status", !comparable ? "unknown"
                : cfgDominanceProven && !straightLine ? "proven_on_all_cfg_paths"
                        : allBeforeAfter && straightLine
                                ? "proven_on_normal_completion" : "likely");
        relation.put("before_event_count", before.size());
        relation.put("after_event_count", after.size());
        relation.put("all_before_instructions_before_after", allBeforeAfter);
        if (beforeTerms.equals(PERSISTENCE) && afterTerms.equals(DISPATCH)) {
            root.set("persist_before_dispatch", relation.deepCopy());
        }
        ObjectNode controlFlow = root.putObject("control_flow");
        controlFlow.put("branch_count", branchCount);
        controlFlow.put("exception_handler_count", exceptionHandlerCount);
        controlFlow.put("straight_line", straightLine);
        controlFlow.put("edge_count", cfgEdges.size());
        controlFlow.put("dominance_proven", cfgDominanceProven);
        root.putArray("limitations")
                .add("Straight-line runtime proof applies only to normal completion; an earlier call may throw or terminate")
                .add("Branches, loops, exceptions, asynchronous completion, reflection, and external internals can change runtime order")
                .add("Persistence and dispatch labels are name-based classifications; inspect the listed calls");
        appendMeta(root, jdbi, cls.sourceTokens(), cls.sourceFile(), cls.module());
        return root.toString();
    }

    private static String indexedName(ClassMemberRecord member) {
        return member.kind().equals("CONSTRUCTOR") ? "<init>" : member.name();
    }

    private static String category(MethodCallView call, Set<String> beforeTerms,
            Set<String> afterTerms) {
        String searchable = (call.toClass() + " " + call.toMethod()).toLowerCase(Locale.ROOT);
        if (beforeTerms.stream().anyMatch(searchable::contains)) return "before";
        if (afterTerms.stream().anyMatch(searchable::contains)) return "after";
        return "call";
    }

    private static void appendEvent(ArrayNode sequence, Event event) {
        ObjectNode node = sequence.addObject();
        node.put("instruction_ordinal", event.ordinal());
        node.put("category", event.category());
        node.put("callee_class", event.call().toClass());
        node.put("callee_method", event.call().toMethod());
        node.put("callee_descriptor", event.call().toDescriptor());
        node.set("evidence_lines", JSON.valueToTree(event.call().evidenceLines()));
    }

    private static boolean dominatesEveryAfter(List<Event> before, List<Event> after,
            List<String> encodedEdges) {
        if (encodedEdges.isEmpty()) return false;
        Map<Integer, Set<Integer>> predecessors = new HashMap<>();
        Set<Integer> nodes = new HashSet<>();
        for (String encoded : encodedEdges) {
            int separator = encoded.indexOf('>');
            if (separator <= 0 || separator == encoded.length() - 1) continue;
            try {
                int from = Integer.parseInt(encoded.substring(0, separator));
                int to = Integer.parseInt(encoded.substring(separator + 1));
                nodes.add(from);
                nodes.add(to);
                predecessors.computeIfAbsent(to, ignored -> new HashSet<>()).add(from);
            } catch (NumberFormatException ignored) {
                // Invalid persisted evidence cannot establish dominance.
            }
        }
        if (nodes.isEmpty()) return false;
        int entry = nodes.stream().min(Integer::compareTo).orElseThrow();
        Map<Integer, Set<Integer>> dominators = new HashMap<>();
        for (int node : nodes) {
            dominators.put(node, node == entry ? new HashSet<>(Set.of(entry))
                    : new HashSet<>(nodes));
        }
        boolean changed;
        do {
            changed = false;
            for (int node : nodes) {
                if (node == entry) continue;
                Set<Integer> incoming = predecessors.getOrDefault(node, Set.of());
                Set<Integer> next = new HashSet<>();
                if (!incoming.isEmpty()) {
                    next.addAll(dominators.get(incoming.iterator().next()));
                    for (int predecessor : incoming) {
                        next.retainAll(dominators.get(predecessor));
                    }
                }
                next.add(node);
                if (!next.equals(dominators.get(node))) {
                    dominators.put(node, next);
                    changed = true;
                }
            }
        } while (changed);
        Set<Integer> beforeOrdinals = before.stream().map(Event::ordinal)
                .collect(java.util.stream.Collectors.toSet());
        return after.stream().allMatch(event -> dominators
                .getOrDefault(event.ordinal(), Set.of()).stream()
                .anyMatch(beforeOrdinals::contains));
    }

    private static String selectionError(Jdbi jdbi, ClassRecord cls, String method,
            String signature, List<ClassMemberRecord> candidates) {
        ObjectNode root = JSON.createObjectNode();
        root.put("error", candidates.isEmpty() ? "Method not found" : "Ambiguous method");
        root.put("target", cls.className());
        root.put("method", method);
        if (signature != null) root.put("requested_signature", signature);
        ArrayNode choices = root.putArray("candidates");
        for (ClassMemberRecord candidate : candidates) {
            ObjectNode choice = choices.addObject();
            choice.put("signature", candidate.signature());
            choice.put("descriptor", candidate.descriptor());
        }
        appendMeta(root, jdbi, cls.sourceTokens(), cls.sourceFile(), cls.module());
        return root.toString();
    }

    private record Event(int ordinal, MethodCallView call, String category) {}
}
