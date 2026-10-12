package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenKotlinSettingsTest {
    @TempDir Path root;
    Properties load(String set) throws Exception { var values = new Properties(); try (var r = Files.newBufferedReader(root.resolve("target/quill-kotlin-" + set + ".properties"))) { values.load(r); } return values; }
    @Test void effectiveMergedSettingsAndExecutionOverridesAreCapturedDuringInitialization() throws Exception {
        Path pom = root.resolve("pom.xml"); Files.writeString(pom, "<project/>");
        Path effective = root.resolve("effective.xml"); Files.writeString(effective, """
            <project><groupId>probe</groupId><artifactId>module</artifactId><version>1</version>
            <properties><language>2.3</language><api>${language}</api></properties>
            <build><plugins><plugin><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId><version>2.4.21</version>
            <configuration><languageVersion>${language}</languageVersion><apiVersion>${api}</apiVersion><jvmTarget>17</jvmTarget></configuration>
            <executions><execution><goals><goal>test-compile</goal></goals><configuration><apiVersion>2.2</apiVersion></configuration></execution></executions>
            </plugin></plugins></build></project>
            """);
        MavenKotlinSettings.capture(effective, root, Map.of("probe:module:1", root));
        assertEquals("2.3", load("main").getProperty("languageVersion")); assertEquals("2.3", load("main").getProperty("apiVersion"));
        assertEquals("2.2", load("test").getProperty("apiVersion")); assertEquals("17", load("main").getProperty("jvmTarget"));
        var modified = Files.getLastModifiedTime(root.resolve("target/quill-kotlin-main.properties"));
        MavenKotlinSettings.capture(effective, root, Map.of("probe:module:1", root));
        assertEquals(modified, Files.getLastModifiedTime(root.resolve("target/quill-kotlin-main.properties")));
        Files.writeString(root.resolve("target/quill-kotlin-main.properties"), "buildInput.count=broken\n");
        MavenKotlinSettings.capture(effective, root, Map.of("probe:module:1", root));
        assertEquals("2.3", load("main").getProperty("languageVersion"));
        Files.writeString(root.resolve("target/quill-kotlin-main.properties"), "buildInput.count=1\n");
        MavenKotlinSettings.capture(effective, root, Map.of("probe:module:1", root));
        assertEquals("2.3", load("main").getProperty("languageVersion"));
    }
    @Test void ambiguousExecutionsAndExternalParentsNeedBuildEvidence() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path effective = root.resolve("effective.xml"); Files.writeString(effective, """
            <projects><project><groupId>probe</groupId><artifactId>module</artifactId><version>1</version>
            <parent><groupId>external</groupId><artifactId>parent</artifactId><version>1</version><relativePath/></parent>
            <build><plugins><plugin><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId><version>2.4.21</version>
            <executions><execution><goals><goal>compile</goal></goals><configuration><apiVersion>2.2</apiVersion></configuration></execution>
            <execution><goals><goal>compile</goal></goals><configuration><apiVersion>2.3</apiVersion></configuration></execution></executions>
            </plugin></plugins></build></project></projects>
            """);
        MavenKotlinSettings.capture(effective, root, Map.of("probe:module:1", root));
        assertNotNull(load("main").getProperty("configurationError"));
    }
    @Test void reactorParentOutsideModuleAncestorsIsFingerprinted() throws Exception {
        Path module = root.resolve("child"), parent = root.resolve("parent");
        Files.createDirectories(module); Files.createDirectories(parent);
        Files.writeString(module.resolve("pom.xml"), "<project/>"); Files.writeString(parent.resolve("pom.xml"), "<project/>");
        Path effective = root.resolve("effective.xml"); Files.writeString(effective, """
            <project><groupId>probe</groupId><artifactId>child</artifactId><version>1</version>
            <parent><groupId>probe</groupId><artifactId>parent</artifactId><version>1</version></parent>
            <build><plugins><plugin><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId><version>2.4.21</version></plugin></plugins></build></project>
            """);
        MavenKotlinSettings.capture(effective, root, Map.of("probe:child:1", module, "probe:parent:1", parent));
        var captured = new Properties(); try (var reader = Files.newBufferedReader(module.resolve("target/quill-kotlin-main.properties"))) { captured.load(reader); }
        assertNull(captured.getProperty("configurationError"));
        assertTrue(captured.values().contains(parent.resolve("pom.xml").toString()));
    }
}
