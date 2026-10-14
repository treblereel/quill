package org.treblereel.mcp.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.objectweb.asm.*;

/** Immutable JVM API evidence from the same class-file snapshot as the dependency graph. */
public final class JvmApiScanner {
    private static final ObjectMapper JSON=new ObjectMapper();
    private JvmApiScanner() {}
    public static Map<Integer,String> capture(ClassFileSnapshot snapshot,Map<String,Integer> ids) {
        Map<Integer,String> result=new TreeMap<>();
        Set<Integer> duplicates=new HashSet<>();
        for(var entry:snapshot.entries()) {
            var reader=new ClassReader(entry.bytecode());
            Integer id=ids.get(reader.getClassName().replace('/','.')); if(id==null) continue;
            if(result.containsKey(id)) {duplicates.add(id); continue;}
            ObjectNode evidence=JSON.createObjectNode().put("schema_version",1).put("status","captured");
            var members=evidence.putArray("members");
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public void visit(int version,int access,String name,String signature,String parent,String[] interfaces) {
                    evidence.put("access_flags",access); evidence.put("generic_signature",signature);
                }
                @Override public void visitInnerClass(String name,String outer,String inner,int access) {
                    if(name.equals(reader.getClassName())) evidence.put("access_flags",access);
                }
                @Override public FieldVisitor visitField(int access,String name,String descriptor,String signature,Object constant) {
                    var field=members.addObject().put("kind","FIELD").put("name",name).put("descriptor",descriptor)
                            .put("access_flags",access).put("generic_signature",signature);
                    if(constant==null) field.putNull("constant");
                    else {
                        var value=field.putObject("constant").put("descriptor",descriptor);
                        if(constant instanceof Float f) value.put("bits",Integer.toUnsignedString(Float.floatToRawIntBits(f)));
                        else if(constant instanceof Double d) value.put("bits",Long.toUnsignedString(Double.doubleToRawLongBits(d)));
                        else value.put("value",constant.toString());
                    }
                    return null;
                }
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if(name.equals("<clinit>")) return null;
                    var method=members.addObject().put("kind",name.equals("<init>")?"CONSTRUCTOR":"METHOD")
                            .put("name",name).put("descriptor",descriptor).put("access_flags",access).put("generic_signature",signature);
                    var thrown=method.putArray("exceptions");
                    if(exceptions!=null) Arrays.stream(exceptions).map(n->n.replace('/','.')).sorted().forEach(thrown::add);
                    return null;
                }
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            var ordered=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();members.forEach(ordered::add);
            ordered.sort(Comparator.comparing((com.fasterxml.jackson.databind.JsonNode m)->m.path("kind").asText())
                    .thenComparing(m->m.path("name").asText()).thenComparing(m->m.path("descriptor").asText()));
            members.removeAll(); ordered.forEach(members::add);
            result.put(id,evidence.toString());
        }
        for(int id:duplicates) result.put(id,JSON.createObjectNode().put("schema_version",1).put("status","ambiguous_bytecode_owner").toString());
        return Map.copyOf(result);
    }
}
