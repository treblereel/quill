package org.treblereel.mcp.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.objectweb.asm.Type;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.IndexReader.MethodCallView;

/** Expands compiler forwarding edges, retaining the bytecode path as evidence. */
final class KotlinUsageNormalizer {
    record Usage(MethodCallView call, List<MethodCallView> path) {}
    record Result(List<Usage> usages, boolean truncated) {}
    private static final int CAP = 5_000;

    record SourceMember(String className, String method, String descriptor, String sourceFile) {}

    /** Returns a source declaration only after every edge has compiler-forwarding evidence. */
    static java.util.Optional<SourceMember> sourceTarget(Jdbi jdbi, int owner, String method, String descriptor) {
        Set<String> visited = new HashSet<>();
        for (int depth = 0; depth < 32; depth++) {
            if (!visited.add(owner + "|" + method + "|" + descriptor)) break;
            final String name = method, desc = descriptor;
            boolean declared = IndexReader.findKotlinDeclarations(jdbi, owner).stream().anyMatch(value ->
                    ("FUNCTION".equals(value.kind()) || "CONSTRUCTOR".equals(value.kind()))
                    && name.equals(value.jvmName()) && desc.equals(value.descriptor()));
            if (declared) {
                if (depth == 0) return java.util.Optional.empty();
                var cls = IndexReader.findClassById(jdbi, owner);
                return cls.map(value -> new SourceMember(value.className(), name, desc, value.sourceFile()));
            }
            var calls = IndexReader.findAdjacentMethodCalls(jdbi, owner, method, descriptor, "outbound");
            if (calls.size() != 1 || calls.getFirst().occurrenceCount() != 1 || !forwarder(jdbi, calls.getFirst())) break;
            var call = calls.getFirst();
            owner = call.toClassId(); method = call.toMethod(); descriptor = call.toDescriptor();
        }
        return java.util.Optional.empty();
    }

    static Result inbound(Jdbi jdbi, int owner, String method, String descriptor) {
        var result = new ArrayList<Usage>();
        boolean[] truncated = {false};
        int[] visited = {0};
        expand(jdbi, owner, method, descriptor, List.of(), new HashSet<>(), result, truncated, visited);
        return new Result(List.copyOf(result), truncated[0]);
    }

    private static void expand(Jdbi jdbi, int owner, String method, String descriptor,
            List<MethodCallView> suffix, Set<String> active, List<Usage> result,
            boolean[] truncated, int[] visited) {
        String key = owner + "|" + method + "|" + descriptor;
        if (!active.add(key)) { truncated[0] = true; return; }
        if (active.size() > 32) { truncated[0] = true; active.remove(key); return; }
        for (var call : IndexReader.findAdjacentMethodCalls(jdbi, owner, method, descriptor, "inbound")) {
            if (++visited[0] > CAP) { truncated[0] = true; break; }
            var path = new ArrayList<MethodCallView>();
            path.add(call); path.addAll(suffix);
            if (forwarder(jdbi, call)) {
                expand(jdbi, call.fromClassId(), call.fromMethod(), call.fromDescriptor(),
                        path, active, result, truncated, visited);
            } else result.add(new Usage(call, List.copyOf(path)));
        }
        active.remove(key);
    }

    private static boolean forwarder(Jdbi jdbi, MethodCallView call) {
        var source = IndexReader.findKotlinDeclarations(jdbi, call.fromClassId());
        var target = IndexReader.findKotlinDeclarations(jdbi, call.toClassId());
        if (source.isEmpty() || target.isEmpty()) return false;
        // A real source function must never be collapsed just because it forwards a call.
        if (source.stream().anyMatch(value -> ("FUNCTION".equals(value.kind()) || "PROPERTY".equals(value.kind()) || "CONSTRUCTOR".equals(value.kind()))
                && call.fromMethod().equals(value.jvmName())
                && call.fromDescriptor().equals(value.descriptor()))) return false;
        if (call.toMethod().equals(call.fromMethod() + "$default")
                && (call.fromClassId() == call.toClassId() || call.toClass().equals(call.fromClass() + "$Companion"))) {
            boolean overload = IndexReader.findClassMembers(jdbi, call.fromClassId()).stream()
                    .anyMatch(member -> call.fromMethod().equals(member.name())
                            && call.fromDescriptor().equals(member.descriptor())
                            && member.annotations().stream().anyMatch(annotation -> annotation.equals("kotlin.jvm.JvmOverloads")
                                || annotation.equals("@JvmOverloads")));
            boolean declaredDefault = target.stream().anyMatch(value -> "FUNCTION".equals(value.kind())
                    && call.fromMethod().equals(value.jvmName()) && value.hasDefaultParameters());
            if (overload && declaredDefault) {
                return IndexReader.findAdjacentMethodCalls(jdbi, call.fromClassId(),
                        call.fromMethod(), call.fromDescriptor(), "outbound").size() == 1;
            }
        }
        boolean constructor = "<init>".equals(call.toMethod());
        var declaration = target.stream().filter(value -> ("FUNCTION".equals(value.kind()) || "PROPERTY".equals(value.kind()) || "CONSTRUCTOR".equals(value.kind()))
                && call.toMethod().equals(value.jvmName())
                && call.toDescriptor().equals(value.descriptor())).findFirst();
        boolean targetOverload = declaration.isEmpty()
                && target.stream().anyMatch(value -> "FUNCTION".equals(value.kind())
                    && call.toMethod().equals(value.jvmName()) && value.hasDefaultParameters())
                && IndexReader.findClassMembers(jdbi, call.toClassId()).stream().anyMatch(member ->
                    call.toMethod().equals(member.name()) && call.toDescriptor().equals(member.descriptor())
                        && member.annotations().stream().anyMatch(annotation ->
                            annotation.equals("kotlin.jvm.JvmOverloads") || annotation.equals("@JvmOverloads")));
        if (!constructor && declaration.isEmpty() && !targetOverload) return false;
        boolean sameOwner = call.fromClassId() == call.toClassId();
        Type[] from = Type.getArgumentTypes(call.fromDescriptor());
        Type[] to = Type.getArgumentTypes(call.toDescriptor());
        if (constructor && sameOwner && "<init>".equals(call.fromMethod())
                && to.length > 0 && to[to.length - 1].getDescriptor().equals("Lkotlin/jvm/internal/DefaultConstructorMarker;")
                && target.stream().anyMatch(value -> "CONSTRUCTOR".equals(value.kind()) && value.hasDefaultParameters())) {
            boolean overload = IndexReader.findClassMembers(jdbi, call.fromClassId()).stream().anyMatch(member ->
                    "CONSTRUCTOR".equals(member.kind()) && call.fromDescriptor().equals(member.descriptor())
                    && member.annotations().contains("kotlin.jvm.JvmOverloads"));
            return overload && call.occurrenceCount() == 1 && IndexReader.findAdjacentMethodCalls(jdbi,
                    call.fromClassId(), call.fromMethod(), call.fromDescriptor(), "outbound").size() == 1;
        }
        if (sameOwner && (call.fromMethod().equals(call.toMethod() + "$default")
                || constructor && call.fromMethod().equals("<init>"))) {
            if (!constructor && (declaration.isEmpty() || !declaration.get().hasDefaultParameters())) return false;
            String marker = constructor ? "Lkotlin/jvm/internal/DefaultConstructorMarker;" : "Ljava/lang/Object;";
            if (from.length == 0 || !from[from.length - 1].getDescriptor().equals(marker)
                    || !Type.getReturnType(call.fromDescriptor()).equals(Type.getReturnType(call.toDescriptor()))) return false;
            int receiver = !constructor && from.length > 0
                    && from[0].getDescriptor().equals("L" + call.toClass().replace('.', '/') + ";")
                    && !call.invocationKind().equals("static") ? 1 : 0;
            if (constructor && declaration.isPresent() && !declaration.get().hasDefaultParameters()) return false;
            int valueParameters = declaration.isPresent() && declaration.get().valueParameters() != null
                    ? declaration.get().valueParameters().size()
                    : to.length - (!constructor && declaration.get().isSuspend() ? 1 : 0)
                        - (!constructor && declaration.get().extension() ? 1 : 0);
            int masks = (valueParameters + 31) / 32;
            if (from.length != receiver + to.length + masks + 1) return false;
            for (int i = 0; i < to.length; i++) if (!from[receiver + i].equals(to[i])) return false;
            for (int i = receiver + to.length; i < from.length - 1; i++)
                if (!from[i].equals(Type.INT_TYPE)) return false;
            return true;
        }
        if (!call.fromMethod().equals(call.toMethod())) return false;
        if (sameOwner && !call.fromDescriptor().equals(call.toDescriptor())) {
            var physical = IndexReader.findClassMembers(jdbi, call.fromClassId()).stream()
                    .filter(member -> call.fromMethod().equals(member.name())
                            && call.fromDescriptor().equals(member.descriptor())).findFirst();
            boolean overload = physical.isPresent() && physical.get().annotations().stream()
                    .anyMatch(annotation -> annotation.equals("kotlin.jvm.JvmOverloads") || annotation.equals("@JvmOverloads"));
            boolean hiddenBridge = physical.isEmpty() && from.length == to.length;
            if (overload || hiddenBridge) {
                var outgoing = IndexReader.findAdjacentMethodCalls(jdbi, call.fromClassId(),
                        call.fromMethod(), call.fromDescriptor(), "outbound");
                return outgoing.size() == 1 && call.occurrenceCount() == 1;
            }
        }
        boolean multifile = source.stream().anyMatch(value -> "MULTIFILE_FACADE".equals(value.kind()))
                && target.stream().anyMatch(value -> "MULTIFILE_PART".equals(value.kind()))
                && call.toClass().startsWith(call.fromClass() + "__");
        boolean companion = call.toClass().equals(call.fromClass() + "$Companion")
                && target.stream().anyMatch(value -> "COMPANION_OBJECT".equals(value.kind()));
        if ((multifile || companion) && call.fromDescriptor().equals(call.toDescriptor())) {
            var outgoing = IndexReader.findAdjacentMethodCalls(jdbi, call.fromClassId(),
                    call.fromMethod(), call.fromDescriptor(), "outbound");
            return outgoing.size() == 1 && call.occurrenceCount() == 1;
        }
        return false;
    }
}
