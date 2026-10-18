package com.rk.terminal.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedTerminalHostScriptTest {
    @Test
    fun finalProotProcessKeepsCallerStdio() {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val script = listOf(
            workingDirectory.resolve(
                "ReTerminal/core/main/src/main/assets/init-host.sh"
            ),
            workingDirectory.resolve("src/main/assets/init-host.sh")
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Embedded terminal init-host.sh asset is missing.")

        val directory = Files.createTempDirectory("omnibot-host-stdio-").toFile()
        try {
            val files = directory.resolve("files").apply { mkdirs() }
            val rootfs = directory.resolve("local/alpine").apply { mkdirs() }
            rootfs.resolve("bin").mkdirs()
            rootfs.resolve("bin/sh").writeText("fixture")
            rootfs.resolve("etc").mkdirs()
            rootfs.resolve("etc/os-release").writeText("ID=alpine\n")
            rootfs.resolve(".omnibot-rootfs-ready").writeText("alpine\n")
            files.resolve("proot").writeText(
                """
                IFS= read -r value
                printf 'stdout:%s\n' "${'$'}value"
                printf 'stderr:%s\n' "${'$'}value" >&2
                exit 7
                """.trimIndent(),
            )
            val hostScript = directory.resolve("init-host.sh").apply { writeText(script) }
            val builder = ProcessBuilder("/bin/sh", hostScript.absolutePath)
            builder.environment().putAll(mapOf(
                "PREFIX" to directory.absolutePath,
                "LINKER" to "/bin/sh",
                "OMNIBOT_TERMINAL_DISTRIBUTION" to "alpine",
            ))
            val process = builder.start()
            try {
                process.outputStream.bufferedWriter().use { it.write("stdin-probe\n") }
                assertTrue("Host shell did not finish", process.waitFor(5, TimeUnit.SECONDS))
                assertEquals(7, process.exitValue())
                assertEquals("stdout:stdin-probe", process.inputStream.bufferedReader().readText().trim())
                assertEquals("stderr:stdin-probe", process.errorStream.bufferedReader().readText().trim())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
