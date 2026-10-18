package cn.com.omnimind.bot.agent.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedAgentConfigFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `Codex settings open before the terminal home or rootfs exists`() = runBlocking {
        val home = File(temporary.root, "not-installed/root")
        val payload = CodexConfigAdapter.readConfig(
            AgentProviderMappingInput(
                agentId = "codex-acp",
                provider = null,
                model = null,
                harnessAdapter = AcpHarnessAdapters.codex,
            ),
            object : AgentConfigFileAccess {
                override suspend fun read(path: String, executorKey: String): String =
                    readManagedAgentConfigFile(home, path) ?: error("Shell must not start")
                override suspend fun write(path: String, content: String, executorKey: String) =
                    error("Reading configuration must not write it")
            },
        )
        assertEquals("codex", payload["kind"])
        assertEquals("api", payload["authMode"])
        assertEquals("", payload["apiKey"])
        assertFalse(home.exists())
    }

    @Test
    fun `regular saved configuration is read without changing it`() {
        val home = temporary.newFolder("home")
        val file = File(home, ".config/example/config.json")
        file.parentFile.mkdirs()
        val content = "{\"model\":\"fixture-model\"}\n"
        file.writeText(content)
        assertEquals(content, readManagedAgentConfigFile(home, "/root/.config/example/config.json"))
        assertEquals(content, file.readText())
    }

    @Test
    fun `guest symbolic links and paths outside home retain the shell reader`() {
        val home = temporary.newFolder("links")
        val target = temporary.newFolder("guest-link-target")
        Files.createSymbolicLink(File(home, ".config").toPath(), target.toPath())
        assertNull(readManagedAgentConfigFile(home, "/root/.config/settings.json"))
        assertNull(readManagedAgentConfigFile(home, "/etc/settings.json"))
        assertNull(readManagedAgentConfigFile(home, "/root/../settings.json"))
    }

    @Test(expected = IOException::class)
    fun `a directory is not silently treated as missing configuration`() {
        val home = temporary.newFolder("directory")
        File(home, ".config").mkdirs()
        readManagedAgentConfigFile(home, "/root/.config")
    }
}
