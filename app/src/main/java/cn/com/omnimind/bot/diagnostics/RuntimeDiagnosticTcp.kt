package cn.com.omnimind.bot.diagnostics

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** The pin identifies this temporary receiver; no DNS ownership or public CA is needed. */
internal data class RuntimeDiagnosticTarget(
    val host: String,
    val https: HttpUrl? = null,
    val port: Int = -1,
    val certificateSha256: String? = null,
) {
    companion object {
        fun parse(value: String): RuntimeDiagnosticTarget {
            val text = value.trim()
            if (text.startsWith("tls://")) {
                val uri = runCatching { URI(text) }.getOrNull()
                require(uri != null && !uri.host.isNullOrEmpty() && uri.port in 1..65535 &&
                    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                    uri.rawPath.matches(Regex("/[a-f0-9]{64}"))) {
                    "TCP 诊断地址须包含端口和接收端证书指纹，请重新导入诊断链接"
                }
                return RuntimeDiagnosticTarget(uri.host, port = uri.port, certificateSha256 = uri.rawPath.drop(1))
            }
            val url = text.toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
                url.query == null && url.fragment == null) {
                "请填写 HTTPS 接收地址或导入 TCP 诊断链接"
            }
            return RuntimeDiagnosticTarget(url.host, https = url)
        }
    }
}

/** One bounded snapshot over authenticated TLS, with no HTTP, commands, or inbound app actions. */
internal object RuntimeDiagnosticTcp {
    const val MAX_BYTES = 1_048_576

    internal fun trustManager(pin: String): X509TrustManager {
        require(pin.matches(Regex("[a-f0-9]{64}")))
        val expected = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are not accepted")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("Missing receiver certificate")
                leaf.checkValidity()
                if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(leaf.encoded))) {
                    throw CertificateException("Receiver certificate mismatch")
                }
            }
        }
    }

    fun socket(target: RuntimeDiagnosticTarget): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager(requireNotNull(target.certificateSha256))), null)
        return (context.socketFactory.createSocket() as SSLSocket).apply {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            soTimeout = 10_000
        }
    }

    fun send(socket: SSLSocket, target: RuntimeDiagnosticTarget, code: String, body: String) {
        val payload = body.toByteArray(Charsets.UTF_8)
        require(payload.size in 1..MAX_BYTES)
        require(code.matches(Regex("[A-Za-z0-9_-]{32,128}")))
        socket.connect(InetSocketAddress(target.host, target.port), 10_000)
        socket.startHandshake() // No diagnostic code or report is sent until the pin is verified.
        val output = DataOutputStream(socket.outputStream)
        output.writeBytes("OBD1")
        output.writeShort(code.length)
        output.write(code.toByteArray(Charsets.US_ASCII))
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
        if (DataInputStream(socket.inputStream).readUnsignedByte() != 0) {
            throw IOException("Diagnostic report rejected")
        }
    }
}
