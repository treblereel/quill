package org.treblereel.mcp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

/**
 * Explicit annotation members as JSON-friendly values: strings, numbers, booleans, enum
 * constant names, class names, nested annotation names and arrays of those. Long strings and
 * arrays are bounded so one annotation cannot dominate the index.
 */
public final class AnnotationValues {

    private static final int MAX_STRING = 200;
    private static final int MAX_ARRAY = 20;

    private AnnotationValues() {}

    /** Key listing the members whose value comes from the annotation type's default. */
    public static final String DEFAULTS_KEY = "$defaults";

    public static Map<String, Object> of(AnnotationInstance annotation) {
        return of(annotation, null);
    }

    /**
     * Explicit members, plus the defaults declared by the annotation type when {@code types}
     * indexes it (application or dependency JARs); defaulted members are listed under
     * {@link #DEFAULTS_KEY}. Without the type only explicit members are known.
     */
    public static Map<String, Object> of(AnnotationInstance annotation, IndexView types) {
        Map<String, Object> result = new TreeMap<>();
        for (AnnotationValue value : annotation.values()) {
            Object converted = convert(value);
            if (converted != null) result.put(value.name(), converted);
        }
        ClassInfo type = types == null ? null : types.getClassByName(annotation.name());
        if (type == null) return result;
        List<String> defaulted = new ArrayList<>();
        for (MethodInfo member : type.methods()) {
            if (result.containsKey(member.name()) || member.defaultValue() == null) continue;
            Object converted = convert(member.defaultValue());
            if (converted == null) continue;
            result.put(member.name(), converted);
            defaulted.add(member.name());
        }
        if (!defaulted.isEmpty()) result.put(DEFAULTS_KEY, defaulted.stream().sorted().toList());
        return result;
    }

    private static Object convert(AnnotationValue value) {
        return switch (value.kind()) {
            case BOOLEAN -> value.asBoolean();
            case BYTE, SHORT, INTEGER -> value.asInt();
            case LONG -> value.asLong();
            case FLOAT, DOUBLE -> value.asDouble();
            case CHARACTER -> String.valueOf(value.asChar());
            case STRING -> bounded(value.asString());
            case ENUM -> value.asEnum();
            case CLASS -> value.asClass().name().toString();
            case NESTED -> "@" + value.asNested().name();
            case ARRAY -> {
                List<Object> items = new ArrayList<>();
                for (AnnotationValue item : value.asArrayList()) {
                    if (items.size() >= MAX_ARRAY) break;
                    Object converted = convert(item);
                    if (converted != null) items.add(converted);
                }
                yield items;
            }
            default -> null;
        };
    }

    private static String bounded(String value) {
        return value.length() <= MAX_STRING ? value : value.substring(0, MAX_STRING) + "…";
    }
}
