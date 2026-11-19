package org.treblereel.mcp.core;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The process-wide Jackson mapper with default settings. ObjectMapper is thread-safe once
 * configured, so callers share this instance instead of building their own; code that needs
 * different parser or writer features keeps a separately configured mapper.
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}
}
