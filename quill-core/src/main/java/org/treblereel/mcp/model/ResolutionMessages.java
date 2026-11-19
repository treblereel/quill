package org.treblereel.mcp.model;

import java.util.List;

public final class ResolutionMessages {
    public static final String STATIC_MODEL_LIMITATION =
            "Static analysis cannot observe runtime extensions, conditional registration, "
                    + "or framework-specific resolution hooks.";
    public static final String CANDIDATE_NOT_PERSISTED =
            "The statically selected candidate is outside the persisted project bean set.";

    private ResolutionMessages() {}

    public static String resolvedInApplicationContext(String context) {
        return "Resolved in application module context '" + context + "'.";
    }

    public static String differingApplicationContexts(List<String> contexts) {
        return "Resolution differs across application contexts: " + contexts + ".";
    }
}
