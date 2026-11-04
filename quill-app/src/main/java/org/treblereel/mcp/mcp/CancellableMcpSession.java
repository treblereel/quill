package org.treblereel.mcp.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Adds request cancellation missing from the SDK's legacy stdio session. */
final class CancellableMcpSession extends McpServerSession {
    private final McpServerSession delegate;
    private final Map<Object, Sinks.Empty<Void>> active = new ConcurrentHashMap<>();

    CancellableMcpSession(McpServerSession delegate, McpServerTransport transport, Duration timeout) {
        super(delegate.getId(), timeout, transport, null, Map.of(), Map.of());
        this.delegate = delegate;
    }

    @Override public Mono<Void> handle(McpSchema.JSONRPCMessage message) {
        return Mono.defer(() -> {
            if (message instanceof McpSchema.JSONRPCNotification notification
                    && "notifications/cancelled".equals(notification.method())) {
                if (notification.params() instanceof Map<?, ?> params && params.get("requestId") != null) {
                    var pending = active.get(params.get("requestId"));
                    if (pending != null) pending.tryEmitEmpty();
                }
                return Mono.empty();
            }
            if (!(message instanceof McpSchema.JSONRPCRequest request) || !"tools/call".equals(request.method()))
                return delegate.handle(message);
            var cancel = Sinks.<Void>empty();
            if (active.putIfAbsent(request.id(), cancel) != null)
                return Mono.error(new IllegalArgumentException("Duplicate active MCP request id"));
            return delegate.handle(message).takeUntilOther(cancel.asMono())
                    .doFinally(signal -> active.remove(request.id(), cancel));
        });
    }

    private void cancelAll() { active.values().forEach(signal -> signal.tryEmitEmpty()); }
    @Override public Mono<Void> closeGracefully() {
        return Mono.defer(() -> { cancelAll(); return delegate.closeGracefully(); });
    }
    @Override public void close() { cancelAll(); delegate.close(); }
    @Override public <T> Mono<T> sendRequest(String method, Object params, TypeRef<T> type) {
        return delegate.sendRequest(method, params, type);
    }
    @Override public Mono<Void> sendNotification(String method, Object params) { return delegate.sendNotification(method, params); }
    @Override public void init(McpSchema.ClientCapabilities capabilities, McpSchema.Implementation client) { delegate.init(capabilities, client); }
    @Override public void setMinLoggingLevel(McpSchema.LoggingLevel level) { delegate.setMinLoggingLevel(level); }
    @Override public boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel level) { return delegate.isNotificationForLevelAllowed(level); }
}
