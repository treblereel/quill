package org.treblereel.mcp.kotlin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Isolated K2 FIR analysis with opt-in compatible plugins and no code generation. */
public final class SemanticWorker {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--serve")) {
            try { serve(System.in, System.out); } finally { K2Analyzer.clear(); }
            return;
        }
        try { JSON.writeValue(System.out, safelyAnalyze(JSON.readTree(System.in))); }
        finally { K2Analyzer.clear(); }
    }

    // Length-prefixed UTF-8 JSON keeps multiline paths and compiler diagnostics out of framing.
    static void serve(java.io.InputStream source, java.io.OutputStream target) throws Exception {
        var input = new java.io.DataInputStream(source);
        var output = new java.io.DataOutputStream(target);
        while (true) {
            int first = input.read();
            if (first == -1) return;
            int size = (first << 24) | (input.readUnsignedByte() << 16)
                    | (input.readUnsignedByte() << 8) | input.readUnsignedByte();
            if (size <= 0 || size > 1024 * 1024) throw new java.io.IOException("Invalid request frame");
            byte[] bytes = input.readNBytes(size);
            if (bytes.length != size) throw new java.io.EOFException("Truncated request frame");
            // Malformed JSON is a protocol failure: terminate rather than lose synchronization.
            ObjectNode result = safelyAnalyze(JSON.readTree(bytes));
            byte[] response = JSON.writeValueAsBytes(result);
            if (response.length > 1024 * 1024) throw new java.io.IOException("Response frame too large");
            output.writeInt(response.length);
            output.write(response);
            output.flush();
            // Compiler state after stack exhaustion is not reusable. Flush fallback before exiting.
            if (result.path("reason").asText().equals("StackOverflowError")) return;
        }
    }

    private static ObjectNode safelyAnalyze(JsonNode request) {
        ObjectNode result;
        try { result = analyze(request); }
        catch (Exception | LinkageError | StackOverflowError failure) {
            K2Analyzer.clear();
            failure.printStackTrace(System.err);
            result = K2Analyzer.response("unavailable").put("reason", failure.getClass().getSimpleName());
        }
        return result;
    }

    static ObjectNode analyze(JsonNode request) { return K2Analyzer.analyze(request); }
}
