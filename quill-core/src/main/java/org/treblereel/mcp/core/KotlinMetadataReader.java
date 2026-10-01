package org.treblereel.mcp.core;

import java.util.ArrayList;
import java.util.List;
import kotlin.Metadata;
import kotlinx.metadata.Flag;
import kotlinx.metadata.KmDeclarationContainer;
import kotlinx.metadata.KmFunction;
import kotlinx.metadata.KmPackage;
import kotlinx.metadata.KmProperty;
import kotlinx.metadata.jvm.JvmExtensionsKt;
import kotlinx.metadata.jvm.JvmMethodSignature;
import kotlinx.metadata.jvm.KotlinClassHeader;
import kotlinx.metadata.jvm.KotlinClassMetadata;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

/** Best-effort Kotlin declaration model backed by the compiler's {@code kotlin.Metadata}. */
public final class KotlinMetadataReader {

    private static final DotName METADATA = DotName.createSimple("kotlin.Metadata");

    public enum Status {
        ABSENT,
        PARSED,
        UNSUPPORTED,
        INVALID
    }

    public enum Kind {
        CLASS,
        INTERFACE,
        ENUM,
        ANNOTATION,
        OBJECT,
        COMPANION_OBJECT,
        FILE_FACADE,
        MULTIFILE_FACADE,
        MULTIFILE_PART,
        SYNTHETIC,
        UNKNOWN
    }

    public record Function(
            String name, String jvmName, String descriptor, boolean suspend,
            boolean extension, boolean hasDefaultParameters, boolean synthesized) {}

    public record Property(
            String name, boolean mutable, boolean lateinit, boolean delegated,
            boolean extension, String fieldName, String fieldDescriptor,
            String getterName, String getterDescriptor,
            String setterName, String setterDescriptor) {}

    public record Result(
            Status status, Kind kind, String kotlinName, String metadataVersion,
            boolean data, boolean inner, boolean value, boolean funInterface,
            List<Function> functions, List<Property> properties, String detail) {
        public Result {
            functions = functions == null ? List.of() : List.copyOf(functions);
            properties = properties == null ? List.of() : List.copyOf(properties);
        }

        static Result absent() {
            return new Result(Status.ABSENT, Kind.UNKNOWN, null, null,
                    false, false, false, false, List.of(), List.of(), null);
        }
    }

    private KotlinMetadataReader() {}

    public static Result read(ClassInfo classInfo) {
        AnnotationInstance annotation = classInfo.declaredAnnotation(METADATA);
        if (annotation == null) return Result.absent();
        try {
            KotlinClassHeader header = new KotlinClassHeader(
                    integer(annotation, "k", 1), integers(annotation, "mv"),
                    strings(annotation, "d1"), strings(annotation, "d2"),
                    string(annotation, "xs", ""), string(annotation, "pn", ""),
                    integer(annotation, "xi", 0));
            return read(header);
        } catch (RuntimeException failure) {
            return invalid(Status.INVALID, null, failure);
        }
    }

    static Result read(Metadata header) {
        String version = version(header.mv());
        try {
            KotlinClassMetadata metadata = KotlinClassMetadata.readLenient(header);
            if (metadata == null) {
                return new Result(Status.UNSUPPORTED, Kind.UNKNOWN, null, version,
                        false, false, false, false, List.of(), List.of(),
                        "Unsupported Kotlin metadata kind " + header.k());
            }
            if (metadata instanceof KotlinClassMetadata.Class value) {
                var kmClass = value.getKmClass();
                int flags = kmClass.getFlags();
                return new Result(Status.PARSED, classKind(flags), normalize(kmClass.getName()),
                        version, Flag.Class.IS_DATA.invoke(flags),
                        Flag.Class.IS_INNER.invoke(flags),
                        Flag.Class.IS_VALUE.invoke(flags) || Flag.Class.IS_INLINE.invoke(flags),
                        Flag.Class.IS_FUN.invoke(flags), functions(kmClass), properties(kmClass), null);
            }
            if (metadata instanceof KotlinClassMetadata.FileFacade value) {
                return packageResult(Kind.FILE_FACADE, value.getKmPackage(), version, null);
            }
            if (metadata instanceof KotlinClassMetadata.MultiFileClassPart value) {
                return packageResult(Kind.MULTIFILE_PART, value.getKmPackage(), version,
                        value.getFacadeClassName());
            }
            if (metadata instanceof KotlinClassMetadata.MultiFileClassFacade value) {
                return new Result(Status.PARSED, Kind.MULTIFILE_FACADE, null, version,
                        false, false, false, false, List.of(), List.of(),
                        String.join(",", value.getPartClassNames()));
            }
            if (metadata instanceof KotlinClassMetadata.SyntheticClass) {
                return new Result(Status.PARSED, Kind.SYNTHETIC, null, version,
                        false, false, false, false, List.of(), List.of(), null);
            }
            return new Result(Status.UNSUPPORTED, Kind.UNKNOWN, null, version,
                    false, false, false, false, List.of(), List.of(),
                    "Unsupported Kotlin metadata representation "
                            + metadata.getClass().getSimpleName());
        } catch (IllegalArgumentException failure) {
            return invalid(Status.UNSUPPORTED, version, failure);
        } catch (RuntimeException failure) {
            return invalid(Status.INVALID, version, failure);
        }
    }

    private static Result packageResult(
            Kind kind, KmPackage value, String version, String detail) {
        return new Result(Status.PARSED, kind, null, version,
                false, false, false, false, functions(value), properties(value), detail);
    }

    private static List<Function> functions(KmDeclarationContainer container) {
        List<Function> result = new ArrayList<>();
        for (KmFunction function : container.getFunctions()) {
            int flags = function.getFlags();
            JvmMethodSignature signature = JvmExtensionsKt.getSignature(function);
            result.add(new Function(function.getName(),
                    signature == null ? null : signature.getName(),
                    signature == null ? null : signature.getDescriptor(),
                    Flag.Function.IS_SUSPEND.invoke(flags),
                    function.getReceiverParameterType() != null,
                    function.getValueParameters().stream().anyMatch(parameter ->
                            Flag.ValueParameter.DECLARES_DEFAULT_VALUE.invoke(parameter.getFlags())),
                    Flag.Function.IS_SYNTHESIZED.invoke(flags)));
        }
        return List.copyOf(result);
    }

    private static List<Property> properties(KmDeclarationContainer container) {
        List<Property> result = new ArrayList<>();
        for (KmProperty property : container.getProperties()) {
            int flags = property.getFlags();
            var field = JvmExtensionsKt.getFieldSignature(property);
            var getter = JvmExtensionsKt.getGetterSignature(property);
            var setter = JvmExtensionsKt.getSetterSignature(property);
            result.add(new Property(property.getName(), Flag.Property.IS_VAR.invoke(flags),
                    Flag.Property.IS_LATEINIT.invoke(flags),
                    Flag.Property.IS_DELEGATED.invoke(flags),
                    property.getReceiverParameterType() != null,
                    field == null ? null : field.getName(),
                    field == null ? null : field.getDescriptor(),
                    getter == null ? null : getter.getName(),
                    getter == null ? null : getter.getDescriptor(),
                    setter == null ? null : setter.getName(),
                    setter == null ? null : setter.getDescriptor()));
        }
        return List.copyOf(result);
    }

    private static Kind classKind(int flags) {
        if (Flag.Class.IS_COMPANION_OBJECT.invoke(flags)) return Kind.COMPANION_OBJECT;
        if (Flag.Class.IS_OBJECT.invoke(flags)) return Kind.OBJECT;
        if (Flag.Class.IS_ANNOTATION_CLASS.invoke(flags)) return Kind.ANNOTATION;
        if (Flag.Class.IS_ENUM_CLASS.invoke(flags)) return Kind.ENUM;
        if (Flag.Class.IS_INTERFACE.invoke(flags)) return Kind.INTERFACE;
        return Kind.CLASS;
    }

    private static Result invalid(Status status, String version, RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        return new Result(status, Kind.UNKNOWN, null, version,
                false, false, false, false, List.of(), List.of(), message);
    }

    private static int integer(AnnotationInstance annotation, String name, int fallback) {
        AnnotationValue value = annotation.value(name);
        return value == null ? fallback : value.asInt();
    }

    private static int[] integers(AnnotationInstance annotation, String name) {
        AnnotationValue value = annotation.value(name);
        return value == null ? new int[0] : value.asIntArray();
    }

    private static String string(
            AnnotationInstance annotation, String name, String fallback) {
        AnnotationValue value = annotation.value(name);
        return value == null ? fallback : value.asString();
    }

    private static String[] strings(AnnotationInstance annotation, String name) {
        AnnotationValue value = annotation.value(name);
        return value == null ? new String[0] : value.asStringArray();
    }

    private static String normalize(String internalName) {
        return internalName == null ? null : internalName.replace('/', '.');
    }

    private static String version(int[] version) {
        if (version == null || version.length == 0) return "unknown";
        StringBuilder result = new StringBuilder();
        for (int part : version) {
            if (!result.isEmpty()) result.append('.');
            result.append(part);
        }
        return result.toString();
    }
}
