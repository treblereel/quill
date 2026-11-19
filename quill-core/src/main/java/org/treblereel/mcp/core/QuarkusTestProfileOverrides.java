package org.treblereel.mcp.core;

import java.nio.file.Path;
import java.util.*;
import org.objectweb.asm.*;
import org.treblereel.mcp.model.ClassRecord;

/** Literal, straight-line map declarations only. Never loads or executes application classes. */
public final class QuarkusTestProfileOverrides {
    private static final String PROFILE = "io.quarkus.test.junit.QuarkusTestProfile";
    public record Value(String key, String value, int line) {}
    public record Parsed(boolean declared, String status, int line, List<Value> values) {}
    private record Node(ClassFileSnapshot.Entry entry, String name, String parent, List<String> interfaces, int access) {}
    private record Text(String value, int line) {}
    private record Pair(Text key, Text value) {}
    private record LiteralMap(List<Value> values) {}
    private static final Object UNKNOWN = new Object();

    static List<ConfigurationScanner.Definition> scan(Path root, ClassFileSnapshot snapshot,
            Map<String, ClassRecord> classes) {
        try { root = root.toRealPath(); } catch (java.io.IOException unavailable) { throw new IllegalStateException("Project root unavailable", unavailable); }
        Map<String, Node> nodes = new TreeMap<>();
        for (var entry : snapshot.entries()) {
            var reader = new ClassReader(entry.bytecode());
            String name = reader.getClassName().replace('/', '.');
            nodes.put(name, new Node(entry, name, reader.getSuperName() == null ? null : reader.getSuperName().replace('/', '.'),
                    Arrays.stream(reader.getInterfaces()).map(s -> s.replace('/', '.')).toList(),reader.getAccess()));
        }
        Map<String, List<String>> owners = new TreeMap<>();
        Map<String, Parsed> parsed = new HashMap<>();
        for (var node : nodes.values()) {
            if ((node.access() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0 || !subtype(node.name(), nodes, classes, new HashSet<>())) continue;
            String owner = node.name(); Set<String> visited = new HashSet<>();
            while (owner != null && nodes.containsKey(owner) && visited.add(owner)) {
                String current = owner;
                Parsed body = parsed.computeIfAbsent(owner, n -> read(nodes.get(n).entry().bytecode()));
                if (body.declared()) { owners.computeIfAbsent(current, n -> new ArrayList<>()).add(node.name()); break; }
                owner = nodes.get(owner).parent();
            }
            if (owner == null || !nodes.containsKey(owner)) {
                owners.computeIfAbsent(node.name(), n -> new ArrayList<>()).add(node.name());
                parsed.put(node.name(), new Parsed(false, "method_outside_snapshot", 0, List.of()));
            }
        }
        List<ConfigurationScanner.Definition> result = new ArrayList<>();
        var json = Json.MAPPER;
        for (var item : owners.entrySet()) {
            Node node = nodes.get(item.getKey()); ClassRecord cls = classes.get(item.getKey());
            if (cls == null || cls.sourceFile() == null || cls.module() == null || cls.sourceSet() == null) continue;
            try {
                Path source = root.resolve(cls.sourceFile()).toRealPath(), binary = node.entry().path().toRealPath();
                if (!source.startsWith(root) || !binary.startsWith(root)) continue;
                Parsed body = parsed.get(item.getKey());
                var evidence = json.createObjectNode().put("declaring_class", item.getKey()).put("method", "getConfigOverrides()")
                        .put("status", body.status()).put("activation", "unknown")
                        .put("source_sha256", SourceFileHashes.hash(source))
                        .put("binary_file", root.relativize(binary).toString().replace('\\', '/'))
                        .put("binary_sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(node.entry().bytecode())))
                        .put("profile_count", item.getValue().size());
                var profiles = evidence.putArray("profile_classes"); item.getValue().forEach(profiles::add);
                if (!body.status().equals("static_map")) {
                    result.add(new ConfigurationScanner.Definition("<dynamic>", "quarkus_test_profile_unknown", cls.sourceFile(), body.line(), cls.module(), cls.sourceSet(), evidence.toString()));
                } else for (Value value : body.values()) {
                    result.add(new ConfigurationScanner.Definition(value.key(), "quarkus_test_profile", cls.sourceFile(), value.line(), cls.module(), cls.sourceSet(), evidence.toString()));
                }
            } catch (Exception unavailable) {
                throw new IllegalStateException("Could not capture test-profile declaration provenance for " + item.getKey(), unavailable);
            }
        }
        return List.copyOf(result);
    }

    private static boolean subtype(String name, Map<String, Node> nodes, Map<String, ClassRecord> classes, Set<String> seen) {
        if (PROFILE.equals(name)) return true;
        if (name == null || !seen.add(name)) return false;
        Node node = nodes.get(name); ClassRecord cls = classes.get(name);
        List<String> interfaces = node != null ? node.interfaces() : cls != null ? cls.interfaces() : List.of();
        for (String iface : interfaces) if (subtype(iface, nodes, classes, seen)) return true;
        return subtype(node != null ? node.parent() : cls != null ? cls.superclass() : null, nodes, classes, seen);
    }

    public static Parsed read(byte[] bytecode) {
        Parsed[] result = {new Parsed(false, "method_absent", 0, List.of())};
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals("getConfigOverrides") || Type.getArgumentTypes(desc).length != 0 || (access & Opcodes.ACC_STATIC) != 0) return null;
                // Covariant overrides/bridges must mask a literal superclass body, never inherit its answer.
                if (!desc.equals("()Ljava/util/Map;") || (access & Opcodes.ACC_BRIDGE) != 0) {
                    if (!result[0].declared()) result[0] = new Parsed(true,"unsupported_body",0,List.of());
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    final List<Object> stack = new ArrayList<>(); final Map<Integer,Object> locals = new HashMap<>();
                    int line, firstLine, returns; boolean bad = (access & Opcodes.ACC_ABSTRACT) != 0; LiteralMap returned;
                    Object pop() { return stack.isEmpty() ? UNKNOWN : stack.removeLast(); }
                    void fail() { bad = true; stack.clear(); }
                    @Override public void visitLineNumber(int n, Label label) { line = n; if (firstLine == 0) firstLine = n; }
                    @Override public void visitLdcInsn(Object value) { if (!bad) stack.add(value instanceof String s ? new Text(s,line) : UNKNOWN); }
                    @Override public void visitVarInsn(int op, int var) {
                        if (bad) return;
                        if (op == Opcodes.ASTORE) locals.put(var,pop());
                        else if (op == Opcodes.ALOAD) stack.add(locals.getOrDefault(var,UNKNOWN)); else fail();
                    }
                    @Override public void visitIntInsn(int op,int value) { if (!bad && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) stack.add(value); else fail(); }
                    @Override public void visitTypeInsn(int op,String type) {
                        if (bad) return; Object size = pop();
                        if (op == Opcodes.ANEWARRAY && type.equals("java/util/Map$Entry") && size instanceof Integer n && n >= 0 && n <= 32) stack.add(new Object[n]); else fail();
                    }
                    @Override public void visitInsn(int op) {
                        if (bad) return;
                        if (op >= Opcodes.ICONST_0 && op <= Opcodes.ICONST_5) stack.add(op-Opcodes.ICONST_0);
                        else if (op == Opcodes.DUP && !stack.isEmpty()) stack.add(stack.getLast());
                        else if (op == Opcodes.AASTORE) {
                            Object value=pop(), index=pop(), array=pop();
                            if (array instanceof Object[] a && index instanceof Integer i && i>=0 && i<a.length && value instanceof Pair) a[i]=value; else fail();
                        } else if (op == Opcodes.ARETURN) {
                            Object value=pop(); returns++; if (value instanceof LiteralMap map && stack.isEmpty()) returned=map; else fail();
                        } else if (op != Opcodes.NOP) fail();
                    }
                    @Override public void visitMethodInsn(int op,String owner,String name,String desc,boolean iface) {
                        if (bad) return;
                        int count=Type.getArgumentTypes(desc).length; List<Object> args=new ArrayList<>();
                        for(int i=0;i<count;i++) args.addFirst(pop());
                        if(op != Opcodes.INVOKESTATIC) { fail(); return; }
                        if(owner.equals("java/util/Map") && name.equals("entry") && count==2 && args.get(0) instanceof Text k && args.get(1) instanceof Text v) { stack.add(new Pair(k,v)); return; }
                        List<Pair> pairs=new ArrayList<>();
                        if(owner.equals("java/util/Map") && name.equals("ofEntries") && count==1 && args.getFirst() instanceof Object[] array) {
                            for(Object value:array) if(value instanceof Pair pair) pairs.add(pair); else { fail(); return; }
                        } else if ((owner.equals("java/util/Map") && name.equals("of")) || (owner.equals("java/util/Collections") && name.equals("singletonMap") && count==2) || (owner.equals("java/util/Collections") && name.equals("emptyMap") && count==0)) {
                            if(count%2!=0 || count>64) { fail(); return; }
                            for(int i=0;i<count;i+=2) if(args.get(i) instanceof Text k && args.get(i+1) instanceof Text v) pairs.add(new Pair(k,v)); else { fail(); return; }
                        } else { fail(); return; }
                        Set<String> keys=new HashSet<>();List<Value> values=new ArrayList<>();
                        for(Pair pair:pairs) { if(!keys.add(pair.key().value())) { fail(); return; } values.add(new Value(pair.key().value(),pair.value().value(),pair.key().line())); }
                        stack.add(new LiteralMap(List.copyOf(values)));
                    }
                    @Override public void visitFieldInsn(int op,String owner,String name,String desc) { fail(); }
                    @Override public void visitJumpInsn(int op,Label label) { fail(); }
                    @Override public void visitTryCatchBlock(Label start,Label end,Label handler,String type) { fail(); }
                    @Override public void visitInvokeDynamicInsn(String name,String desc,Handle handle,Object... args) { fail(); }
                    @Override public void visitIincInsn(int var,int increment) { fail(); }
                    @Override public void visitTableSwitchInsn(int min,int max,Label dflt,Label... labels) { fail(); }
                    @Override public void visitLookupSwitchInsn(Label dflt,int[] keys,Label[] labels) { fail(); }
                    @Override public void visitMultiANewArrayInsn(String desc,int dimensions) { fail(); }
                    @Override public void visitEnd() { result[0]=new Parsed(true,!bad && returned!=null && returns==1 ? "static_map" : "unsupported_body",firstLine,!bad && returned!=null && returns==1 ? returned.values() : List.of()); }
                };
            }
        },ClassReader.SKIP_FRAMES);
        return result[0];
    }
    private QuarkusTestProfileOverrides() {}
}
