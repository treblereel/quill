package org.treblereel.mcp.db;

import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;

/** Resolves a symbolic method reference to an indexed declaration, never a runtime receiver. */
public final class JvmMethodResolver {
    private static final int CAP = 5000;
    public record Resolution(String status, ClassRecord owner, ClassMemberRecord member, List<String> path) {
        public boolean resolved() { return owner != null && member != null; }
    }
    private final Jdbi db;
    private final Map<String,List<ClassRecord>> owners = new HashMap<>();
    private final Map<Integer,List<ClassMemberRecord>> members = new HashMap<>();
    private final Map<String,Resolution> cache = new HashMap<>();
    private final boolean limited;
    private final Set<String> ambiguousOccurrences;
    private final Map<String,List<Integer>> aliases=new HashMap<>();
    public JvmMethodResolver(Jdbi db) {
        this.db = db;
        ambiguousOccurrences=db.withHandle(h -> new HashSet<>(h.createQuery("""
                SELECT class_name FROM class_occurrences GROUP BY class_name
                HAVING count(DISTINCT output_directory)>1
                """).mapTo(String.class).list()));
        var classes = IndexReader.findAllClasses(db);
        limited = classes.size() > CAP;
        if (!limited) for (var c : classes) owners.computeIfAbsent(c.className(), ignored -> new ArrayList<>()).add(c);
    }
    private Resolution unknown(String status) { return new Resolution(status,null,null,List.of()); }
    public Resolution resolve(String owner, String name, String descriptor) {
        return cache.computeIfAbsent(owner + ":" + name + descriptor, ignored -> lookup(owner,name,descriptor));
    }
    private Resolution lookup(String owner, String name, String descriptor) {
        if (limited) return unknown("hierarchy_limit");
        var path = new ArrayList<String>();
        var seen = new HashSet<String>();
        var interfaces = new LinkedHashSet<String>();
        String cursor = owner;
        boolean interfaceOwner = false;
        while (cursor != null && !cursor.isBlank()) {
            if (!seen.add(cursor)) return unknown("hierarchy_cycle");
            path.add(cursor);
            if (ambiguousOccurrences.contains(cursor)) return unknown("owner_ambiguous");
            var matches = owners.getOrDefault(cursor,List.of());
            // java.lang.Object can be absent from the application index. It has no static
            // methods inherited by interfaces; Object instance methods require dependency evidence.
            if (matches.size() != 1) {
                if (cursor.equals("java.lang.Object") && !interfaces.isEmpty()
                        && !Set.of("toString","hashCode","equals","getClass","clone","finalize","wait","notify","notifyAll").contains(name)) break;
                return unknown(matches.isEmpty() ? "ancestor_not_indexed" : "owner_ambiguous");
            }
            var cls = matches.getFirst();
            if (path.size() == 1) interfaceOwner = "INTERFACE".equals(cls.kind());
            var declarations = declared(cls,name,descriptor);
            if (declarations.size() > 1) return unknown("declaration_ambiguous");
            if (declarations.size() == 1) return new Resolution(path.size()==1 ? "declared" : "inherited_class_declaration",cls,declarations.getFirst(),List.copyOf(path));
            if ("<init>".equals(name) || "<clinit>".equals(name)) return unknown("declaration_not_indexed");
            interfaces.addAll(cls.interfaces());
            cursor = interfaceOwner ? null : cls.superclass();
        }
        // Collect all interface declarations, then discard candidates shadowed by a subinterface.
        var pending = new ArrayDeque<String>(interfaces);
        var visited = new HashSet<String>();
        var candidates = new LinkedHashMap<String,ClassMemberRecord>();
        var graph = new HashMap<String,List<String>>();
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (!visited.add(next)) continue;
            if (ambiguousOccurrences.contains(next)) return unknown("owner_ambiguous");
            var matches = owners.getOrDefault(next,List.of());
            if (matches.size()!=1) return unknown(matches.isEmpty() ? "ancestor_not_indexed" : "owner_ambiguous");
            var cls = matches.getFirst();
            if (!"INTERFACE".equals(cls.kind())) return unknown("invalid_interface_hierarchy");
            for (var m : declared(cls,name,descriptor))
                if (!flag(m,"static") && !flag(m,"private")) candidates.put(next,m);
            graph.put(next,cls.interfaces());
            pending.addAll(cls.interfaces());
        }
        var degrees = new HashMap<String,Integer>();
        graph.keySet().forEach(key -> degrees.put(key,0));
        graph.values().forEach(parents -> parents.forEach(parent -> degrees.merge(parent,1,Integer::sum)));
        var ready = new ArrayDeque<String>(); degrees.forEach((key,value) -> {if(value==0) ready.add(key);});
        int processed=0;
        while(!ready.isEmpty()) {
            String next=ready.removeFirst(); processed++;
            for(String parent:graph.getOrDefault(next,List.of())) if(degrees.merge(parent,-1,Integer::sum)==0) ready.add(parent);
        }
        if(processed!=graph.size()) return unknown("hierarchy_cycle");
        var maximal = candidates.keySet().stream().filter(base -> candidates.keySet().stream()
                .noneMatch(child -> !base.equals(child) && subinterface(child,base,new HashSet<>()))).toList();
        if (maximal.size()!=1) return unknown(maximal.isEmpty() ? "declaration_not_indexed" : "interface_declaration_ambiguous");
        String selected=maximal.getFirst();
        return new Resolution("inherited_interface_declaration",owners.get(selected).getFirst(),candidates.get(selected),List.of(owner,selected));
    }
    private boolean subinterface(String child,String parent,Set<String> seen) {
        var pending=new ArrayDeque<String>(); pending.add(child);
        while(!pending.isEmpty()) {
            String next=pending.removeFirst(); if(!seen.add(next)) continue;
            var cls=owners.getOrDefault(next,List.of()); if(cls.size()!=1) continue;
            if(cls.getFirst().interfaces().contains(parent)) return true;
            pending.addAll(cls.getFirst().interfaces());
        }
        return false;
    }
    private List<ClassMemberRecord> declared(ClassRecord cls,String name,String descriptor) {
        return members.computeIfAbsent(cls.id(),id -> IndexReader.findJvmClassMembers(db,id)).stream()
                .filter(m -> ("METHOD".equals(m.kind()) || "CONSTRUCTOR".equals(m.kind()))
                        && ("CONSTRUCTOR".equals(m.kind()) ? "<init>" : m.name()).equals(name) && m.descriptor().equals(descriptor)).toList();
    }
    public static boolean flag(ClassMemberRecord member,String flag) {
        return member.modifiers()!=null && Arrays.asList(member.modifiers().strip().split("\\s+")).contains(flag);
    }
    /** Expand only references resolving to the selected exact JVM declaration. Overrides stay separate. */
    public List<Integer> symbolicOwners(int declarationId,String name,String descriptor) {
        return aliases.computeIfAbsent(declarationId+":"+name+descriptor,ignored->loadSymbolicOwners(declarationId,name,descriptor));
    }
    private List<Integer> loadSymbolicOwners(int declarationId,String name,String descriptor) {
        var ids = new TreeSet<Integer>(); ids.add(declarationId);
        if (limited || "<init>".equals(name) || "<clinit>".equals(name)) return List.copyOf(ids);
        var candidates = db.withHandle(h -> h.createQuery("""
                SELECT DISTINCT c.id,c.class_name FROM method_calls mc JOIN classes c ON c.id=mc.to_class_id
                WHERE mc.to_method=:name AND mc.to_descriptor=:descriptor
                  AND c.lifecycle='current' AND c.origin!='orphan_output'
                """).bind("name",name).bind("descriptor",descriptor)
                .map((rs,ctx) -> Map.entry(rs.getInt("id"),rs.getString("class_name"))).list());
        // Resolve the candidate ancestry before issuing member reads, so one batch replaces
        // hundreds of small SELECTs. Missing/ambiguous parents still remain unresolved.
        var pending=new ArrayDeque<String>();candidates.forEach(c->pending.add(c.getValue()));
        var seen=new HashSet<String>();var idsToLoad=new HashSet<Integer>();
        while(!pending.isEmpty()) {
            String owner=pending.removeFirst();if(!seen.add(owner))continue;
            var matches=owners.getOrDefault(owner,List.of());if(matches.size()!=1)continue;
            var cls=matches.getFirst();if(!members.containsKey(cls.id()))idsToLoad.add(cls.id());
            if(cls.superclass()!=null)pending.add(cls.superclass());pending.addAll(cls.interfaces());
        }
        var loaded=IndexReader.findJvmClassMembers(db,idsToLoad);
        idsToLoad.forEach(id->members.put(id,loaded.getOrDefault(id,List.of())));
        for (var candidate : candidates) {
            var resolution=resolve(candidate.getValue(),name,descriptor);
            if (resolution.resolved() && resolution.owner().id()==declarationId) ids.add(candidate.getKey());
        }
        return List.copyOf(ids);
    }
}
