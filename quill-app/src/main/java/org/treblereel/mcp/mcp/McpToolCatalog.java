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
            "project", "limit", "offset", "module", "source_set", "class_name",
            "include_generated", "include_tests", "include_deleted");

    /** Tools that only answer with --workspace; a single-project catalog omits them. */
    private static final Set<String> WORKSPACE_TOOLS = Set.of(
            "list_workspace_repositories", "get_workspace_dependencies", "resolve_workspace_entity",
            "find_workspace_usages", "assess_workspace_change_risk");

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
            List<AsyncToolSpecification> catalog = new ArrayList<>(create(
                    router, RouterTools.class, toolScheduler, responseScheduler,
                    requestTimeout, McpToolProfile.full()));
            if (!profile.tools().isEmpty() || !profile.listsOverview()) {
                catalog.removeIf(spec -> spec.tool().name().equals("get_overview"));
            }
            java.util.Arrays.stream(QuillTools.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Tool.class))
                    .filter(method -> profile.includes(method.getName())
                            || (!profile.tools().isEmpty() && profile.listsOverview()
                                    && method.getName().equals("get_overview")))
                    .map(method -> specification(quillTools, method, toolScheduler,
                            responseScheduler, requestTimeout, profile, quillTools.singleProjectCatalog()))
                    .forEach(catalog::add);
            catalog.sort(Comparator.comparing(spec -> spec.tool().name()));
            return List.copyOf(catalog);
        }
        boolean singleProject = tools instanceof QuillTools quill && quill.singleProjectCatalog();
        return java.util.Arrays.stream(toolType.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .filter(method -> method.getAnnotation(Tool.class).listed())
                .filter(method -> profile.includes(method.getName()))
                .filter(method -> !singleProject || !WORKSPACE_TOOLS.contains(method.getName()))
                .sorted(Comparator.comparing(Method::getName))
                .map(method -> specification(tools, method, toolScheduler, responseScheduler,
                        requestTimeout, profile, singleProject))
                .toList();
    }

    private static AsyncToolSpecification specification(
            Object tools, Method method, Scheduler toolScheduler,
            Scheduler responseScheduler, Duration requestTimeout, McpToolProfile profile,
            boolean singleProject) {
        Tool annotation = method.getAnnotation(Tool.class);
        McpSchema.Tool.Builder toolBuilder =
                McpSchema.Tool.builder(method.getName(), inputSchema(method, singleProject))
                        .description(description(tools, method, annotation));
        if (annotation.readOnly()) {
            // Queries may refresh derived caches/telemetry, but never edit source/configuration,
            // execute builds, or contact open-ended external services.
            toolBuilder.annotations(McpSchema.ToolAnnotations.builder()
                    .readOnlyHint(true).destructiveHint(false).openWorldHint(false).build());
        }
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
                    var waiting = new java.util.concurrent.atomic.AtomicBoolean();
                    return invokePrepared(tools, method, request.arguments(), profile, toolScheduler, waiting)
                        .timeout(requestTimeout.minus(requestTimeout.dividedBy(10).compareTo(Duration.ofSeconds(1)) < 0
                                ? requestTimeout.dividedBy(10) : Duration.ofSeconds(1)))
                        .onErrorResume(RejectedExecutionException.class,
                                ignored -> Mono.just(result("Server busy; retry later", true,
                                        annotation.structured())))
                        .onErrorResume(TimeoutException.class, ignored -> Mono.just(waiting.get() ? refreshTimeout(tools) : result(
                                "Tool timed out after " + requestTimeout.toSeconds()
                                        + " seconds; retry with a narrower query or increase "
                                        + "QUILL_MCP_REQUEST_TIMEOUT",
                                true, annotation.structured())))
                        // The SDK stdio transport uses a unicast outbound sink whose concurrent
                        // tryEmitNext calls may fail. Serialize completion signals while keeping
                        // the actual tool work parallel.
                        .map(McpToolCatalog::withErrorText)
                        .map(result -> {
                            UxTelemetry.record(method.getName(), routedTool(method, request.arguments()),
                                    (System.nanoTime() - started) / 1_000_000L, result,
                                    clientOf(exchange), modelOf(request.meta()));
                            return result;
                        })
                        .publishOn(responseScheduler);
                })
                .build();
    }

    /** A request gave up waiting: say which project is doing what, so the wait can be diagnosed. */
    private static McpSchema.CallToolResult refreshTimeout(Object tools) {
        var error = JSON.createObjectNode().put("error_code", "INDEX_REFRESH_TIMEOUT")
                .put("message", "Index preparation did not finish within the request deadline; retry this request")
                .put("retryable", true);
        var waiting = error.putArray("waiting_for");
        (tools instanceof QuillTools quill ? quill.waitStatus()
                : tools instanceof RouterTools router ? router.waitStatus() : List.<String>of()).forEach(waiting::add);
        return result(error.toString(), true, true);
    }

    /**
     * Clients that read only text report a failure without any as "Unknown error", so an error's
     * cause must not live in structured content alone. Successful results stay structured-only.
     */
    private static McpSchema.CallToolResult withErrorText(McpSchema.CallToolResult result) {
        if (!Boolean.TRUE.equals(result.isError()) || result.content() != null && !result.content().isEmpty()) return result;
        var error = JSON.valueToTree(result.structuredContent());
        StringBuilder text = new StringBuilder();
        if (error.hasNonNull("error_code")) text.append(error.path("error_code").asText()).append(": ");
        text.append(error.hasNonNull("message") ? error.path("message").asText() : error.toString());
        if (!error.path("waiting_for").isEmpty()) {
            var waiting = new java.util.ArrayList<String>();
            error.path("waiting_for").forEach(item -> waiting.add(item.asText()));
            text.append(" (waiting for ").append(String.join("; ", waiting)).append(')');
        }
        return McpSchema.CallToolResult.builder().isError(true).structuredContent(result.structuredContent())
                .content(List.of(McpSchema.TextContent.builder(text.toString()).build())).build();
    }

    private static Mono<McpSchema.CallToolResult> invokePrepared(Object tools, Method method,
            Map<String, Object> arguments, McpToolProfile profile, Scheduler scheduler,
            java.util.concurrent.atomic.AtomicBoolean waiting) {
        return Mono.defer(() -> {
            Map<?, ?> query = arguments == null ? Map.of() : arguments;
            if (method.getName().equals("execute_tool") && query.get("arguments") instanceof Map<?, ?> nested) query = nested;
            String selector = query.get("project") instanceof String project ? project : null;
            boolean indexed = !method.getName().equals("search_tools");
            waiting.set(indexed && (tools instanceof QuillTools || tools instanceof RouterTools));
            Mono<Void> ready = !waiting.get() ? Mono.empty() : Mono.fromFuture(tools instanceof QuillTools quill
                    ? quill.awaitPrepared(selector) : ((RouterTools) tools).awaitPrepared(selector));
            return ready.then(Mono.fromCallable(() -> {
                        waiting.set(false);
                        return invoke(tools, method, arguments, profile);
                    }).subscribeOn(scheduler))
                    .flatMap(result -> {
                        if (!waitingSupported(tools) || !refreshPending(JSON.valueToTree(result.structuredContent()))) return Mono.just(result);
                        var next = tools instanceof QuillTools quill ? quill.awaitPrepared(selector)
                                : ((RouterTools) tools).awaitPrepared(selector);
                        // A completed preparation may be an actionable failure; do not spin on it.
                        if (next.isDone()) return Mono.fromCallable(() -> invoke(tools, method, arguments, profile)).subscribeOn(scheduler);
                        waiting.set(true);
                        return Mono.fromFuture(next).then(invokePrepared(tools, method, arguments, profile, scheduler, waiting));
                    });
        });
    }

    private static boolean waitingSupported(Object tools) { return tools instanceof QuillTools || tools instanceof RouterTools; }

    private static boolean refreshPending(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || !value.isContainerNode()) return false;
        if ("REFRESH_PENDING".equals(value.path("error_code").asText())
                || "refresh_pending".equals(value.path("status").asText())
                || "INDEX_REFRESH_PENDING".equals(value.path("code").asText())) return true;
        for (var child : value) if (refreshPending(child)) return true;
        return false;
    }

    private static String description(Object tools, Method method, Tool annotation) {
        Boolean workspace = tools instanceof QuillTools quill ? quill.workspaceMode()
                : tools instanceof RouterTools router ? router.workspaceMode() : null;
        if (workspace == null) return annotation.description();
        // Some clients surface only tools/list, so retain mode guidance there too.
        if (method.getName().equals("get_overview")) {
            String mode = workspace
                    ? "Server mode: workspace (--workspace). For inventory use view=compact (limit<=50); then project=<name> with view=full. "
                    : "Server mode: single_project. Overview without arguments, not view=compact. ";
            return mode + "Optional orientation; known targets can be queried directly. Use change_session before edits.";
        }
        return annotation.description();
    }

    /** Client name/version from initialize; every MCP client sends it. */
    private static String clientOf(io.modelcontextprotocol.server.McpAsyncServerExchange exchange) {
        if (exchange == null) return null;
        try {
            var info = exchange.getClientInfo();
            return info == null ? null : info.name() + "/" + info.version();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * The model, when the client reports it per call. Codex sends it in
     * _meta.x-codex-turn-metadata.model; Claude Code does not report a model.
     */
    private static String modelOf(Map<String, Object> meta) {
        if (meta == null || !(meta.get("x-codex-turn-metadata") instanceof Map<?, ?> codex)) return null;
        return codex.get("model") instanceof String model && !model.isBlank() ? model : null;
    }

    private static String routedTool(Method method, Map<String, Object> arguments) {
        if (!"execute_tool".equals(method.getName()) || arguments == null) return null;
        Object value = arguments.get("name");
        return value instanceof String name ? name : null;
    }

    private static boolean queryEvidence(Method method) {
        return method.getDeclaringClass() == QuillTools.class
                && method.getAnnotation(Tool.class).structured()
                && !Set.of("get_overview", "get_build_status", "get_build_problems", "get_file_problems",
                        "change_session", "plan_change", "verify_change").contains(method.getName());
    }

    private static boolean coverageEvidence(Method method) {
        return queryEvidence(method) && Set.of("get_call_hierarchy", "find_usages", "find_symbol_usages",
                "get_context", "find_workspace_usages").contains(method.getName());
    }

    static Map<String, Object> inputSchema(Method method) {
        return inputSchema(method, false);
    }

    /**
     * @param singleProject omit the optional project selector, which has only one possible value;
     *                      the argument is still accepted when a client sends it
     */
    static Map<String, Object> inputSchema(Method method, boolean singleProject) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            if (singleProject && parameter.getName().equals("project")
                    && isOptional(parameter.getParameterizedType())) continue;
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", jsonType(parameter.getParameterizedType()));
            if ("array".equals(property.get("type"))) {
                property.put("items", Map.of("type", "string"));
            } else if ("object".equals(property.get("type"))) {
                property.put("additionalProperties", true);
            }
            ToolArg arg = parameter.getAnnotation(ToolArg.class);
            if (arg != null) {
                if (!SELF_DESCRIBING_ARGUMENTS.contains(parameter.getName())
                        && !arg.description().equalsIgnoreCase(parameter.getName().replace('_', ' '))) {
                    property.put("description", arg.description());
                }
                if (arg.allowed().length > 0) {
                    property.put("enum", List.of(arg.allowed()));
                }
            }
            properties.put(parameter.getName(), property);
            if (!isOptional(parameter.getParameterizedType())) required.add(parameter.getName());
        }

        if (coverageEvidence(method)) {
            properties.put("evidence_detail", Map.of("type", "string", "enum", List.of("summary", "full")));
            properties.put("evidence_offset", Map.of("type", "integer"));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        // An empty "required" array is the JSON Schema default; omitting it keeps the catalog
        // that clients resend with every model request smaller.
        if (!required.isEmpty()) schema.put("required", required);
        // No additionalProperties=false: invocation rejects unknown arguments itself and lists
        // the accepted ones, and the flag repeated on every tool cost ~1.6k catalog characters.
        return schema;
    }

    private static McpSchema.CallToolResult invoke(
            Object tools, Method method, Map<String, Object> arguments, McpToolProfile profile) {
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
            String text = invocation.text();
            if (!invocation.error() && (graphOutput(method.getName()) || graphOutput(routedTool(method, arguments)))) {
                text = GraphMetadata.compact(text);
            }
            return result(withoutUnavailableNextStep(text, profile), invocation.error(), structured);
        } catch (RuntimeException e) {
            trace.failure(e);
            return result("Tool failed: " + ProjectRegistry.safeMessage(e), true, structured);
        } finally {
            trace.close();
        }
    }

    private static boolean graphOutput(String name) {return name != null && java.util.Set.of("get_call_hierarchy", "get_context", "analyze_execution_order").contains(name);}

    static Invocation invokeMethod(
            Object tools, Method method, Map<String, Object> arguments) {
        Map<String, Object> args = guessedArguments(method, withAliases(method.getName(),
                arguments == null ? Map.of() : arguments));
        try {
            Object[] values = new Object[method.getParameterCount()];
            Parameter[] parameters = method.getParameters();
            var acceptedArguments = java.util.Arrays.stream(parameters)
                    .map(Parameter::getName).collect(java.util.stream.Collectors.toSet());
            boolean queryEvidence = queryEvidence(method);
            boolean coverageEvidence = coverageEvidence(method);
            if (coverageEvidence) acceptedArguments.addAll(Set.of("evidence_detail", "evidence_offset"));
            String evidenceDetail = "summary";
            int evidenceOffset = 0;
            if (coverageEvidence) {
                Object detail = args.get("evidence_detail");
                if (detail != null && (!(detail instanceof String) || !Set.of("summary", "full").contains(detail)))
                    return new Invocation("Invalid evidence_detail: expected summary or full", true);
                if (detail != null) evidenceDetail = (String) detail;
                if (args.get("evidence_offset") != null) evidenceOffset = (Integer) convert(args.get("evidence_offset"), Integer.class);
                if (evidenceOffset < 0 || evidenceOffset > 0 && !evidenceDetail.equals("full"))
                    return new Invocation("evidence_offset must be nonnegative and requires evidence_detail=full", true);
            }
            Optional<String> unknown = args.keySet().stream()
                    .filter(name -> !acceptedArguments.contains(name)).findFirst();
            if (unknown.isPresent()) {
                return new Invocation("Unknown argument: " + unknown.get() + ". Accepted arguments: "
                        + String.join(", ", acceptedArguments.stream().sorted().toList()), true);
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
            if (queryEvidence) text = ToolResponseSupport.queryEvidence(text, method.getName(), args,
                    evidenceDetail.equals("full"), evidenceOffset);
            if (method.getAnnotation(Tool.class).structured()) {
                // A routed call was compacted under its own tool name by the nested invocation.
                if (!"execute_tool".equals(method.getName())) {
                    text = ResponseCompaction.apply(text, method.getName(), args);
                    if (!"full".equals(args.get("detail")) && !"full".equals(args.get("evidence_detail")))
                        text = StatedLimitations.of(tools).stateOnce(text);
                }
                text = ResponseBudget.apply(text, method.getName(), args);
            }
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

    /**
     * Drops a next_step that names a tool the active profile does not expose, so an error never
     * sends the client to a tool it cannot call.
     */
    static String withoutUnavailableNextStep(String text, McpToolProfile profile) {
        if (text == null || profile.router() || !text.contains("\"next_step\"")) return text;
        try {
            if (!(JSON.readTree(text) instanceof com.fasterxml.jackson.databind.node.ObjectNode root)) return text;
            String tool = root.path("next_step").path("tool").asText(null);
            if (tool == null || profile.includes(tool)) return text;
            root.remove("next_step");
            return root.toString();
        } catch (Exception ignored) {
            return text;
        }
    }

    /** Spellings agents used for the router's arguments in recorded sessions. */
    private static final Map<String, Map<String, String>> ARGUMENT_ALIASES = Map.of(
            "execute_tool", Map.of("tool_name", "name", "tool", "name",
                    "args", "arguments", "parameters", "arguments", "input", "arguments"),
            "find_usages", Map.of("symbol", "target", "class_name", "target"),
            "list_injection_points", Map.of("class_name", "target", "type", "target"),
            "list_beans", Map.of("type", "class_name"));

    private static final List<String> SELECTORS = List.of("target", "targets", "class_name");
    private static final Set<String> GUESSED_SELECTORS = Set.of("target", "targets", "class_name", "symbol", "class");
    private static final Map<String, String> DIRECTIONS = Map.of("callers", "inbound", "incoming", "inbound",
            "callees", "outbound", "outgoing", "outbound");

    /**
     * Agents carry argument names across tools: V22 CQ-045 Codex sent class_name to tools that take
     * target, target to one that takes class_name, method_name and direction=callers, and six calls
     * failed. A guess reaches the parameter it means when that parameter is the only one it can
     * mean and was not also given; anything ambiguous stays an unknown argument.
     */
    static Map<String, Object> guessedArguments(Method method, Map<String, Object> arguments) {
        Map<String, Parameter> parameters = new LinkedHashMap<>();
        for (Parameter parameter : method.getParameters()) parameters.put(parameter.getName(), parameter);
        List<String> guesses = arguments.keySet().stream()
                .filter(name -> GUESSED_SELECTORS.contains(name) && !parameters.containsKey(name)).toList();
        List<String> selectors = SELECTORS.stream().filter(parameters::containsKey).toList();
        Map<String, Object> normalized = new LinkedHashMap<>(arguments);
        if (guesses.size() == 1 && selectors.size() == 1 && !arguments.containsKey(selectors.get(0))) {
            Object value = normalized.remove(guesses.get(0));
            normalized.put(selectors.get(0), selectors.get(0).equals("targets") && value instanceof String one ? List.of(one) : value);
        }
        // A change_session snapshot id names nothing verify_change could narrow: it recomputes from the worktree.
        // In V24 and V25 CQ-049 Claude passed it in three of six sessions and lost a turn to the error.
        if (method.getName().equals("verify_change") && !parameters.containsKey("session_id"))
            normalized.remove("session_id");
        if (parameters.containsKey("method") && !parameters.containsKey("method_name")
                && normalized.containsKey("method_name") && !normalized.containsKey("method"))
            normalized.put("method", normalized.remove("method_name"));
        // V41: Codex sent depth for max_depth and property for key.
        for (var guess : Map.of("depth", "max_depth", "property", "key").entrySet())
            if (parameters.containsKey(guess.getValue()) && !parameters.containsKey(guess.getKey())
                    && normalized.containsKey(guess.getKey()) && !normalized.containsKey(guess.getValue()))
                normalized.put(guess.getValue(), normalized.remove(guess.getKey()));
        // V41 CQ-020: a client HTML-escaped <init>.
        for (String name : List.of("method", "signature"))
            if (normalized.get(name) instanceof String value && value.contains("&"))
                normalized.put(name, value.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"));
        Parameter direction = parameters.get("direction");
        ToolArg directionArg = direction == null ? null : direction.getAnnotation(ToolArg.class);
        if (directionArg != null && List.of(directionArg.allowed()).contains("inbound")
                && normalized.get("direction") instanceof String value && DIRECTIONS.containsKey(value.toLowerCase(java.util.Locale.ROOT)))
            normalized.put("direction", DIRECTIONS.get(value.toLowerCase(java.util.Locale.ROOT)));
        return normalized;
    }

    private static Map<String, Object> withAliases(String tool, Map<String, Object> arguments) {
        Map<String, String> aliases = ARGUMENT_ALIASES.get(tool);
        if (aliases == null || arguments.keySet().stream().noneMatch(aliases::containsKey)) return arguments;
        Map<String, Object> normalized = new LinkedHashMap<>();
        arguments.forEach((key, value) -> {
            String canonical = aliases.getOrDefault(key, key);
            // Keep ambiguous query inputs invalid; router compatibility retains its canonical-wins rule.
            if (!canonical.equals(key) && !tool.equals("execute_tool")
                    && (arguments.containsKey(canonical) || normalized.containsKey(canonical))) {
                normalized.put(key, value);
                return;
            }
            if (!canonical.equals(key) && arguments.containsKey(canonical)) return;
            normalized.put(canonical, value);
        });
        return normalized;
    }

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
