package cn.com.omnimind.bot.diagnostics

import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Test

class RuntimeDiagnosticTcpTest {
    @Test fun rawTlsPinsReceiverBeforeSendingBoundedSnapshotAndRejectsOtherCertificate() {
        val temp = Files.createTempDirectory("diagnostic-tls-test")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val file = temp.resolve("fixture.p12")
            val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=diagnostic-test", "-validity", "1", "-storetype", "PKCS12", "-keystore", file.toString(),
                "-storepass", "fixture-only", "-keypass", "fixture-only").redirectErrorStream(true).redirectOutput(temp.resolve("keytool.log").toFile()).start()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, "fixture-only".toCharArray()) } }
            val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-only".toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
            val certificate = keys.getCertificate("fixture") as X509Certificate
            val pin = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
            (context.serverSocketFactory.createServerSocket(0) as SSLServerSocket).use { server ->
                server.soTimeout = 5000
                val received = executor.submit<String> {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.inputStream)
                        val magic = ByteArray(4).also(input::readFully)
                        assertEquals("OBD1", String(magic))
                        val code = ByteArray(input.readUnsignedShort()).also(input::readFully)
                        assertEquals("a".repeat(43), String(code))
                        val body = ByteArray(input.readInt()).also(input::readFully)
                        socket.outputStream.write(0)
                        String(body)
                    }
                }
                val target = RuntimeDiagnosticTarget.parse("tls://127.0.0.1:${server.localPort}/$pin")
                RuntimeDiagnosticTcp.socket(target).use { RuntimeDiagnosticTcp.send(it, target, "a".repeat(43), "{\"fixture\":true}") }
                assertEquals("{\"fixture\":true}", received.get(5, TimeUnit.SECONDS))
                val rejected = executor.submit<Boolean> {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5000
                        try { socket.startHandshake(); socket.inputStream.read() == -1 } catch (_: SSLException) { true }
                    }
                }
                val wrong = target.copy(certificateSha256 = "0".repeat(64))
                try {
                    RuntimeDiagnosticTcp.socket(wrong).use { RuntimeDiagnosticTcp.send(it, wrong, "a".repeat(43), "must never arrive") }
                    fail("Unpinned receiver accepted")
                } catch (_: SSLException) { /* Expected: no application bytes sent. */ }
                assertTrue(rejected.get(5, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
            temp.toFile().deleteRecursively()
        }
    }
}
