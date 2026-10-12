package org.treblereel.mcp.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KotlinCompilerCaptureTest {
    @TempDir Path root;
    @Test void capturesEffectiveOptionsPluginsAndEscapedPathsWithoutChangingBuild() throws Exception {
        var config = Xpp3DomBuilder.build(new java.io.StringReader("""
            <configuration><languageVersion>2.3</languageVersion><apiVersion>2.2</apiVersion><jvmTarget>17</jvmTarget>
            <args><arg>-Xcontext-parameters</arg></args><compilerPlugins><plugin>spring</plugin></compilerPlugins>
            <pluginOptions><option>all-open:annotation=probe.Open</option></pluginOptions></configuration>
            """));
        var values = KotlinCompilerCapture.settings(config, "2.4.21", List.of(root.resolve("plugin with spaces.jar")));
        assertEquals("2.3", values.getProperty("languageVersion")); assertEquals("2.2", values.getProperty("apiVersion"));
        assertEquals("17", values.getProperty("jvmTarget")); assertEquals("-Xcontext-parameters", values.getProperty("argument.0"));
        assertEquals("plugin:org.jetbrains.kotlin.allopen:preset=spring", values.getProperty("pluginOption.0"));
        assertEquals("plugin:org.jetbrains.kotlin.allopen:annotation=probe.Open", values.getProperty("pluginOption.1"));
        Path file = root.resolve("target/quill-kotlin-main.properties"); KotlinCompilerCapture.write(file, values);
        var restored = new Properties(); try(var reader = Files.newBufferedReader(file)) { restored.load(reader); }
        assertEquals(values, restored); assertFalse(Boolean.parseBoolean(values.getProperty("allowPlugins")));
    }
    @Test void unsupportedAndMissingPluginArtifactsRemainExplicit() throws Exception {
        var config = Xpp3DomBuilder.build(new java.io.StringReader("<configuration><compilerPlugins><plugin>custom</plugin></compilerPlugins></configuration>"));
        assertEquals("custom", KotlinCompilerCapture.settings(config, "2.4.21", List.of()).getProperty("unsupportedPlugins"));
        var known = Xpp3DomBuilder.build(new java.io.StringReader("<configuration><compilerPlugins><plugin>all-open</plugin></compilerPlugins></configuration>"));
        assertEquals("plugin artifacts missing", KotlinCompilerCapture.settings(known, "2.4.21", List.of()).getProperty("unsupportedPlugins"));
        assertEquals("2.4.21", KotlinCompilerCapture.settings(null, "2.4.21", List.of()).getProperty("compilerVersion"));
    }
}
