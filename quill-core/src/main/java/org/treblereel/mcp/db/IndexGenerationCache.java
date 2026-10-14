package org.treblereel.mcp.db;

import java.util.*;
import java.util.function.Supplier;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ClassMemberRecord;

/** Bounded immutable generation evidence. Writable fixtures and staging indexes never enter the cache. */
public final class IndexGenerationCache {
    private static final int MAX_GENERATIONS=8, MAX_MEMBERS=100_000;
    private static final Map<Jdbi,Entry> CACHE=new WeakHashMap<>();
    private static long sequence;
    private record Entry(Graph graph,long access) {}
    private IndexGenerationCache() {}
    public static Graph get(Jdbi database) {
        if(!QuillDatabase.isPooled(database)) return load(database);
        synchronized(CACHE) {
            Entry entry=CACHE.get(database);
            if(entry!=null){CACHE.put(database,new Entry(entry.graph(),++sequence));return entry.graph();}
            Graph graph=load(database);
            if(graph.memberCount>MAX_MEMBERS)return graph;
            while(CACHE.size()>=MAX_GENERATIONS || totalMembers()+graph.memberCount>MAX_MEMBERS) {
                var victim=CACHE.entrySet().stream().min(Comparator.comparingLong(e->e.getValue().access())).orElse(null);
                if(victim==null)break;CACHE.remove(victim.getKey());
            }
            if(graph.memberCount<=MAX_MEMBERS) CACHE.put(database,new Entry(graph,++sequence));
            return graph;
        }
    }
    private static int totalMembers(){return CACHE.values().stream().mapToInt(e->e.graph().memberCount).sum();}
    public static void invalidate(Jdbi database){synchronized(CACHE){CACHE.remove(database);}}
    static int size(){synchronized(CACHE){return CACHE.size();}}
    private static Graph load(Jdbi database) {
        var classes=IndexReader.findAllClasses(database).stream().map(c->new ClassRecord(c.id(),c.className(),c.kind(),c.superclass(),List.copyOf(c.interfaces()),
                c.sourceFile(),c.sourceLine(),c.isBean(),c.sourceTokens(),c.fileId(),c.origin(),c.lifecycle(),c.module(),c.sourceSet())).toList();
        Map<Integer,List<ClassMemberRecord>> members=new HashMap<>();
        if(classes.size()<=5000) {
            members.putAll(IndexReader.findJvmClassMembers(database,classes.stream().map(ClassRecord::id).toList()));
            classes.forEach(c->members.putIfAbsent(c.id(),List.of()));
        }
        Set<String> ambiguous=database.withHandle(h->new HashSet<>(h.createQuery("SELECT class_name FROM class_occurrences GROUP BY class_name HAVING count(DISTINCT output_directory)>1").mapTo(String.class).list()));
        return new Graph(classes,members,ambiguous);
    }
    public static final class Graph {
        public final List<ClassRecord> classes;
        public final Map<String,List<ClassRecord>> owners;
        public final Map<Integer,List<ClassMemberRecord>> members;
        public final Set<String> ambiguous;
        public final Map<Integer,List<ClassMemberRecord>> sourceMembers;
        private final Map<Integer,List<ClassRecord>> children;
        private final int memberCount;
        private final Map<String,List<Integer>> aliases=bounded(256);
        private final Map<String,JvmMethodResolver.Resolution> resolutions=bounded(1024);
        private final Map<Integer,Map<Integer,List<String>>> ancestry=bounded(16);
        private final Map<Integer,List<ClassRecord>> descendants=bounded(64);
        private final Map<String,String> dispatch=bounded(256);
        private Graph(List<ClassRecord> classes,Map<Integer,List<ClassMemberRecord>> members,Set<String> ambiguous) {
            this.classes=classes;Map<String,List<ClassRecord>> ownerMap=new HashMap<>();
            classes.forEach(c->ownerMap.computeIfAbsent(c.className(),ignored->new ArrayList<>()).add(c));
            ownerMap.replaceAll((k,v)->List.copyOf(v));this.owners=Map.copyOf(ownerMap);
            Map<Integer,List<ClassMemberRecord>> immutable=new HashMap<>();members.forEach((k,v)->immutable.put(k,List.copyOf(v)));
            this.members=Map.copyOf(immutable);this.ambiguous=Set.copyOf(ambiguous);
            Map<Integer,List<ClassMemberRecord>> source=new HashMap<>();
            this.members.forEach((id,values)->source.put(id,values.stream().filter(m->!JvmMethodResolver.flag(m,"bridge") && !JvmMethodResolver.flag(m,"synthetic")).toList()));
            sourceMembers=Map.copyOf(source);
            memberCount=members.values().stream().mapToInt(List::size).sum();
            Map<Integer,List<ClassRecord>> reversed=new HashMap<>();
            for(var child:classes) {
                if(owners.get(child.className()).size()!=1)continue;
                var parents=new ArrayList<>(child.interfaces());if(child.superclass()!=null)parents.add(child.superclass());
                for(String parent:parents) {var matches=owners.getOrDefault(parent,List.of());if(matches.size()==1) reversed.computeIfAbsent(matches.getFirst().id(),ignored->new ArrayList<>()).add(child);}
            }
            reversed.replaceAll((k,v)->List.copyOf(v));children=Map.copyOf(reversed);
        }
        private static <K,V> Map<K,V> bounded(int maximum) {
            return Collections.synchronizedMap(new LinkedHashMap<K,V>(16,.75f,true) {
                @Override protected boolean removeEldestEntry(Map.Entry<K,V> entry){return size()>maximum;}
            });
        }
        public List<Integer> aliases(String key,Supplier<List<Integer>> loader) {synchronized(aliases){var value=aliases.get(key);if(value!=null)return value;value=List.copyOf(loader.get());if(value.size()<=512)aliases.put(key,value);return value;}}
        public JvmMethodResolver.Resolution resolution(String key,Supplier<JvmMethodResolver.Resolution> loader) {synchronized(resolutions){var value=resolutions.get(key);if(value!=null)return value;value=loader.get();if(value.path().size()<=256)resolutions.put(key,value);return value;}}
        public String dispatch(String key,Supplier<String> loader) {synchronized(dispatch){String value=dispatch.get(key);if(value!=null)return value;value=loader.get();if(value.length()<=8192)dispatch.put(key,value);return value;}}
        public List<ClassRecord> descendants(ClassRecord owner) {
            synchronized(descendants){return descendants.computeIfAbsent(owner.id(),id->{
                if(owners.getOrDefault(owner.className(),List.of()).size()!=1)return List.of();
                var pending=new ArrayDeque<ClassRecord>();pending.add(owner);var seen=new HashSet<Integer>();var values=new ArrayList<ClassRecord>();
                while(!pending.isEmpty() && seen.size()<5000){var next=pending.removeFirst();if(!seen.add(next.id()))continue;values.add(next);pending.addAll(children.getOrDefault(next.id(),List.of()));}
                values.sort(Comparator.comparing(ClassRecord::className).thenComparingInt(ClassRecord::id));return List.copyOf(values);
            });}
        }
        public Map<Integer,List<String>> ancestry(ClassRecord owner) {
            synchronized(ancestry){var cached=ancestry.get(owner.id());if(cached!=null)return cached;
                int id=owner.id();
                if(owners.getOrDefault(owner.className(),List.of()).size()!=1)return Map.of();
                record Step(ClassRecord cls,List<String> path) {}
                var pending=new ArrayDeque<Step>();pending.add(new Step(owner,List.of(owner.className())));
                Map<Integer,List<String>> paths=new HashMap<>();
                while(!pending.isEmpty() && paths.size()<5000) {
                    var next=pending.removeFirst();if(paths.putIfAbsent(next.cls().id(),next.path())!=null)continue;
                    var parents=new ArrayList<>(next.cls().interfaces());if(next.cls().superclass()!=null)parents.add(next.cls().superclass());
                    for(String parent:parents){var matches=owners.getOrDefault(parent,List.of());if(matches.size()!=1)continue;
                        var path=new ArrayList<>(next.path());path.add(parent);pending.addLast(new Step(matches.getFirst(),List.copyOf(path)));}
                }
                var value=Map.copyOf(paths);
                if(paths.values().stream().mapToLong(List::size).sum()<=4096)ancestry.put(owner.id(),value);
                return value;
            }
        }
        public List<String> path(ClassRecord child,ClassRecord ancestor) {
            if(owners.getOrDefault(ancestor.className(),List.of()).size()!=1)return List.of();
            return ancestry(child).getOrDefault(ancestor.id(),List.of());
        }
    }
}
