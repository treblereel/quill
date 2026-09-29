package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.QuillTopCommand;
import picocli.CommandLine;

class CliCommandTest {

    @Test
    void everySubcommandProvidesStandardHelpOptions() {
        for (String command : List.of("init", "update", "status", "doctor", "clean")) {
            StringWriter output = new StringWriter();
            CommandLine cli = new CommandLine(new QuillTopCommand());
            cli.setOut(new PrintWriter(output));

            int exitCode = cli.execute(command, "--help");

            assertEquals(CommandLine.ExitCode.OK, exitCode, command);
            assertTrue(output.toString().contains("Usage: quill " + command), output.toString());
        }
    }

    @Test
    void missingCommandReturnsUsageExitCode() {
        assertEquals(CommandLine.ExitCode.USAGE,
                new CommandLine(new QuillTopCommand()).execute());
    }

    @Test
    void rootHelpDocumentsStructuredDebugMode() {
        StringWriter output = new StringWriter();
        CommandLine cli = new CommandLine(new QuillTopCommand());
        cli.setOut(new PrintWriter(output));

        assertEquals(CommandLine.ExitCode.OK, cli.execute("--help"));
        assertTrue(output.toString().contains("--debug"));
        assertTrue(output.toString().contains("--debug-directory"));
    }

    @Test
    void expectedCommandFailureReturnsSoftwareExitCode(@TempDir Path project) throws Exception {
        Files.createFile(project.resolve("pom.xml"));

        assertEquals(CommandLine.ExitCode.SOFTWARE,
                new CommandLine(new InitCommand()).execute("--project", project.toString()));
        assertEquals(CommandLine.ExitCode.SOFTWARE,
                new CommandLine(new StatusCommand()).execute("--project", project.toString()));
        assertEquals(CommandLine.ExitCode.SOFTWARE,
                new CommandLine(new DoctorCommand()).execute("--project", project.toString()));
    }
}
