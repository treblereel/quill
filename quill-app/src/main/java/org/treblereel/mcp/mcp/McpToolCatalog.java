package org.treblereel.mcp.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class McpToolCatalog {

    private McpToolCatalog() {}

    static List<SyncToolSpecification> create(QuillTools tools) {
        return java.util.Arrays.stream(QuillTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .sorted(Comparator.comparing(Method::getName))
                .map(method -> specification(tools, method))
                .toList();
    }

    private static SyncToolSpecification specification(QuillTools tools, Method method) {
        Tool annotation = method.getAnnotation(Tool.class);
        McpSchema.Tool tool = McpSchema.Tool.builder(method.getName(), inputSchema(method))
                .description(annotation.description())
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> invoke(tools, method, request.arguments()))
                .build();
    }

    private static Map<String, Object> inputSchema(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", jsonType(parameter.getParameterizedType()));
            ToolArg arg = parameter.getAnnotation(ToolArg.class);
            if (arg != null) property.put("description", arg.description());
            properties.put(parameter.getName(), property);
            if (!isOptional(parameter.getParameterizedType())) required.add(parameter.getName());
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static McpSchema.CallToolResult invoke(
            QuillTools tools, Method method, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        try {
            Object[] values = new Object[method.getParameterCount()];
            Parameter[] parameters = method.getParameters();
            var acceptedArguments = java.util.Arrays.stream(parameters)
                    .map(Parameter::getName).collect(java.util.stream.Collectors.toSet());
            Optional<String> unknown = args.keySet().stream()
                    .filter(name -> !acceptedArguments.contains(name)).findFirst();
            if (unknown.isPresent()) {
                return result("Unknown argument: " + unknown.get(), true);
            }
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                Object value = args.get(parameter.getName());
                if (isOptional(parameter.getParameterizedType())) {
                    values[i] = Optional.ofNullable(convertOptional(
                            value, optionalArgument(parameter.getParameterizedType())));
                } else {
                    if (value == null) {
                        return result("Missing required argument: " + parameter.getName(), true);
                    }
                    values[i] = convert(value, parameter.getType());
                }
            }
            return result((String) method.invoke(tools, values), false);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return result("Tool failed: " + ProjectRegistry.safeMessage(cause), true);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return result("Tool failed: " + ProjectRegistry.safeMessage(e), true);
        }
    }

    private static McpSchema.CallToolResult result(String text, boolean error) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder(text).build()))
                .isError(error)
                .build();
    }

    private static Object convertOptional(Object value, Class<?> targetType) {
        return value == null ? null : convert(value, targetType);
    }

    private static Object convert(Object value, Class<?> targetType) {
        if (targetType == Integer.class || targetType == int.class) {
            if (value instanceof Number number) return number.intValue();
            throw new IllegalArgumentException("Expected integer, got " + value.getClass().getSimpleName());
        }
        if (targetType == String.class && !(value instanceof String)) {
            throw new IllegalArgumentException("Expected string, got " + value.getClass().getSimpleName());
        }
        if (targetType == String.class) return value;
        return value;
    }

    private static boolean isOptional(Type type) {
        return type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == Optional.class;
    }

    private static Class<?> optionalArgument(Type type) {
        Type argument = ((ParameterizedType) type).getActualTypeArguments()[0];
        return argument instanceof Class<?> cls ? cls : String.class;
    }

    private static String jsonType(Type type) {
        Class<?> raw = type instanceof ParameterizedType parameterized
                ? optionalArgument(parameterized)
                : (Class<?>) type;
        return raw == Integer.class || raw == int.class ? "integer" : "string";
    }
}
