package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

@Tag("e2e")
class KotlinMavenIT {

    private static final Path TESTS_ROOT = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path PROJECT_FIXTURE = TESTS_ROOT.resolve("kotlin-maven");
    private static final Path MAVEN_WRAPPER = TESTS_ROOT.getParent().resolve("mvnw");

    @TempDir Path tempDir;

    @Test
    void indexesKotlinCompilerOutputAndMetadata() throws Exception {
        Path project = TestProjectCopies.copyFixture(
                PROJECT_FIXTURE, tempDir.resolve("kotlin-maven"));
        compile(project);

        InitCommand command = new InitCommand();
        command.projectPath = project;
        command.indexOnly = true;
        command.call();

        Path db = ProjectIndexStore.findDbForHead(project);
        assertNotNull(db);
        var jdbi = QuillDatabase.open(db);
        var invoice = IndexReader.findClassByName(jdbi,
                "org.treblereel.mcp.fixture.kotlin.Invoice").orElseThrow();
        assertTrue(invoice.sourceFile().endsWith("Billing.kt"));
        assertTrue(invoice.sourceTokens() > 0);
        var facade = IndexReader.findClassByName(jdbi,
                "org.treblereel.mcp.fixture.kotlin.BillingKt").orElseThrow();
        assertEquals(invoice.sourceFile(), facade.sourceFile());
        var invoiceDeclarations = IndexReader.findKotlinDeclarations(jdbi, invoice.id());
        assertEquals("DATA_CLASS", invoiceDeclarations.getFirst().kind());
        var facadeDeclarations = IndexReader.findKotlinDeclarations(jdbi, facade.id());
        assertEquals("FILE_FACADE", facadeDeclarations.getFirst().kind());
        var charge = facadeDeclarations.stream()
                .filter(value -> value.name().equals("charge")).findFirst().orElseThrow();
        assertTrue(charge.hasDefaultParameters());
        var fetch = facadeDeclarations.stream()
                .filter(value -> value.name().equals("fetchInvoice")).findFirst().orElseThrow();
        assertTrue(fetch.isSuspend());

        var metadata = IndexReader.getMetadata(jdbi);
        assertTrue(Integer.parseInt(metadata.get("kotlin_metadata_classes")) >= 4);
        assertEquals("0", metadata.get("kotlin_metadata_fallbacks"));
    }

    private static void compile(Path project) throws Exception {
        Process process = new ProcessBuilder(MAVEN_WRAPPER.toString(), "-q",
                "-f", project.resolve("pom.xml").toString(), "clean", "compile")
                .directory(project.toFile())
                .inheritIO()
                .start();
        assertTrue(process.waitFor(2, TimeUnit.MINUTES),
                "Kotlin Maven fixture compilation timed out");
        assertEquals(0, process.exitValue(), "Kotlin Maven fixture compilation failed");
    }
}
