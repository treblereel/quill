package org.treblereel.mcp.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.io.StringReader;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaCompilerCaptureTest {
    @TempDir Path root;
    Properties settings(String xml, boolean test) throws Exception {
        return JavaCompilerCapture.settings(Xpp3DomBuilder.build(new StringReader(xml)), test);
    }
    @Test void testSettingsOverrideMainWithoutLosingCompilerFlags() throws Exception {
        String config = """
            <configuration><release>17</release><testRelease>21</testRelease><source>11</source><testSource>17</testSource>
            <target>11</target><testTarget>17</testTarget><compilerId>javac</compilerId><enablePreview>true</enablePreview>
            <generatedSourcesDirectory>/generated main</generatedSourcesDirectory><generatedTestSourcesDirectory>/generated test</generatedTestSourcesDirectory>
            <useModulePath>false</useModulePath></configuration>
            """;
        var main = settings(config, false); var test = settings(config, true);
        assertEquals("17", main.getProperty("release")); assertEquals("21", test.getProperty("release"));
        assertEquals("11", main.getProperty("source")); assertEquals("17", test.getProperty("source"));
        assertEquals("11", main.getProperty("target")); assertEquals("17", test.getProperty("target"));
        assertEquals("true", test.getProperty("enablePreview")); assertEquals("false", test.getProperty("useModulePath"));
        assertEquals("/generated test", test.getProperty("generatedSourcesDirectory"));
    }
    @Test void preservesArgumentOrderSpacesJpmsAndProcessorOptions() throws Exception {
        var values = settings("""
            <configuration><compilerArgs><arg>--add-exports</arg><arg>java.base/jdk.internal.misc=ALL-UNNAMED</arg>
            <arg>--patch-module</arg><arg>probe=/path with spaces</arg><arg>-Amessage=a b</arg><arg>--enable-preview</arg></compilerArgs>
            <annotationProcessors><processor>probe.Processor</processor></annotationProcessors>
            <annotationProcessorPaths><path><groupId>probe</groupId><artifactId>processor</artifactId><version>1</version><classifier>jdk17</classifier></path></annotationProcessorPaths>
            <compilerArguments><Xlint/><Alegacy>x y</Alegacy></compilerArguments><compilerArgument>-Araw='x y'</compilerArgument></configuration>
            """, false);
        assertEquals("probe=/path with spaces", values.getProperty("argument.3"));
        assertEquals("-Amessage=a b", values.getProperty("argument.4")); assertEquals("-Xlint", values.getProperty("argument.6"));
        assertEquals("x y", values.getProperty("argument.8")); assertEquals("true", values.getProperty("enablePreview"));
        assertEquals("probe.Processor", values.getProperty("processor.0"));
        assertEquals("probe:processor:1:jdk17", values.getProperty("processorArtifact.0"));
        assertEquals("declared_coordinates", values.getProperty("processorPathEvidence"));
        assertEquals("-Araw='x y'", values.getProperty("legacyCompilerArgument"));
    }
    @Test void emptyConfigurationDoesNotInventReleaseOrProcessorArtifacts() throws Exception {
        var values = settings("<configuration/>", true);
        assertNull(values.getProperty("release")); assertEquals("0", values.getProperty("processor.count"));
        assertEquals("0", values.getProperty("argument.count"));
    }
    @Test void fingerprintsDetectCreationDeletionAndSameSizeRestoredMtime() throws Exception {
        Path pom = Files.writeString(root.resolve("pom.xml"), "aaaa"); Path absent = root.resolve("missing");
        FileTime modified = Files.getLastModifiedTime(pom);
        var first = new Properties(); JavaCompilerCapture.fingerprints(first, List.of(pom, absent));
        Files.writeString(pom, "bbbb"); Files.setLastModifiedTime(pom, modified);
        var second = new Properties(); JavaCompilerCapture.fingerprints(second, List.of(pom, absent));
        assertNotEquals(first.getProperty("buildInput.sha256.0"), second.getProperty("buildInput.sha256.0"));
        assertEquals("absent", second.getProperty("buildInput.sha256.1"));
        Files.writeString(absent, "created"); assertNotEquals("absent", JavaCompilerCapture.hash(absent));
        Files.delete(pom); assertEquals("absent", JavaCompilerCapture.hash(pom));
    }
}
