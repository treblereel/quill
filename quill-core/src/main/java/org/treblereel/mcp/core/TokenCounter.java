package org.treblereel.mcp.core;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

public final class TokenCounter {

    private static volatile Encoding encoding;

    private TokenCounter() {}

    private static Encoding encoding() {
        if (encoding == null) {
            synchronized (TokenCounter.class) {
                if (encoding == null) {
                    EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
                    encoding = registry.getEncoding(EncodingType.CL100K_BASE);
                }
            }
        }
        return encoding;
    }

    public static int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding().countTokens(text);
    }
}
