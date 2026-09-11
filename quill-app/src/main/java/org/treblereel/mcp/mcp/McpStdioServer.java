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

    private McpStdioServer() {}

    public static void start(ProjectRegistry registry, InputStream input, OutputStream output) {
        CountDownLatch eof = new CountDownLatch(1);
        InputStream serverInput = new EofAwareInputStream(input, eof);
        var mapper = new JacksonMcpJsonMapper(new ObjectMapper());
        var transport = new StdioServerTransportProvider(mapper, serverInput, output);
        Scheduler toolScheduler = Schedulers.newBoundedElastic(
                positiveEnvironmentValue("QUILL_MCP_MAX_CONCURRENCY",
                        DEFAULT_MAX_CONCURRENT_REQUESTS),
                positiveEnvironmentValue("QUILL_MCP_MAX_QUEUED_PER_WORKER",
                        DEFAULT_MAX_QUEUED_REQUESTS_PER_WORKER),
                "quill-mcp");
        Scheduler responseScheduler = Schedulers.newSingle("quill-mcp-response");
        var tools = McpToolCatalog.create(
                new QuillTools(registry), toolScheduler, responseScheduler);

        var server = McpServer.async(transport)
                .serverInfo("quill", QuillTopCommand.version())
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(tools)
                .requestTimeout(Duration.ofSeconds(30))
                .jsonSchemaValidator((schema, value) -> ValidationResponse.asValid(""))
                // McpToolCatalog performs the small set of validations Quill needs.
                // Avoiding the generic JSON Schema engine removes four otherwise
                // unused libraries and their legacy native-image metadata.
                .validateToolInputs(false)
                .build();
        try {
            eof.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                server.closeGracefully().block(Duration.ofSeconds(5));
            } finally {
                try {
                    responseScheduler.dispose();
                } finally {
                    toolScheduler.dispose();
                }
            }
        }
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
