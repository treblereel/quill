package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kotlinx.metadata.Flag;
import kotlinx.metadata.FlagsKt;
import kotlinx.metadata.KmClass;
import kotlinx.metadata.KmConstructor;
import java.util.List;
import org.treblereel.mcp.model.KotlinValueParameter;
import kotlinx.metadata.KmClassifier;
import kotlinx.metadata.KmFunction;
import kotlinx.metadata.KmProperty;
import kotlinx.metadata.KmType;
import kotlinx.metadata.KmValueParameter;
import kotlinx.metadata.jvm.JvmExtensionsKt;
import kotlinx.metadata.jvm.JvmFieldSignature;
import kotlinx.metadata.jvm.JvmMethodSignature;
import kotlinx.metadata.jvm.KotlinClassHeader;
import kotlinx.metadata.jvm.KotlinClassMetadata;
import org.junit.jupiter.api.Test;

class KotlinMetadataReaderTest {

    @Test
    void readsClassFunctionsPropertiesAndKotlinFlags() {
        KmClass type = new KmClass();
        type.setName("org/acme/Order");
        type.setFlags(FlagsKt.flagsOf(Flag.Class.IS_CLASS, Flag.Class.IS_DATA));

        KmFunction load = new KmFunction("load");
        load.setFlags(FlagsKt.flagsOf(
                Flag.Function.IS_DECLARATION, Flag.Function.IS_SUSPEND));
        load.setReturnType(type("org/acme/Order"));
        load.setReceiverParameterType(type("org/acme/Repository"));
        KmValueParameter limit = new KmValueParameter("limit");
        limit.setFlags(FlagsKt.flagsOf(Flag.ValueParameter.DECLARES_DEFAULT_VALUE));
        limit.setType(type("kotlin/Int"));
        load.getValueParameters().add(limit);
        JvmExtensionsKt.setSignature(load,
                new JvmMethodSignature("load", "(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;"));
        type.getFunctions().add(load);

        KmProperty state = new KmProperty("state");
        state.setFlags(FlagsKt.flagsOf(
                Flag.Property.IS_DECLARATION, Flag.Property.IS_VAR,
                Flag.Property.IS_LATEINIT));
        state.setReturnType(type("kotlin/String"));
        JvmExtensionsKt.setFieldSignature(state,
                new JvmFieldSignature("state", "Ljava/lang/String;"));
        JvmExtensionsKt.setGetterSignature(state,
                new JvmMethodSignature("getState", "()Ljava/lang/String;"));
        JvmExtensionsKt.setSetterSignature(state,
                new JvmMethodSignature("setState", "(Ljava/lang/String;)V"));
        type.getProperties().add(state);

        var result = KotlinMetadataReader.read(KotlinClassMetadata.writeClass(type));

        assertEquals(KotlinMetadataReader.Status.PARSED, result.status());
        assertEquals(KotlinMetadataReader.Kind.CLASS, result.kind());
        assertEquals("org.acme.Order", result.kotlinName());
        assertTrue(result.data());
        var function = result.functions().getFirst();
        assertEquals("load", function.name());
        assertEquals("load", function.jvmName());
        assertTrue(function.suspend());
        assertTrue(function.extension());
        assertTrue(function.hasDefaultParameters());
        assertEquals(List.of(new KotlinValueParameter("limit", true, false)), function.valueParameters());
        var property = result.properties().getFirst();
        assertEquals("state", property.name());
        assertTrue(property.mutable());
        assertTrue(property.lateinit());
        assertEquals("getState", property.getterName());
        assertEquals("()Ljava/lang/String;", property.getterDescriptor());
        assertEquals("setState", property.setterName());
        assertEquals("(Ljava/lang/String;)V", property.setterDescriptor());
        assertEquals("Ljava/lang/String;", property.fieldDescriptor());
    }

    @Test
    void reportsUnknownMetadataKindWithoutThrowing() {
        KotlinClassHeader header = new KotlinClassHeader(
                99, new int[]{1, 9, 0}, new String[0], new String[0], "", "", 0);

        var result = KotlinMetadataReader.read(header);

        assertEquals(KotlinMetadataReader.Status.UNSUPPORTED, result.status());
        assertEquals(KotlinMetadataReader.Kind.UNKNOWN, result.kind());
        assertNotNull(result.detail());
        assertFalse(result.detail().isBlank());
    }

    @Test
    void preservesValueClassManglingAndDelegatedPropertyFlags() {
        KmClass type = new KmClass();
        type.setName("org/acme/InvoiceId");
        type.setFlags(FlagsKt.flagsOf(Flag.Class.IS_CLASS, Flag.Class.IS_VALUE));

        KmFunction load = new KmFunction("loadInvoice");
        load.setFlags(FlagsKt.flagsOf(Flag.Function.IS_DECLARATION));
        load.setReturnType(type("org/acme/Invoice"));
        JvmExtensionsKt.setSignature(load, new JvmMethodSignature(
                "loadInvoice-soC6T9M", "(Ljava/lang/String;)Lorg/acme/Invoice;"));
        type.getFunctions().add(load);

        KmProperty delegated = new KmProperty("cached");
        delegated.setFlags(FlagsKt.flagsOf(
                Flag.Property.IS_DECLARATION, Flag.Property.IS_DELEGATED));
        delegated.setReturnType(type("kotlin/String"));
        JvmExtensionsKt.setFieldSignature(delegated,
                new JvmFieldSignature("cached$delegate", "Lkotlin/Lazy;"));
        JvmExtensionsKt.setGetterSignature(delegated,
                new JvmMethodSignature("getCached", "()Ljava/lang/String;"));
        type.getProperties().add(delegated);

        var result = KotlinMetadataReader.read(KotlinClassMetadata.writeClass(type));

        assertTrue(result.value());
        assertEquals("loadInvoice", result.functions().getFirst().name());
        assertEquals("loadInvoice-soC6T9M", result.functions().getFirst().jvmName());
        assertTrue(result.properties().getFirst().delegated());
        assertEquals("cached$delegate", result.properties().getFirst().fieldName());
        assertEquals("getCached", result.properties().getFirst().getterName());
    }

    @Test
    void preservesParameterOrderVarargAndRequiredParametersOnFunctionsAndConstructors() {
        KmClass cls = new KmClass(); cls.setName("acme/Owner");
        KmValueParameter optional = new KmValueParameter("optional");
        optional.setType(type("kotlin/Int"));
        optional.setFlags(FlagsKt.flagsOf(Flag.ValueParameter.DECLARES_DEFAULT_VALUE));
        KmValueParameter required = new KmValueParameter("required name"); required.setType(type("kotlin/String"));
        KmValueParameter vararg = new KmValueParameter("values"); vararg.setType(type("kotlin/Array"));
        vararg.setVarargElementType(type("kotlin/String"));
        KmFunction function = new KmFunction("f"); function.setReturnType(type("kotlin/Unit"));
        function.getValueParameters().addAll(List.of(optional, vararg, required));
        JvmExtensionsKt.setSignature(function, new JvmMethodSignature("f", "(I[Ljava/lang/String;Ljava/lang/String;)V"));
        cls.getFunctions().add(function);
        KmConstructor ctor = new KmConstructor(); ctor.getValueParameters().addAll(List.of(optional, required));
        JvmExtensionsKt.setSignature(ctor, new JvmMethodSignature("<init>", "(ILjava/lang/String;)V"));
        cls.getConstructors().add(ctor);
        KmConstructor noArgs = new KmConstructor();
        JvmExtensionsKt.setSignature(noArgs, new JvmMethodSignature("<init>", "()V"));
        cls.getConstructors().add(noArgs);
        var result = KotlinMetadataReader.read(KotlinClassMetadata.writeClass(cls));
        assertEquals(KotlinMetadataReader.Status.PARSED, result.status(), result.detail());
        assertEquals(List.of(new KotlinValueParameter("optional", true, false),
                new KotlinValueParameter("values", false, true),
                new KotlinValueParameter("required name", false, false)), result.functions().getFirst().valueParameters());
        assertEquals(2, result.constructors().size());
        assertEquals("(ILjava/lang/String;)V", result.constructors().getFirst().descriptor());
        assertEquals(2, result.constructors().getFirst().valueParameters().size());
        assertEquals(List.of(), result.constructors().get(1).valueParameters());
    }

    private static KmType type(String name) {
        KmType result = new KmType();
        result.setClassifier(new KmClassifier.Class(name));
        return result;
    }
}
