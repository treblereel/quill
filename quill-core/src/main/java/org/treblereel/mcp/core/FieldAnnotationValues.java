package org.treblereel.mcp.core;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.lang.reflect.Array;
import java.util.*;
import java.util.regex.Pattern;
import org.objectweb.asm.*;
import java.util.function.*;
import java.nio.charset.StandardCharsets;

/** Explicit classfile literals only; annotation declaration defaults are not inferred. */
final class FieldAnnotationValues {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Pattern SENSITIVE=Pattern.compile("(?i)(^|[._$-])(password|passwd|secret|token|credentials?|private[._-]?key|api[._-]?key|access[._-]?key|pin)([._$-]|$)");
    private record AnnotationLiteral(String desc,Map<String,Object> values){}
    private record EnumLiteral(String desc,String value){}
    static FieldVisitor visitor(Consumer<ObjectNode> sink){
        List<AnnotationLiteral> annotations=new ArrayList<>();
        return new FieldVisitor(Opcodes.ASM9){
            @Override public AnnotationVisitor visitAnnotation(String descriptor,boolean visible){return annotation(descriptor,annotations::add);}
            @Override public void visitEnd(){sink.accept(capture(annotations));}
        };
    }
    private static AnnotationVisitor annotation(String descriptor,Consumer<AnnotationLiteral> sink){
        Map<String,Object> values=new TreeMap<>();return reader(values::put,()->sink.accept(new AnnotationLiteral(descriptor,values)));
    }
    private static AnnotationVisitor reader(BiConsumer<String,Object> sink,Runnable finish){
        return new AnnotationVisitor(Opcodes.ASM9){
            @Override public void visit(String name,Object value){sink.accept(name,value);}
            @Override public void visitEnum(String name,String descriptor,String value){sink.accept(name,new EnumLiteral(descriptor,value));}
            @Override public AnnotationVisitor visitAnnotation(String name,String descriptor){return annotation(descriptor,value->sink.accept(name,value));}
            @Override public AnnotationVisitor visitArray(String name){List<Object> values=new ArrayList<>();return reader((n,v)->values.add(v),()->sink.accept(name,values));}
            @Override public void visitEnd(){finish.run();}
        };
    }
    private static ObjectNode capture(List<AnnotationLiteral> annotations){
        var out=JSON.createObjectNode().put("version",1);var values=out.putObject("explicit");boolean[] complete={true};
        annotations.sort(Comparator.comparing(a->a.desc));int count=0;
        for(var annotation:annotations){
            if(++count>16){out.put("status","annotation_count_limit");complete[0]=false;break;}
            String name=Type.getType(annotation.desc).getClassName();
            if(values.has(name)){values.set(name,unknown("ambiguous_annotation",complete));continue;}
            values.set(name,attributes(annotation,0,complete));
        }
        if(out.toString().getBytes(StandardCharsets.UTF_8).length>8192){values.removeAll();out.put("status","annotation_field_limit");complete[0]=false;}
        return out.put("complete",complete[0]);
    }
    private static ObjectNode attributes(AnnotationLiteral annotation,int depth,boolean[] complete){
        if(depth>3)return unknown("annotation_depth_limit",complete);
        var out=JSON.createObjectNode();
        boolean sensitiveType=sensitive(Type.getType(annotation.desc).getClassName());boolean sensitiveKey=false;
        for(var entry:annotation.values.entrySet()){
            if((entry.getKey().equals("name") || entry.getKey().equals("value")) && entry.getValue() instanceof String key && sensitive(key))sensitiveKey=true;
        }
        int count=0;
        for(var entry:annotation.values.entrySet()){
            if(++count>16)return unknown("attribute_limit",complete);
            String name=entry.getKey();
            boolean redact=sensitiveType || sensitive(name) || sensitiveKey && (name.equals("defaultValue") || name.equals("value"));
            out.set(name,redact?unknown("redacted",complete):value(entry.getValue(),depth+1,complete));
        }
        return out;
    }
    private static JsonNode value(Object value,int depth,boolean[] complete){
        if(depth>4)return unknown("annotation_depth_limit",complete);
        if(value instanceof String s)return s.length()<=256?JSON.getNodeFactory().textNode(s):unknown("string_limit",complete);
        if(value instanceof Type type)return JSON.createObjectNode().put("$kind","class").put("name",type.getClassName());
        if(value instanceof EnumLiteral e)return JSON.createObjectNode().put("$kind","enum").put("type",Type.getType(e.desc).getClassName()).put("constant",e.value);
        if(value instanceof AnnotationLiteral nested)return JSON.createObjectNode().put("$kind","annotation").put("type",Type.getType(nested.desc).getClassName()).set("attributes",attributes(nested,depth,complete));
        if(value instanceof Character c)return JSON.createObjectNode().put("$kind","char").put("value",c.toString());
        if(value instanceof Float f && !Float.isFinite(f))return JSON.createObjectNode().put("$kind","float_bits").put("bits",Integer.toUnsignedString(Float.floatToRawIntBits(f)));
        if(value instanceof Double d && !Double.isFinite(d))return JSON.createObjectNode().put("$kind","double_bits").put("bits",Long.toUnsignedString(Double.doubleToRawLongBits(d)));
        if(value instanceof Boolean || value instanceof Number)return JSON.valueToTree(value);
        if(value instanceof List<?> list){var out=JSON.createArrayNode();for(int n=0;n<Math.min(list.size(),16);n++)out.add(value(list.get(n),depth+1,complete));if(list.size()>16)out.add(unknown("array_limit",complete));return out;}
        if(value!=null && value.getClass().isArray()){var out=JSON.createArrayNode();for(int n=0;n<Math.min(Array.getLength(value),16);n++)out.add(value(Array.get(value,n),depth+1,complete));if(Array.getLength(value)>16)out.add(unknown("array_limit",complete));return out;}
        return unknown("unsupported_annotation_value",complete);
    }
    private static boolean sensitive(String name){return SENSITIVE.matcher(name.replaceAll("([a-z])([A-Z])","$1.$2")).find();}
    private static ObjectNode unknown(String status,boolean[] complete){complete[0]=false;return JSON.createObjectNode().put("$status",status);}
    private FieldAnnotationValues(){}
}
