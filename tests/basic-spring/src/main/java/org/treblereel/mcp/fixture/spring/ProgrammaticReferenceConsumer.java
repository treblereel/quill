package org.treblereel.mcp.fixture.spring;

import java.net.URL;
import java.util.ResourceBundle;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

public class ProgrammaticReferenceConsumer {

    public String runtimeMode() {
        return System.getProperty("runtime.mode");
    }

    public String dynamicProperty(String key) {
        return System.getProperty(key);
    }

    public URL packageResource() {
        return ProgrammaticReferenceConsumer.class.getResource("local.txt");
    }

    public URL testResource() {
        return ProgrammaticReferenceConsumer.class.getResource("/fixtures/test.json");
    }

    public ResourceBundle messages() {
        return ResourceBundle.getBundle("messages");
    }

    public Resource externalResource(ResourceLoader loader) {
        return loader.getResource("file:/tmp/quill-external.txt");
    }
}
