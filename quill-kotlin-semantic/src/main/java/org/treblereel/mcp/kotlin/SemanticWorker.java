package org.treblereel.mcp.kotlin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Isolated K2 FIR analysis; no code generation or project compiler plugins. */
public final class SemanticWorker {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        ObjectNode result;
        try { result = analyze(JSON.readTree(System.in)); }
        catch (Exception | LinkageError failure) {
            failure.printStackTrace(System.err);
            result = K2Analyzer.response("unavailable").put("reason", failure.getClass().getSimpleName());
        }
        JSON.writeValue(System.out, result);
    }

    static ObjectNode analyze(JsonNode request) { return K2Analyzer.analyze(request); }
}
