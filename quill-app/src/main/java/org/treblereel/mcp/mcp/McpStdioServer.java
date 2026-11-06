package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.treblereel.mcp.QuillTopCommand;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public final class McpStdioServer {

    static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 4;
    static final int DEFAULT_MAX_QUEUED_REQUESTS_PER_WORKER = 64;
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private McpStdioServer() {}

    public static void start(ProjectRegistry registry, InputStream input, OutputStream output) {
        start(registry, input, output, McpToolProfile.full());
    }

    public static void start(ProjectRegistry registry, InputStream input, OutputStream output,
            McpToolProfile profile) {
        registry.prewarmAsync();
        startServer(new QuillTools(registry), QuillTools.class, input, output,
                positiveEnvironmentDuration(
                        "QUILL_MCP_REQUEST_TIMEOUT", DEFAULT_REQUEST_TIMEOUT), profile, registry);
    }

    static void start(Object tools, Class<?> toolType, InputStream input, OutputStream output,
            Duration requestTimeout) {
        startServer(tools, toolType, input, output, requestTimeout, McpToolProfile.full(), null);
    }

    private static void startServer(Object tools, Class<?> toolType, InputStream input,
            OutputStream output, Duration requestTimeout, McpToolProfile profile, ProjectRegistry registry) {
        CountDownLatch eof = new CountDownLatch(1);
        InputStream serverInput = new EofAwareInputStream(input, eof);
        var mapper = new JacksonMcpJsonMapper(new ObjectMapper());
        Scheduler toolScheduler = Schedulers.newBoundedElastic(
                positiveEnvironmentValue("QUILL_MCP_MAX_CONCURRENCY",
                        DEFAULT_MAX_CONCURRENT_REQUESTS),
                positiveEnvironmentValue("QUILL_MCP_MAX_QUEUED_PER_WORKER",
                        DEFAULT_MAX_QUEUED_REQUESTS_PER_WORKER),
                "quill-mcp");
        Scheduler responseScheduler = Schedulers.newSingle("quill-mcp-response");
        var transport = new StdioServerTransportProvider(mapper, serverInput, output) {
            @Override public void setSessionFactory(io.modelcontextprotocol.spec.McpServerSession.Factory factory) {
                super.setSessionFactory(delegate -> {
                    var serialized = new io.modelcontextprotocol.spec.McpServerTransport() {
                    @Override public reactor.core.publisher.Mono<Void> sendMessage(io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage message) {
                        // Catalog, handshake and tool responses share the SDK's single-producer sink.
                        return reactor.core.publisher.Mono.defer(() -> delegate.sendMessage(message)).subscribeOn(responseScheduler);
                    }
                    @Override public <T> T unmarshalFrom(Object data, io.modelcontextprotocol.json.TypeRef<T> type) {
                        return delegate.unmarshalFrom(data, type);
                    }
                    @Override public reactor.core.publisher.Mono<Void> closeGracefully() { return delegate.closeGracefully(); }
                    @Override public void close() { delegate.close(); }
                    };
                    return new CancellableMcpSession(factory.create(serialized), serialized, requestTimeout);
                });
            }
        };
        var specifications = McpToolCatalog.create(
                tools, toolType, toolScheduler, responseScheduler, requestTimeout, profile);

        var server = McpServer.async(transport)
                .serverInfo("quill", QuillTopCommand.version())
                .instructions(serverInstructions(profile,
                        tools instanceof QuillTools quill ? quill.workspaceMode() : null))
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(specifications)
                .requestTimeout(requestTimeout)
                .jsonSchemaValidator((schema, value) -> ValidationResponse.asValid(""))
                // McpToolCatalog performs the small set of validations Quill needs.
                // Avoiding the generic JSON Schema engine removes four otherwise
                // unused libraries and their transitive native-image metadata.
                .validateToolInputs(false)
                .build();
        var startupLock = new Object();
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        var stopWarmups = new java.util.ArrayList<Runnable>();
        if (registry != null) registry.prewarmAsync().thenRun(() -> {
            synchronized (startupLock) {
                if (stopped.get()) return;
                stopWarmups.add(KotlinSemanticQueries.startWarmup(registry));
                stopWarmups.add(JavaSemanticQueries.startWarmup(registry));
            }
        });
        try {
            eof.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (startupLock) {
                stopped.set(true);
                stopWarmups.forEach(Runnable::run);
            }
            try {
                // Cancelling the worker scheduler first interrupts active blocking tool calls and
                // prevents an abandoned client from keeping the stdio process alive indefinitely.
                toolScheduler.dispose();
                server.closeGracefully().block(SHUTDOWN_TIMEOUT);
            } finally {
                if (registry != null) registry.close();
                responseScheduler.dispose();
                KotlinSemanticQueries.closeWarmup();
                JavaSemanticQueries.close();
                org.treblereel.mcp.kotlin.KotlinWorkerClient.shared().close();
            }
        }
    }

    static String serverInstructions(McpToolProfile profile) {
        return serverInstructions(profile, null);
    }

    static String serverInstructions(McpToolProfile profile, Boolean workspaceMode) {
        String instructions = "Quill supplies read-only code intelligence for indexed JVM projects. "
                + "Known targets can be queried directly; discover exact tools on demand and print only selected schemas. ";
        instructions += workspaceMode == null
                ? "For get_overview, use view=compact only if launch arguments confirm --workspace; otherwise omit arguments. "
                : workspaceMode
                        ? "Server mode: workspace (--workspace). For inventory, use get_overview view=compact, then select a project for full details. "
                        : "Server mode: single_project (no --workspace). If using get_overview, call without arguments, not view=compact. ";
        instructions += "Follow retry_with on mode errors. Read structuredContent even when text is empty. ";
        if (profile.router() || profile.includes("change_session")) {
            instructions += "For a code change, query change_session "
                + "before editing or choosing a build, then follow directive.primary_action and "
                + "phase_gate; refresh after edits and external compilation. "
                + "A question about how to change code stays read-only unless the user asked to apply it. ";
        }
        instructions += "Quill never runs builds "
                + "or tests. Use source reads for known files and stale/unsupported evidence. "
                + "Do not infer missing tools from empty resources: this server exposes tools, not resources.";
        if (profile.router()) {
            if (!profile.tools().isEmpty()) {
                return instructions + " Call available queries directly. For other operations, "
                        + "obtain arguments through catalog discovery, then execute_tool.";
            }
            return instructions + " Obtain query schemas through catalog discovery, then execute_tool.";
        }
        return instructions + " If a query is not in this profile, use the available specialized tools.";
    }

    private static int positiveEnvironmentValue(String name, int fallback) {
        String configured = System.getenv(name);
        if (configured == null || configured.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(configured);
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static Duration positiveDurationValue(String configured, Duration fallback) {
        if (configured == null || configured.isBlank()) return fallback;
        try {
            long seconds = Long.parseLong(configured.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : fallback;
        } catch (NumberFormatException | ArithmeticException ignored) {
            return fallback;
        }
    }

    private static Duration positiveEnvironmentDuration(String name, Duration fallback) {
        return positiveDurationValue(System.getenv(name), fallback);
    }

    private static final class EofAwareInputStream extends FilterInputStream {
        private final CountDownLatch eof;

        private EofAwareInputStream(InputStream delegate, CountDownLatch eof) {
            super(delegate);
            this.eof = eof;
        }

        @Override
        public int read() throws IOException {
            return signal(super.read());
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return signal(super.read(bytes, offset, length));
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                eof.countDown();
            }
        }

        private int signal(int result) {
            if (result < 0) eof.countDown();
            return result;
        }
    }
}
