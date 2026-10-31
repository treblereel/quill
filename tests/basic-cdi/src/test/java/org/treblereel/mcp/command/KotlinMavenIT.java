package org.treblereel.mcp.command;

import org.treblereel.mcp.index.ProjectIndexStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.db.SymbolRepository;
import org.treblereel.mcp.db.IndexRepository;
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

        initialize(project);

        Path db = ProjectIndexStore.findDbForHead(project);
        assertNotNull(db);
        var jdbi = QuillDatabase.open(db);
        var invoice = jdbi.withHandle(repositoryHandle1 -> new SymbolRepository(repositoryHandle1).findClassByName("org.treblereel.mcp.fixture.kotlin.Invoice")).orElseThrow();
        assertTrue(invoice.sourceFile().endsWith("Billing.kt"));
        assertTrue(invoice.sourceTokens() > 0);
        var facade = jdbi.withHandle(repositoryHandle2 -> new SymbolRepository(repositoryHandle2).findClassByName("org.treblereel.mcp.fixture.kotlin.BillingKt")).orElseThrow();
        assertEquals(invoice.sourceFile(), facade.sourceFile());
        var invoiceDeclarations = jdbi.withHandle(repositoryHandle3 -> new SymbolRepository(repositoryHandle3).findKotlinDeclarations(invoice.id()));
        assertEquals("DATA_CLASS", invoiceDeclarations.getFirst().kind());
        var stateAccessors = invoiceDeclarations.stream()
                .filter(value -> value.kind().equals("PROPERTY"))
                .filter(value -> value.name().equals("state")).toList();
        assertTrue(stateAccessors.stream().anyMatch(value ->
                "state".equals(value.jvmName())
                        && "Ljava/lang/String;".equals(value.descriptor())));
        assertTrue(stateAccessors.stream().anyMatch(value ->
                "getState".equals(value.jvmName())
                        && "()Ljava/lang/String;".equals(value.descriptor())));
        assertTrue(stateAccessors.stream().anyMatch(value ->
                "setState".equals(value.jvmName())
                        && "(Ljava/lang/String;)V".equals(value.descriptor())));
        var facadeDeclarations = jdbi.withHandle(repositoryHandle4 -> new SymbolRepository(repositoryHandle4).findKotlinDeclarations(facade.id()));
        assertEquals("FILE_FACADE", facadeDeclarations.getFirst().kind());
        var charge = facadeDeclarations.stream()
                .filter(value -> value.name().equals("charge")).findFirst().orElseThrow();
        assertTrue(charge.hasDefaultParameters());
        var greeting = facadeDeclarations.stream()
                .filter(value -> value.name().equals("greeting")).findFirst().orElseThrow();
        assertTrue(greeting.hasDefaultParameters());
        var loadInvoice = facadeDeclarations.stream()
                .filter(value -> value.name().equals("loadInvoice")).findFirst().orElseThrow();
        assertTrue(loadInvoice.jvmName().startsWith("loadInvoice-"));
        assertFalse(loadInvoice.jvmName().equals(loadInvoice.name()));
        assertEquals("(Ljava/lang/String;)Lorg/treblereel/mcp/fixture/kotlin/Invoice;",
                loadInvoice.descriptor());
        var fetch = facadeDeclarations.stream()
                .filter(value -> value.name().equals("fetchInvoice")).findFirst().orElseThrow();
        assertTrue(fetch.isSuspend());
        var facadeMembers = jdbi.withHandle(repositoryHandle5 -> new SymbolRepository(repositoryHandle5).findClassMembers(facade.id()));
        assertFalse(facadeMembers.stream().anyMatch(value ->
                value.name().equals("charge$default")));
        assertFalse(facadeMembers.stream().anyMatch(value ->
                value.name().equals("greeting$default")));
        assertFalse(facadeDeclarations.stream().anyMatch(value ->
                value.name().endsWith("$default")),
                "Synthetic default bridges must not become source declarations");

        var invoiceId = jdbi.withHandle(repositoryHandle6 -> new SymbolRepository(repositoryHandle6).findClassByName("org.treblereel.mcp.fixture.kotlin.InvoiceId")).orElseThrow();
        assertEquals("VALUE_CLASS",
                jdbi.withHandle(repositoryHandle7 -> new SymbolRepository(repositoryHandle7).findKotlinDeclarations(invoiceId.id())).getFirst().kind());

        var propertyShapes = jdbi.withHandle(repositoryHandle8 -> new SymbolRepository(repositoryHandle8).findClassByName("org.treblereel.mcp.fixture.kotlin.PropertyShapes")).orElseThrow();
        var propertyDeclarations = jdbi.withHandle(repositoryHandle9 -> new SymbolRepository(repositoryHandle9).findKotlinDeclarations(propertyShapes.id())).stream()
                .filter(value -> value.kind().equals("PROPERTY")).toList();
        assertTrue(propertyDeclarations.stream().anyMatch(value ->
                value.name().equals("isActive") && value.jvmName().equals("isActive")
                        && value.descriptor().equals("Z")));
        assertTrue(propertyDeclarations.stream().anyMatch(value ->
                value.name().equals("isActive") && value.jvmName().equals("isActive")
                        && value.descriptor().equals("()Z")));
        assertTrue(propertyDeclarations.stream().anyMatch(value ->
                value.name().equals("exposed") && value.jvmName().equals("exposed")
                        && value.descriptor().equals("Ljava/lang/String;")));
        assertFalse(propertyDeclarations.stream().anyMatch(value ->
                value.name().equals("exposed") && value.jvmName().startsWith("get")));
        assertTrue(propertyDeclarations.stream().anyMatch(value ->
                value.name().equals("delegated") && value.delegated()
                        && value.jvmName().equals("getDelegated")
                        && value.descriptor().equals("()Ljava/lang/String;")));

        var companion = jdbi.withHandle(repositoryHandle10 -> new SymbolRepository(repositoryHandle10).findClassByName("org.treblereel.mcp.fixture.kotlin.PropertyShapes$Companion")).orElseThrow();
        assertTrue(jdbi.withHandle(repositoryHandle11 -> new SymbolRepository(repositoryHandle11).findKotlinDeclarations(companion.id())).stream()
                .anyMatch(value -> value.kind().equals("PROPERTY")
                        && value.name().equals("PREFIX")));
        assertTrue(jdbi.withHandle(repositoryHandle12 -> new SymbolRepository(repositoryHandle12).findClassMembers(propertyShapes.id())).stream()
                .anyMatch(value -> value.kind().equals("FIELD")
                        && value.name().equals("PREFIX")));

        var multifileFacade = jdbi.withHandle(repositoryHandle13 -> new SymbolRepository(repositoryHandle13).findClassByName("org.treblereel.mcp.fixture.kotlin.BillingApi")).orElseThrow();
        assertTrue(multifileFacade.sourceFile().endsWith("BillingQuotes.kt"));
        assertEquals("source", multifileFacade.origin());
        assertEquals("source", jdbi.withHandle(repositoryHandle14 -> new IndexRepository(repositoryHandle14).findClassOccurrencesByClassIds(java.util.List.of(multifileFacade.id())))
                .get(multifileFacade.id()).getFirst().origin());
        assertEquals("MULTIFILE_FACADE",
                jdbi.withHandle(repositoryHandle15 -> new SymbolRepository(repositoryHandle15).findKotlinDeclarations(multifileFacade.id()))
                        .getFirst().kind());
        var quotesPart = jdbi.withHandle(repositoryHandle16 -> new SymbolRepository(repositoryHandle16).findClassByName("org.treblereel.mcp.fixture.kotlin.BillingApi__BillingQuotesKt"))
                .orElseThrow();
        assertTrue(quotesPart.sourceFile().endsWith("BillingQuotes.kt"));
        assertTrue(jdbi.withHandle(repositoryHandle17 -> new SymbolRepository(repositoryHandle17).findKotlinDeclarations(quotesPart.id())).stream()
                .anyMatch(value -> value.name().equals("quote")));
        var validationPart = jdbi.withHandle(repositoryHandle18 -> new SymbolRepository(repositoryHandle18).findClassByName("org.treblereel.mcp.fixture.kotlin.BillingApi__BillingValidationKt"))
                .orElseThrow();
        assertTrue(validationPart.sourceFile().endsWith("BillingValidation.kt"));
        assertTrue(jdbi.withHandle(repositoryHandle19 -> new SymbolRepository(repositoryHandle19).findKotlinDeclarations(validationPart.id())).stream()
                .anyMatch(value -> value.name().equals("validateInvoice")));

        var metadata = jdbi.withHandle(repositoryHandle20 -> new IndexRepository(repositoryHandle20).getMetadata());
        assertTrue(Integer.parseInt(metadata.get("kotlin_metadata_classes")) >= 7);
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

    private static void initialize(Path project) throws Exception {
        if (!Boolean.getBoolean("native.tests.required")) {
            InitCommand command = new InitCommand();
            command.projectPath = project;
            command.indexOnly = true;
            command.call();
            return;
        }
        Path repositoryRoot = TESTS_ROOT.getParent();
        Path nativeImage = repositoryRoot.resolve(BuildSystem.isWindows()
                ? "quill-app/target/quill.exe" : "quill-app/target/quill");
        assertTrue(java.nio.file.Files.isExecutable(nativeImage),
                "Native quality gate requires an executable image at " + nativeImage);
        Process process = new ProcessBuilder(nativeImage.toString(), "init", "--project",
                project.toString(), "--index-only")
                .directory(project.toFile())
                .inheritIO()
                .start();
        assertTrue(process.waitFor(2, TimeUnit.MINUTES),
                "Native Kotlin indexing timed out");
        assertEquals(0, process.exitValue(), "Native Kotlin indexing failed");
    }
}
