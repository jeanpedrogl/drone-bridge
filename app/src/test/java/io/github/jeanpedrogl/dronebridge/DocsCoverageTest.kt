package io.github.jeanpedrogl.dronebridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the documentation honest: every RPC command registered in the app must appear in
 * `PROTOCOLO.md` (the reference) and in `MANUAL_DESENVOLVEDOR.md` (with an example), and the
 * speed limits / watchdog / video defaults quoted there must match the constants in the code.
 * Adding a `cmd(...)` line without documenting it, or changing a limit without updating the
 * docs, fails here.
 */
class DocsCoverageTest {
    // Gradle runs unit tests from the module directory (app/); allow the project root too.
    private fun projectFile(path: String): File =
        listOf(File(path), File("..", path), File("app", path)).firstOrNull { it.exists() }
            ?: throw AssertionError("Arquivo não encontrado: $path (cwd=${File(".").absolutePath})")

    private val sources = "src/main/java/io/github/jeanpedrogl/dronebridge"

    private fun registeredCommands(): Set<String> {
        val builder = Regex("""\b(?:cmd|flightCmd|gimbalCmd|cameraCmd)\(\s*"([a-z_]+\.[a-z_]+)"""")
        val builtIn = Regex("""RpcCommand\(\s*"([a-z_]+\.[a-z_]+)"""")
        return builder.findAll(projectFile("$sources/DroneCommands.kt").readText()).map { it.groupValues[1] }.toSet() +
            builtIn.findAll(projectFile("$sources/RpcRegistry.kt").readText()).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun theScanFindsTheCommands() {
        // Guards the regexes above: if they silently match nothing, the checks below would pass vacuously.
        assertTrue("achou só ${registeredCommands().size}", registeredCommands().size >= 40)
    }

    @Test
    fun everyCommandIsInTheProtocolReference() = assertDocumented("PROTOCOLO.md")

    @Test
    fun everyCommandHasAnExampleInTheManual() = assertDocumented("MANUAL_DESENVOLVEDOR.md")

    @Test
    fun speedLimitsAndVideoDefaultsInTheDocsMatchTheCode() {
        val controller = projectFile("$sources/DroneController.kt").readText()
        val video = projectFile("$sources/VideoRelayConfig.kt").readText()
        fun number(source: String, name: String): String =
            Regex("""const val $name = ([0-9.]+)""").find(source)?.groupValues?.get(1)?.removeSuffix(".0")
                ?: throw AssertionError("Constante $name não encontrada")
        val expected = listOf(
            "${number(controller, "MAX_HORIZONTAL_MS")} m/s",
            "${number(controller, "MAX_VERTICAL_MS")} m/s",
            "${number(controller, "MAX_YAW_DPS")} °/s",
            "${number(controller, "MAX_GIMBAL_DPS")} °/s",
            "${number(controller, "COMMAND_TIMEOUT_MS")} ms",
            "${number(video, "DEFAULT_FPS")} fps, ${number(video, "DEFAULT_MAX_WIDTH")} px, " +
                "qualidade ${number(video, "DEFAULT_JPEG_QUALITY")}",
        )
        for (doc in listOf("PROTOCOLO.md", "MANUAL_DESENVOLVEDOR.md")) {
            val text = projectFile(doc).readText()
            val missing = expected.filter { it !in text }
            assertTrue("$doc não menciona os valores do código: $missing", missing.isEmpty())
        }
    }

    private fun assertDocumented(doc: String) {
        val text = projectFile(doc).readText()
        val missing = registeredCommands().filter { "`$it`" !in text }
        assertTrue("Comandos sem documentação em $doc: $missing", missing.isEmpty())
    }
}
