import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.function.Executable
import kotlinx.serialization.json.*

class ProbeMutationTest {
    private data class Result(val exit: Int, val output: String, val error: String) {
        fun json(): JsonObject {
            assertEquals(0, exit, error)
            return Json.parseToJsonElement(output).jsonObject
        }
    }

    private fun runProbe(input: String): Result {
        // Global Standalone services can be disposed once at JVM shutdown. Each independent
        // probe uses a child process; all mutations inside that probe retain one session.
        val root = Files.createTempDirectory("quill-probe-test-")
        val definition = root.resolve("cases.json")
        val output = root.resolve("stdout.json")
        val error = root.resolve("stderr.log")
        var process: Process? = null
        try {
            Files.writeString(definition, input)
            process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx512m", "-Xss8m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "ProbeKt", definition.toString()).redirectOutput(output.toFile()).redirectError(error.toFile()).start()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Probe timed out")
            return Result(process.exitValue(), Files.readString(output), Files.readString(error))
        } finally {
            process?.let { if (it.isAlive) { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) } }
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test @Timeout(40)
    fun retainedSessionMatchesAllRequiredMutationStates() {
        val input = javaClass.getResource("/mutations.json")!!.readText()
        val report = runProbe(input).json()
        val cases = report.getValue("cases").jsonArray.map { it.jsonObject }
        val required = cases.filter { !it.getValue("negative_control").jsonPrimitive.boolean }
        assertEquals(35, required.size)
        assertEquals(1, report.getValue("session_builds").jsonPrimitive.int)
        assertFalse(report.getValue("production_enabled").jsonPrimitive.boolean)
        assertAll(required.map { case -> Executable {
            assertTrue(case.getValue("correct").jsonPrimitive.boolean, case.toString())
            assertTrue(case.getValue("psi_matches_disk").jsonPrimitive.boolean, case.toString())
        } })
        val control = cases.single { it.getValue("negative_control").jsonPrimitive.boolean }
        assertEquals("kotlin/Int", control.getValue("actual").jsonPrimitive.content)
        assertFalse(control.getValue("correct").jsonPrimitive.boolean)
        assertFalse(control.getValue("psi_matches_disk").jsonPrimitive.boolean)
    }

    @Test @Timeout(40)
    fun analysisFailureIsRecordedAndCannotPassCorrectnessGate() {
        val report = runProbe("""[{"case":"missing_query_marker","sources":{"Call.kt":"fun pick() = 1"},"query_file":"Call.kt","expected_type":"kotlin/Int","expected_parameters":[]}]""").json()
        val case = report.getValue("cases").jsonArray.single().jsonObject
        assertFalse(case.getValue("correct").jsonPrimitive.boolean)
        assertTrue(case.getValue("actual").jsonPrimitive.content.startsWith("error:"))
        assertTrue(case.getValue("failure").jsonPrimitive.content.isNotBlank())
    }

    @Test @Timeout(40)
    fun snapshotCannotWriteOutsideItsTemporarySourceRoot() {
        val result = runProbe("""[{"sources":{"../Escape.kt":"fun escape() = 1"}}]""")
        assertNotEquals(0, result.exit)
        assertTrue(result.error.contains("IllegalArgumentException"), result.error)
    }
}
