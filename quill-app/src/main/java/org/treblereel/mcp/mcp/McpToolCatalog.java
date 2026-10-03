package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.treblereel.mcp.diagnostics.DebugTrace;
import org.treblereel.mcp.diagnostics.UxTelemetry;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

final class McpToolCatalog {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> OBJECT_OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "additionalProperties", true);
    private static final Set<String> SELF_DESCRIBING_ARGUMENTS = Set.of(
            "project", "limit", "offset", "module", "source_set", "class_name");

    private McpToolCatalog() {}

    static List<AsyncToolSpecification> create(
            QuillTools tools, Scheduler toolScheduler, Scheduler responseScheduler,
            Duration requestTimeout) {
        return create(tools, QuillTools.class, toolScheduler, responseScheduler, requestTimeout,
                McpToolProfile.full());
    }

    static List<AsyncToolSpecification> create(
            QuillTools tools, Scheduler toolScheduler, Scheduler responseScheduler,
            Duration requestTimeout, McpToolProfile profile) {
        return create(tools, QuillTools.class, toolScheduler, responseScheduler, requestTimeout,
                profile);
    }

    static List<AsyncToolSpecification> create(
            Object tools, Class<?> toolType, Scheduler toolScheduler,
            Scheduler responseScheduler, Duration requestTimeout) {
        return create(tools, toolType, toolScheduler, responseScheduler, requestTimeout,
                McpToolProfile.full());
    }

    static List<AsyncToolSpecification> create(
            Object tools, Class<?> toolType, Scheduler toolScheduler,
            Scheduler responseScheduler, Duration requestTimeout, McpToolProfile profile) {
        if (profile.router()) {
            if (!(tools instanceof QuillTools quillTools) || toolType != QuillTools.class) {
                throw new IllegalArgumentException("Router profile requires QuillTools");
            }
            RouterTools router = new RouterTools(quillTools);
            return create(router, RouterTools.class, toolScheduler, responseScheduler,
                    requestTimeout, McpToolProfile.full());
        }
        return java.util.Arrays.stream(toolType.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .filter(method -> profile.includes(method.getName()))
                .sorted(Comparator.comparing(Method::getName))
                .map(method -> specification(
                        tools, method, toolScheduler, responseScheduler, requestTimeout))
                .toList();
    }

    private static AsyncToolSpecification specification(
            Object tools, Method method, Scheduler toolScheduler,
            Scheduler responseScheduler, Duration requestTimeout) {
        Tool annotation = method.getAnnotation(Tool.class);
        McpSchema.Tool.Builder toolBuilder =
                McpSchema.Tool.builder(method.getName(), inputSchema(method))
                        .description(annotation.description());
        if (annotation.structured()) {
            toolBuilder.outputSchema(annotation.output().isBlank()
                    ? OBJECT_OUTPUT_SCHEMA
                    : ToolOutputSchemas.schema(annotation.output()));
        }
        McpSchema.Tool tool = toolBuilder.build();

        return AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    long started = System.nanoTime();
                    return Mono.fromCallable(() -> invoke(tools, method, request.arguments()))
                        .subscribeOn(toolScheduler)
                        .timeout(requestTimeout)
                        .onErrorResume(RejectedExecutionException.class,
                                ignored -> Mono.just(result("Server busy; retry later", true,
                                        annotation.structured())))
                        .onErrorResume(TimeoutException.class, ignored -> Mono.just(result(
                                "Tool timed out after " + requestTimeout.toSeconds()
                                        + " seconds; retry with a narrower query or increase "
                                        + "QUILL_MCP_REQUEST_TIMEOUT",
                                true, annotation.structured())))
                        // The SDK stdio transport uses a unicast outbound sink whose concurrent
                        // tryEmitNext calls may fail. Serialize completion signals while keeping
                        // the actual tool work parallel.
                        .map(result -> {
                            UxTelemetry.record(method.getName(), routedTool(method, request.arguments()),
                                    (System.nanoTime() - started) / 1_000_000L, result);
                            return result;
                        })
                        .publishOn(responseScheduler);
                })
                .build();
    }

    private static String routedTool(Method method, Map<String, Object> arguments) {
        if (!"execute_tool".equals(method.getName()) || arguments == null) return null;
        Object value = arguments.get("name");
        return value instanceof String name ? name : null;
    }

    static Map<String, Object> inputSchema(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", jsonType(parameter.getParameterizedType()));
            if ("array".equals(property.get("type"))) {
                property.put("items", Map.of("type", "string"));
            } else if ("object".equals(property.get("type"))) {
                property.put("additionalProperties", true);
            }
            ToolArg arg = parameter.getAnnotation(ToolArg.class);
            if (arg != null) {
                if (!SELF_DESCRIBING_ARGUMENTS.contains(parameter.getName())) {
                    property.put("description", arg.description());
                }
                if (arg.allowed().length > 0) {
                    property.put("enum", List.of(arg.allowed()));
                }
            }
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
            Object tools, Method method, Map<String, Object> arguments) {
        boolean structured = method.getAnnotation(Tool.class).structured();
        DebugTrace.Trace trace = DebugTrace.start("mcp_tool_call");
        try {
            trace.event("tool_invoked", Map.of(
                    "tool", method.getName(),
                    "argument_names", arguments == null
                            ? List.of() : arguments.keySet().stream().sorted().toList()));
            Invocation invocation = invokeMethod(tools, method, arguments);
            trace.event("tool_result", Map.of(
                    "tool", method.getName(), "is_error", invocation.error()));
            return result(invocation.text(), invocation.error(), structured);
        } catch (RuntimeException e) {
            trace.failure(e);
            return result("Tool failed: " + ProjectRegistry.safeMessage(e), true, structured);
        } finally {
            trace.close();
        }
    }

    static Invocation invokeMethod(
            Object tools, Method method, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        try {
            Object[] values = new Object[method.getParameterCount()];
            Parameter[] parameters = method.getParameters();
            var acceptedArguments = java.util.Arrays.stream(parameters)
                    .map(Parameter::getName).collect(java.util.stream.Collectors.toSet());
            Optional<String> unknown = args.keySet().stream()
                    .filter(name -> !acceptedArguments.contains(name)).findFirst();
            if (unknown.isPresent()) {
                return new Invocation("Unknown argument: " + unknown.get(), true);
            }
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                Object value = args.get(parameter.getName());
                if (isOptional(parameter.getParameterizedType())) {
                    values[i] = Optional.ofNullable(convertOptional(
                            value, optionalArgument(parameter.getParameterizedType())));
                } else {
                    if (value == null) {
                        return new Invocation(
                                "Missing required argument: " + parameter.getName(), true);
                    }
                }
                ToolArg arg = parameter.getAnnotation(ToolArg.class);
                if (value != null && arg != null && arg.allowed().length > 0
                        && (!(value instanceof String string)
                                || !List.of(arg.allowed()).contains(string))) {
                    return new Invocation("Invalid argument " + parameter.getName()
                            + ": expected one of " + String.join(", ", arg.allowed()), true);
                }
                if (!isOptional(parameter.getParameterizedType())) {
                    values[i] = convert(value, parameter.getType());
                }
            }
            String text = (String) method.invoke(tools, values);
            return new Invocation(text, isToolError(text));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new Invocation(
                    "Tool failed: " + ProjectRegistry.safeMessage(cause), true);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return new Invocation("Tool failed: " + ProjectRegistry.safeMessage(e), true);
        }
    }

    record Invocation(String text, boolean error) {}

    private static McpSchema.CallToolResult result(
            String text, boolean error, boolean structured) {
        McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder()
                .isError(error);
        if (structured) {
            builder.structuredContent(structuredContent(text, error));
        } else {
            builder.content(List.of(McpSchema.TextContent.builder(text).build()));
        }
        return builder.build();
    }

    private static Object structuredContent(String text, boolean error) {
        try {
            var parsed = JSON.readTree(text);
            if (parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                return normalizeErrorEnvelope(object);
            }
        } catch (Exception ignored) {
            // Catalog-generated failures are converted to a stable structured envelope.
        }
        if (!error) return JSON.createObjectNode().put("result", text);
        var envelope = JSON.createObjectNode();
        envelope.put("error_code", "TOOL_INVOCATION_ERROR");
        envelope.put("message", text);
        envelope.put("retryable", text.contains("retry"));
        return envelope;
    }

    private static Object normalizeErrorEnvelope(
            com.fasterxml.jackson.databind.node.ObjectNode object) {
        if (!object.has("error")) return object;
        String legacy = object.path("error").asText("Tool failed");
        if (!object.has("error_code")) {
            String code = legacy.matches("[a-z][a-z0-9_]*")
                    ? legacy.toUpperCase(java.util.Locale.ROOT)
                    : "TOOL_ERROR";
            object.put("error_code", code);
        }
        if (!object.has("message")) object.put("message", legacy);
        if (!object.has("retryable")) object.put("retryable", false);
        object.remove("error");
        return object;
    }

    private static Object convertOptional(Object value, Class<?> targetType) {
        return value == null ? null : convert(value, targetType);
    }

    private static Object convert(Object value, Class<?> targetType) {
        if (targetType == Integer.class || targetType == int.class) {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
                return ((Number) value).intValue();
            }
            if (value instanceof Long number
                    && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
                return number.intValue();
            }
            throw new IllegalArgumentException("Expected integer, got " + value.getClass().getSimpleName());
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            if (value instanceof Boolean) return value;
            throw new IllegalArgumentException("Expected boolean, got "
                    + value.getClass().getSimpleName());
        }
        if (targetType == String.class && !(value instanceof String)) {
            throw new IllegalArgumentException("Expected string, got " + value.getClass().getSimpleName());
        }
        if (targetType == String.class) return value;
        if (targetType == List.class) {
            if (value instanceof List<?> list
                    && list.stream().allMatch(String.class::isInstance)) return List.copyOf(list);
            throw new IllegalArgumentException("Expected string array");
        }
        if (targetType == Map.class) {
            if (value instanceof Map<?, ?> map
                    && map.keySet().stream().allMatch(String.class::isInstance)) {
                return Map.copyOf(map);
            }
            throw new IllegalArgumentException("Expected object");
        }
        return value;
    }

    private static boolean isOptional(Type type) {
        return type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == Optional.class;
    }

    private static Class<?> optionalArgument(Type type) {
        Type argument = ((ParameterizedType) type).getActualTypeArguments()[0];
        if (argument instanceof Class<?> cls) return cls;
        if (argument instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw) return raw;
        return String.class;
    }

    private static String jsonType(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() == Optional.class) {
                return jsonType(parameterized.getActualTypeArguments()[0]);
            }
            if (parameterized.getRawType() == List.class) return "array";
            if (parameterized.getRawType() == Map.class) return "object";
        }
        Class<?> raw = type instanceof ParameterizedType parameterized
                ? optionalArgument(parameterized) : (Class<?>) type;
        if (raw == Integer.class || raw == int.class) return "integer";
        if (raw == Boolean.class || raw == boolean.class) return "boolean";
        if (raw == Map.class) return "object";
        return "string";
    }

    private static boolean isToolError(String text) {
        if (text == null || text.isBlank() || text.charAt(0) != '{') return false;
        try {
            var parsed = JSON.readTree(text);
            return parsed.has("error_code") || parsed.has("error");
        } catch (Exception ignored) {
            return false;
        }
    }
}
