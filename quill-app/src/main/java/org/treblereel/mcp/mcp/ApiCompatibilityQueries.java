package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.KotlinDeclarationRecord;
import org.treblereel.mcp.model.KotlinDeclarationTypes;
import org.treblereel.mcp.model.KotlinValueParameter;

/** Conservative declaration deltas, not a complete JVM linkage or source-compatibility proof. */
final class ApiCompatibilityQueries {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record OwnerKey(String name, String module, String sourceSet) implements Comparable<OwnerKey> {
        public int compareTo(OwnerKey other) { return Comparator.comparing(OwnerKey::name).thenComparing(OwnerKey::module)
                .thenComparing(OwnerKey::sourceSet).compare(this, other); }
    }
    private record MemberKey(String kind, String name, String descriptor) implements Comparable<MemberKey> {
        public int compareTo(MemberKey other) { return Comparator.comparing(MemberKey::kind).thenComparing(MemberKey::name)
                .thenComparing(MemberKey::descriptor).compare(this, other); }
    }
    private record Member(MemberKey key, String signature, Set<String> flags, String annotations, String types, boolean valid, boolean visibilityKnown) {
        boolean visible() { return flags.contains("public") || flags.contains("protected"); }
    }
    private record KotlinKey(String kind, String jvmName, String descriptor) implements Comparable<KotlinKey> {
        public int compareTo(KotlinKey other) { return Comparator.comparing(KotlinKey::kind).thenComparing(KotlinKey::jvmName)
                .thenComparing(KotlinKey::descriptor).compare(this, other); }
    }
    private static final class Owner {
        final int id; final OwnerKey key; final String kind, superclass, interfaces;
        final Map<MemberKey, Member> members = new TreeMap<>();
        final Map<KotlinKey, KotlinDeclarationRecord> kotlin = new TreeMap<>();
        final Set<String> annotations = new TreeSet<>();
        boolean ambiguous;
        Owner(int id, OwnerKey key, String kind, String superclass, String interfaces) {
            this.id=id; this.key=key; this.kind=kind; this.superclass=superclass; this.interfaces=interfaces;
        }
    }
    private record Finding(Owner before, Owner after, Member oldMember, Member newMember,
                           KotlinDeclarationRecord oldKotlin, KotlinDeclarationRecord newKotlin,
                           String reason, String assessment) {
        Finding(Owner before, Owner after, Member oldMember, Member newMember, String reason, String assessment) {
            this(before, after, oldMember, newMember, null, null, reason, assessment);
        }
    }

    static ObjectNode compare(Jdbi baseline, Jdbi current, int limit) {
        Map<OwnerKey, Owner> before = owners(baseline), after = owners(current);
        List<Finding> findings = new ArrayList<>();
        Set<OwnerKey> keys = new TreeSet<>(before.keySet()); keys.addAll(after.keySet());
        for (OwnerKey key : keys) {
            Owner old = before.get(key), now = after.get(key);
            if (old != null && old.ambiguous || now != null && now.ambiguous) {
                findings.add(new Finding(old,now,null,null,"AMBIGUOUS_OWNER","unknown")); continue;
            }
            if (old == null || now == null) {
                findings.add(new Finding(old,now,null,null,old == null ? "CLASS_ADDED" : "CLASS_REMOVED",
                        old == null ? "review_required" : "potentially_breaking")); continue;
            }
            if (!old.kind.equals(now.kind)) findings.add(new Finding(old,now,null,null,"CLASS_KIND_CHANGED","potentially_breaking"));
            if (!old.superclass.equals(now.superclass) || !old.interfaces.equals(now.interfaces))
                findings.add(new Finding(old,now,null,null,"HIERARCHY_CHANGED","potentially_breaking"));
            if (!old.annotations.equals(now.annotations)) findings.add(new Finding(old,now,null,null,"CLASS_ANNOTATIONS_CHANGED","review_required"));
            Set<MemberKey> members = new TreeSet<>(old.members.keySet()); members.addAll(now.members.keySet());
            for (MemberKey memberKey : members) {
                Member left = old.members.get(memberKey), right = now.members.get(memberKey);
                if (left != null && !left.valid || right != null && !right.valid) {
                    if (left != null && (left.visible() || !left.visibilityKnown) || right != null && (right.visible() || !right.visibilityKnown))
                        findings.add(new Finding(old,now,left,right,"MEMBER_EVIDENCE_INCOMPLETE","unknown"));
                    continue;
                }
                if ((left == null || !left.visible()) && (right == null || !right.visible())) continue;
                if (left == null) {
                    boolean abstractAdded = right.key.kind.equals("METHOD") && right.flags.contains("abstract") && !right.flags.contains("static");
                    findings.add(new Finding(old,now,null,right,abstractAdded ? "ABSTRACT_METHOD_ADDED" : "MEMBER_ADDED",
                            abstractAdded ? "potentially_breaking" : "review_required")); continue;
                }
                if (right == null) { findings.add(new Finding(old,now,left,null,"MEMBER_REMOVED_OR_DESCRIPTOR_CHANGED","potentially_breaking")); continue; }
                if (access(right.flags) < access(left.flags)) findings.add(new Finding(old,now,left,right,"VISIBILITY_NARROWED","potentially_breaking"));
                else if (access(right.flags) > access(left.flags)) findings.add(new Finding(old,now,left,right,"VISIBILITY_WIDENED","review_required"));
                if (left.flags.contains("static") != right.flags.contains("static")) findings.add(new Finding(old,now,left,right,"STATIC_INSTANCE_CHANGED","potentially_breaking"));
                if (!left.flags.contains("final") && right.flags.contains("final")) findings.add(new Finding(old,now,left,right,"FINAL_ADDED","potentially_breaking"));
                if (!left.flags.contains("abstract") && right.flags.contains("abstract")) findings.add(new Finding(old,now,left,right,"ABSTRACT_ADDED","potentially_breaking"));
                if (!left.signature.equals(right.signature) || !left.types.equals(right.types))
                    findings.add(new Finding(old,now,left,right,"SOURCE_SIGNATURE_CHANGED","review_required"));
                if (!left.annotations.equals(right.annotations)) findings.add(new Finding(old,now,left,right,"ANNOTATIONS_CHANGED","review_required"));
                Set<String> ignored = Set.of("public","protected","private","static","final","abstract");
                Set<String> leftOther = new TreeSet<>(left.flags), rightOther = new TreeSet<>(right.flags);
                leftOther.removeAll(ignored); rightOther.removeAll(ignored);
                if (!leftOther.equals(rightOther) || left.flags.contains("final") && !right.flags.contains("final")
                        || left.flags.contains("abstract") && !right.flags.contains("abstract"))
                    findings.add(new Finding(old,now,left,right,"OTHER_MODIFIERS_CHANGED","review_required"));
            }
            compareKotlin(old, now, findings);
        }
        ObjectNode root = JSON.createObjectNode();
        long breaks = findings.stream().filter(f -> f.assessment.equals("potentially_breaking")).count();
        long unknown = findings.stream().filter(f -> f.assessment.equals("unknown")).count();
        root.put("verdict", breaks > 0 ? "potentially_breaking" : unknown > 0 ? "unknown" : findings.isEmpty() ? "no_detected_changes" : "review_required");
        root.put("scope","indexed_main_and_legacy_unspecified_classes_public_protected_declared_jvm_members");
        root.put("kotlin_metadata_scope","matched_public_protected_jvm_declarations");
        root.put("compatibility_proven",false); root.put("baseline_class_count",before.size()); root.put("current_class_count",after.size());
        root.put("finding_count",findings.size()); root.put("potentially_breaking_count",breaks); root.put("unknown_count",unknown);
        root.put("review_required_count",findings.size()-breaks-unknown);
        root.put("truncated",findings.size()>limit);
        var details=root.putArray("findings");
        Map<String,List<ObjectNode>> subtypes = directSubtypes(baseline);
        Map<String,ObjectNode> impacts = new HashMap<>();
        for (Finding finding : findings.stream().limit(Math.max(0,limit)).toList()) {
            var node=details.addObject(); Owner owner=finding.before != null ? finding.before : finding.after;
            node.put("class_name",owner.key.name); node.put("module",owner.key.module); node.put("source_set",owner.key.sourceSet);
            node.put("reason",finding.reason); node.put("assessment",finding.assessment);
            if (finding.oldMember == null && finding.newMember == null) {
                if (finding.before != null) appendOwner(node.putObject("before_class"),finding.before);
                if (finding.after != null) appendOwner(node.putObject("after_class"),finding.after);
            }
            if (finding.oldMember != null) appendMember(node.putObject("before"),finding.oldMember);
            if (finding.newMember != null) appendMember(node.putObject("after"),finding.newMember);
            if (finding.oldKotlin != null) appendKotlin(node.putObject("before_kotlin"),finding.oldKotlin);
            if (finding.newKotlin != null) appendKotlin(node.putObject("after_kotlin"),finding.newKotlin);
            String impactKey=finding.before==null?"absent:"+owner.key:"baseline:"+owner.id+":"+(finding.oldMember==null?"class":finding.oldMember.key.toString());
            node.set("baseline_impact",impacts.computeIfAbsent(impactKey,ignored->{
                var impact=JSON.createObjectNode();appendImpact(impact,baseline,finding,Math.max(0,limit),subtypes);return impact;
            }));
        }
        root.putArray("limitations")
                .add("This is a declaration review, not a binary/source compatibility proof or a release approval")
                .add("Class access/final/abstract flags, constants, exceptions, bridge/synthetic methods and complete generic signatures are not retained")
                .add("Inherited member lookup and replacement artifacts can preserve linkage after a declared member disappears")
                .add("Kotlin checks cover retained metadata declarations with the same JVM identity; source visibility, inline bodies and missing metadata remain unknown")
                .add("Member additions can affect overload resolution; source compatibility requires recompiling consumers")
                .add("Impact uses baseline indexed static callers, field accesses, subclasses and dependencies; external consumers and inherited symbolic-owner references are unknown")
                .add("Test and custom source sets are excluded; legacy unspecified source sets may contain tests")
                .add("Missing class visibility means public/protected members of non-public owners may be included");
        return root;
    }
    private static void appendOwner(ObjectNode node,Owner owner) {
        node.put("kind",owner.kind);node.put("superclass",owner.superclass);
        node.put("interfaces",owner.interfaces);node.set("annotation_markers",JSON.valueToTree(owner.annotations));
    }
    private static void appendMember(ObjectNode node, Member member) {
        node.put("kind",member.key.kind); node.put("name",member.key.name); node.put("descriptor",member.key.descriptor);
        node.put("signature",member.signature); node.set("modifiers",JSON.valueToTree(new TreeSet<>(member.flags)));
    }
    private static void appendKotlin(ObjectNode node, KotlinDeclarationRecord declaration) {
        node.put("kind", declaration.kind()).put("name", declaration.name());
        node.put("jvm_name", declaration.jvmName()).put("descriptor", declaration.descriptor());
        node.put("suspend", declaration.isSuspend()).put("extension", declaration.extension());
        node.put("has_default_parameters", declaration.hasDefaultParameters());
        node.put("mutable", declaration.mutable()).put("lateinit", declaration.lateinit());
        node.put("delegated", declaration.delegated());
        if (declaration.valueParameters() != null) node.set("value_parameters", JSON.valueToTree(declaration.valueParameters()));
        if (declaration.declarationTypes() != null) node.set("source_types", JSON.valueToTree(declaration.declarationTypes()));
    }
    private static void compareKotlin(Owner old, Owner now, List<Finding> findings) {
        Set<String> comparedProperties = new HashSet<>();
        for (var entry : old.kotlin.entrySet()) {
            KotlinDeclarationRecord left = entry.getValue(), right = now.kotlin.get(entry.getKey());
            // JVM removals have their own finding. Source checks require matched metadata on both sides.
            if (right == null || !visibleKotlin(old, left)) continue;
            if (left.kind().equals("PROPERTY") && !comparedProperties.add(left.name())) continue;
            if (!left.name().equals(right.name())) kotlinFinding(findings, old, now, left, right,
                    "KOTLIN_NAME_CHANGED", "potentially_breaking");
            if (left.isSuspend() != right.isSuspend() || left.extension() != right.extension())
                kotlinFinding(findings, old, now, left, right, "KOTLIN_CALL_CONVENTION_CHANGED", "potentially_breaking");
            List<KotlinValueParameter> oldParameters = left.valueParameters(), newParameters = right.valueParameters();
            if (oldParameters != null && newParameters != null) {
                if (oldParameters.size() != newParameters.size())
                    kotlinFinding(findings, old, now, left, right, "KOTLIN_PARAMETERS_CHANGED", "potentially_breaking");
                else for (int i = 0; i < oldParameters.size(); i++) {
                    var before = oldParameters.get(i); var after = newParameters.get(i);
                    if (!before.name().equals(after.name()) || before.vararg() != after.vararg())
                        kotlinFinding(findings, old, now, left, right, "KOTLIN_PARAMETERS_CHANGED", "potentially_breaking");
                    if (before.hasDefault() && !after.hasDefault())
                        kotlinFinding(findings, old, now, left, right, "KOTLIN_DEFAULT_REMOVED", "potentially_breaking");
                    else if (!before.hasDefault() && after.hasDefault())
                        kotlinFinding(findings, old, now, left, right, "KOTLIN_DEFAULT_ADDED", "review_required");
                }
            } else if (left.hasDefaultParameters() != right.hasDefaultParameters())
                kotlinFinding(findings, old, now, left, right, "KOTLIN_PARAMETER_EVIDENCE_INCOMPLETE", "unknown");
            if (left.mutable() && !right.mutable())
                kotlinFinding(findings, old, now, left, right, "KOTLIN_PROPERTY_BECAME_READ_ONLY", "potentially_breaking");
            else if (!left.mutable() && right.mutable())
                kotlinFinding(findings, old, now, left, right, "KOTLIN_PROPERTY_BECAME_MUTABLE", "review_required");
            if (left.lateinit() != right.lateinit() || left.delegated() != right.delegated())
                kotlinFinding(findings, old, now, left, right, "KOTLIN_PROPERTY_SEMANTICS_CHANGED", "review_required");
            KotlinDeclarationTypes beforeTypes = left.declarationTypes(), afterTypes = right.declarationTypes();
            if (beforeTypes != null && afterTypes != null && !beforeTypes.equals(afterTypes))
                kotlinFinding(findings, old, now, left, right,
                        unsafeNullability(beforeTypes, afterTypes) ? "KOTLIN_NULLABILITY_WEAKENED" : "KOTLIN_SOURCE_TYPES_CHANGED",
                        unsafeNullability(beforeTypes, afterTypes) ? "potentially_breaking" : "review_required");
        }
    }
    private static boolean visibleKotlin(Owner owner, KotlinDeclarationRecord declaration) {
        Member member = memberForKotlin(owner, declaration);
        return member != null && member.valid && member.visible();
    }
    private static Member memberForKotlin(Owner owner, KotlinDeclarationRecord declaration) {
        String name = declaration.jvmName();
        if (name == null || name.isBlank()) return null;
        String kind = declaration.kind().equals("CONSTRUCTOR") ? "CONSTRUCTOR" : "METHOD";
        Member member = owner.members.get(new MemberKey(kind, name, declaration.descriptor()));
        if (member == null && declaration.kind().equals("PROPERTY"))
            member = owner.members.get(new MemberKey("FIELD", name, declaration.descriptor()));
        return member;
    }
    private static boolean unsafeNullability(KotlinDeclarationTypes before, KotlinDeclarationTypes after) {
        if (before.result() != null && after.result() != null && !before.result().nullable() && after.result().nullable()) return true;
        if (before.receiver() != null && after.receiver() != null && before.receiver().nullable() && !after.receiver().nullable()) return true;
        if (before.parameters().size() != after.parameters().size()) return false;
        for (int i = 0; i < before.parameters().size(); i++) {
            if (before.parameters().get(i).type().nullable() && !after.parameters().get(i).type().nullable()) return true;
        }
        return false;
    }
    private static void kotlinFinding(List<Finding> findings, Owner old, Owner now,
            KotlinDeclarationRecord left, KotlinDeclarationRecord right, String reason, String assessment) {
        if (findings.stream().anyMatch(f -> f.oldKotlin == left && f.reason.equals(reason))) return;
        findings.add(new Finding(old, now, memberForKotlin(old, left), memberForKotlin(now, right),
                left, right, reason, assessment));
    }
    private static Map<OwnerKey,Owner> owners(Jdbi db) {
        return db.withHandle(h -> {
            Map<OwnerKey,Owner> result=new TreeMap<>(); Map<Integer,Owner> ids=new HashMap<>();
            h.createQuery("""
                    SELECT id,class_name,kind,superclass,interfaces,module,source_set FROM classes
                    WHERE lifecycle='current' AND origin!='orphan_output' AND (source_set='main' OR source_set IS NULL)
                    ORDER BY class_name,module,source_set,id
                    """).map((r,c) -> new Owner(r.getInt("id"),new OwnerKey(r.getString("class_name"),value(r.getString("module")),value(r.getString("source_set"))),
                        r.getString("kind"),value(r.getString("superclass")),canonical(r.getString("interfaces"),true)))
                    .forEach(owner -> { Owner existing=result.putIfAbsent(owner.key,owner); if(existing!=null) {existing.ambiguous=true;owner.ambiguous=true;} ids.put(owner.id,existing==null?owner:existing); });
            h.createQuery("SELECT class_id,COUNT(DISTINCT module) AS n FROM class_occurrences GROUP BY class_id HAVING n>1")
                    .map((r,c)->r.getInt("class_id")).forEach(id->{if(ids.containsKey(id))ids.get(id).ambiguous=true;});
            h.createQuery("SELECT class_id,kind,name,descriptor,signature,modifiers,annotations,annotation_details,type_name,parameter_types FROM class_members ORDER BY class_id,kind,name,descriptor,signature")
                    .map((r,c) -> Map.entry(r.getInt("class_id"),member(r.getString("kind"),r.getString("name"),r.getString("descriptor"),r.getString("signature"),
                            r.getString("modifiers"),r.getString("annotations"),r.getString("annotation_details"),r.getString("type_name"),r.getString("parameter_types"))))
                    .forEach(entry -> {Owner owner=ids.get(entry.getKey());if(owner!=null && owner.members.putIfAbsent(entry.getValue().key,entry.getValue())!=null)owner.ambiguous=true;});
            h.createQuery("SELECT class_id,annotation_name,direct,via_annotation FROM class_annotations ORDER BY class_id,annotation_name,direct,via_annotation")
                    .map((r,c)->Map.entry(r.getInt("class_id"),r.getString("annotation_name")+"|"+r.getInt("direct")+"|"+value(r.getString("via_annotation"))))
                    .forEach(entry->{if(ids.containsKey(entry.getKey()))ids.get(entry.getKey()).annotations.add(entry.getValue());});
            h.createQuery("""
                    SELECT class_id,kind,name,jvm_name,descriptor,semantic_model,is_suspend,is_extension,
                           has_default_parameters,is_mutable,is_lateinit,is_delegated,is_synthetic,value_parameters,declaration_types
                    FROM kotlin_declarations ORDER BY class_id,kind,name,jvm_name,descriptor
                    """).map((r,c) -> new KotlinDeclarationRecord(r.getInt("class_id"), r.getString("kind"), r.getString("name"),
                            r.getString("jvm_name"), r.getString("descriptor"), r.getString("semantic_model"),
                            r.getInt("is_suspend") == 1, r.getInt("is_extension") == 1,
                            r.getInt("has_default_parameters") == 1, r.getInt("is_mutable") == 1,
                            r.getInt("is_lateinit") == 1, r.getInt("is_delegated") == 1,
                            r.getInt("is_synthetic") == 1, IndexReader.kotlinParametersFromJson(r.getString("value_parameters")),
                            IndexReader.kotlinTypesFromJson(r.getString("declaration_types"))))
                    .forEach(declaration -> {
                        Owner owner = ids.get(declaration.classId());
                        if (owner == null || declaration.synthetic() || !"kotlin_metadata".equals(declaration.semanticModel())
                                || !Set.of("FUNCTION", "PROPERTY", "CONSTRUCTOR").contains(declaration.kind())) return;
                        String jvmName = declaration.jvmName() == null || declaration.jvmName().isBlank()
                                ? "source:" + declaration.name() : declaration.jvmName();
                        KotlinKey key = new KotlinKey(declaration.kind(), jvmName, value(declaration.descriptor()));
                        if (owner.kotlin.putIfAbsent(key, declaration) != null) owner.ambiguous = true;
                    });
            return result;
        });
    }
    private static Member member(String kind,String name,String descriptor,String signature,String modifiers,String annotations,String details,String type,String parameters) {
        Set<String> flags=new TreeSet<>();
        for(String token:value(modifiers).replaceAll("[\\[\\],]"," ").split("\\s+")) if(!token.isEmpty()) flags.add(token);
        Set<String> supported=Set.of("public","private","protected","static","final","abstract","native","synchronized","strictfp","transient","volatile");
        long access=flags.stream().filter(Set.of("public","private","protected")::contains).count();
        boolean valid=validDescriptor(kind,descriptor) && supported.containsAll(flags) && access<=1;
        String canonicalName=kind.equals("CONSTRUCTOR")?"<init>":name;
        // Empty descriptors must not accidentally merge unrelated legacy declarations.
        return new Member(new MemberKey(kind,canonicalName,valid?descriptor:"unknown:"+value(descriptor)+":"+signature),signature,flags,
                canonical(annotations,true)+"|"+canonical(details,true),value(type)+"|"+canonical(parameters,false),valid,supported.containsAll(flags) && access<=1);
    }
    private static boolean validDescriptor(String kind,String value) {
        if(value==null || value.isEmpty())return false;
        try {
            if(kind.equals("FIELD"))return typeEnd(value,0,false)==value.length();
            if(!Set.of("METHOD","CONSTRUCTOR").contains(kind) || value.charAt(0)!='(')return false;
            int cursor=1;
            while(cursor<value.length() && value.charAt(cursor)!=')')cursor=typeEnd(value,cursor,false);
            if(cursor>=value.length())return false;
            int result=cursor+1;
            return typeEnd(value,result,true)==value.length() && (!kind.equals("CONSTRUCTOR") || value.substring(result).equals("V"));
        } catch(IllegalArgumentException malformed){return false;}
    }
    private static int typeEnd(String value,int offset,boolean allowVoid) {
        if(offset>=value.length())throw new IllegalArgumentException();
        char kind=value.charAt(offset);
        if("BCDFIJSZ".indexOf(kind)>=0 || kind=='V' && allowVoid)return offset+1;
        if(kind=='[') {
            int cursor=offset;
            while(cursor<value.length() && value.charAt(cursor)=='[')cursor++;
            if(cursor-offset>255)throw new IllegalArgumentException();
            return typeEnd(value,cursor,false);
        }
        if(kind=='L') {
            int end=value.indexOf(';',offset);
            if(end<0)throw new IllegalArgumentException();
            String name=value.substring(offset+1,end);
            if(name.isEmpty() || name.contains(".") || name.contains("[") || name.startsWith("/") || name.endsWith("/") || name.contains("//"))throw new IllegalArgumentException();
            return end+1;
        }
        throw new IllegalArgumentException();
    }
    private static int access(Set<String> flags) {return flags.contains("public")?3:flags.contains("protected")?2:flags.contains("private")?0:1;}
    private static String value(String value) {return value==null?"":value;}
    private static String canonical(String value,boolean unordered) {
        if(value==null)return "";
        try {
            JsonNode node=orderedObjects(JSON.readTree(value));
            if(unordered && node.isArray()) {List<String> entries=new ArrayList<>();node.forEach(n->entries.add(n.toString()));Collections.sort(entries);return entries.toString();}
            return node.toString();
        } catch(Exception malformed){return "unparsed:"+value;}
    }
    private static JsonNode orderedObjects(JsonNode node) {
        if(node.isObject()) {
            ObjectNode result=JSON.createObjectNode();Set<String> fields=new TreeSet<>();node.fieldNames().forEachRemaining(fields::add);
            fields.forEach(key->result.set(key,orderedObjects(node.get(key))));return result;
        }
        if(node.isArray()) {var result=JSON.createArrayNode();node.forEach(value->result.add(orderedObjects(value)));return result;}
        return node;
    }
    private static void appendImpact(ObjectNode node,Jdbi baseline,Finding finding,int limit,Map<String,List<ObjectNode>> subtypeMap) {
        node.put("scope","baseline_static_evidence"); node.put("complete",false); node.put("external_consumers_known",false);
        if(finding.before==null || finding.before.ambiguous) {node.put("status","unavailable"); return;}
        Owner owner=finding.before;
        baseline.useHandle(h -> {
            String sql;Map<String,Object> bindings;
            if(finding.oldMember!=null && finding.oldMember.valid) {
                Member member=finding.oldMember;
                sql=member.key.kind.equals("FIELD") ? """
                    SELECT DISTINCT c.id,c.class_name,c.module,c.source_set,a.from_method,a.from_descriptor FROM field_accesses a JOIN classes c ON c.id=a.from_class_id
                    WHERE a.to_class_id=:id AND a.field_name=:name AND a.field_descriptor=:descriptor AND c.lifecycle='current' AND c.origin!='orphan_output'
                    """ : """
                    SELECT DISTINCT c.id,c.class_name,c.module,c.source_set,a.from_method,a.from_descriptor FROM method_calls a JOIN classes c ON c.id=a.from_class_id
                    WHERE a.to_class_id=:id AND a.to_method=:name AND a.to_descriptor=:descriptor AND c.lifecycle='current' AND c.origin!='orphan_output'
                    """;
                bindings=Map.of("id",owner.id,"name",member.key.name,"descriptor",member.key.descriptor);
                node.put("reference_matching","exact_owner_name_descriptor");
            } else {
                sql="""
                    SELECT DISTINCT c.id,c.class_name,c.module,c.source_set,NULL AS from_method,NULL AS from_descriptor FROM dependencies d JOIN classes c ON c.id=d.from_class_id
                    WHERE d.to_class_id=:id AND c.lifecycle='current' AND c.origin!='orphan_output'
                    """;
                bindings=Map.of("id",owner.id);node.put("reference_matching","class_dependency");
            }
            long count=h.createQuery("SELECT COUNT(*) FROM ("+sql+")").bindMap(bindings).mapTo(Long.class).one();
            List<ObjectNode> consumers=h.createQuery("SELECT * FROM ("+sql+") ORDER BY class_name,module,source_set,id,from_method,from_descriptor LIMIT :detail_limit")
                    .bindMap(bindings).bind("detail_limit",limit).map((r,c)->consumer(r)).list();
            node.put("consumer_count",count);node.set("consumers",JSON.valueToTree(consumers));node.put("consumers_truncated",count>limit);
            // Subclasses matter for final/abstract changes even when no call instruction exists.
            List<ObjectNode> subtypes=subtypeMap.getOrDefault(owner.key.name,List.of());
            node.put("direct_subtype_count",subtypes.size());node.set("direct_subtypes",JSON.valueToTree(subtypes.stream().limit(limit).toList()));node.put("direct_subtypes_truncated",subtypes.size()>limit);
        });
    }
    private static ObjectNode consumer(java.sql.ResultSet row) throws java.sql.SQLException {
        ObjectNode node=JSON.createObjectNode().put("baseline_class_id",row.getInt("id")).put("class_name",row.getString("class_name"));
        node.put("module",value(row.getString("module")));node.put("source_set",value(row.getString("source_set")));
        if(row.getString("from_method")!=null)node.put("method",row.getString("from_method")).put("descriptor",row.getString("from_descriptor"));
        return node;
    }
    private static Map<String,List<ObjectNode>> directSubtypes(Jdbi db) {
        Map<String,List<ObjectNode>> result=new HashMap<>();
        db.useHandle(h->h.createQuery("SELECT id,class_name,module,source_set,superclass,interfaces,NULL AS from_method,NULL AS from_descriptor FROM classes WHERE lifecycle='current' AND origin!='orphan_output' ORDER BY class_name,module,source_set,id")
                .map((r,c)->{
                    ObjectNode node=consumer(r);Set<String> parents=new TreeSet<>();
                    String superclass=r.getString("superclass");if(superclass!=null && !superclass.isBlank())parents.add(superclass);
                    try {JsonNode interfaces=JSON.readTree(r.getString("interfaces"));if(interfaces!=null && interfaces.isArray())interfaces.forEach(v->parents.add(v.asText()));}catch(Exception ignored){}
                    for(String parent:parents)result.computeIfAbsent(parent,ignored->new ArrayList<>()).add(node);
                    return node;
                }).list());
        return result;
    }
}
