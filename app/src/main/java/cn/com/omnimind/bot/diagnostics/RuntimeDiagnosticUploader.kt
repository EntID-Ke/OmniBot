package cn.com.omnimind.bot.diagnostics

import android.os.Build
import android.os.SystemClock
import cn.com.omnimind.baselib.util.RuntimeLogEntry
import cn.com.omnimind.baselib.util.RuntimeLogStore
import cn.com.omnimind.bot.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.net.Socket

/** User-started, twenty-minute, outbound diagnostics. No account or MCP access. */
internal object RuntimeDiagnosticUploader {
    private const val DURATION_MS = 20 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val lock = Any()
    private class Session(val endpoint: RuntimeDiagnosticTarget, val code: String) {
        val id = UUID.randomUUID().toString()
        val deadline = SystemClock.elapsedRealtime() + DURATION_MS
        var job: Job? = null
        var call: Call? = null
        var socket: Socket? = null
        var lastSentAt: Long? = null
        var sentCount = 0
        var error: String? = null
    }
    private var session: Session? = null

    internal fun validateEndpoint(value: String): RuntimeDiagnosticTarget = RuntimeDiagnosticTarget.parse(value)

    fun start(endpoint: String, code: String): Map<String, Any?> {
        val link = if (endpoint.trim().startsWith("omnibotdiagnostics:")) {
            requireNotNull(RuntimeDiagnosticLink.parse(endpoint.trim())) { "诊断链接无效，请重新复制完整链接" }
        } else null
        val url = validateEndpoint(link?.get("endpoint") ?: endpoint)
        val accessCode = link?.get("code") ?: code
        require(accessCode.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "诊断码须为 32–128 位字母、数字、下划线或连字符" }
        synchronized(lock) {
            stopLocked()
            val current = Session(url, accessCode)
            session = current
            current.job = scope.launch {
                while (isActive && SystemClock.elapsedRealtime() < current.deadline) {
                    try {
                        val body = RuntimeDiagnosticReport.encode(current.id, deviceInfo(), RuntimeLogStore.listRecent(100))
                        val https = current.endpoint.https
                        val error = if (https != null) {
                            val call = createUploadCall(https, current.code, body)
                            synchronized(lock) {
                                if (session !== current) return@launch
                                current.call = call
                            }
                            call.execute().use { response ->
                                if (response.isSuccessful) null else "HTTP ${response.code}"
                            }
                        } else {
                            RuntimeDiagnosticTcp.socket(current.endpoint).use { socket ->
                                synchronized(lock) {
                                    if (session !== current) return@launch
                                    current.socket = socket
                                }
                                RuntimeDiagnosticTcp.send(socket, current.endpoint, current.code, body)
                            }
                            null
                        }
                        synchronized(lock) {
                            if (error == null) {
                                current.lastSentAt = System.currentTimeMillis()
                                current.sentCount++
                            }
                            current.error = error
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // Do not expose a receiver URL, code, response body, or request in logs/UI.
                        synchronized(lock) { current.error = error.javaClass.simpleName }
                    } finally {
                        synchronized(lock) { current.call = null; current.socket = null }
                    }
                    delay(10_000)
                }
                synchronized(lock) { if (session === current) session = null }
            }
            return stateLocked()
        }
    }

    fun state(): Map<String, Any?> = synchronized(lock) { stateLocked() }

    internal fun createUploadCall(endpoint: HttpUrl, code: String, body: String): Call =
        client.newCall(Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $code")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build())

    fun stop(): Map<String, Any?> = synchronized(lock) {
        stopLocked()
        stateLocked()
    }

    private fun stopLocked() {
        val previous = session
        session = null
        previous?.job?.cancel()
        previous?.call?.cancel()
        runCatching { previous?.socket?.close() }
    }

    private fun stateLocked(): Map<String, Any?> {
        if (session?.let { SystemClock.elapsedRealtime() >= it.deadline } == true) stopLocked()
        val current = session
        return mapOf(
            "active" to (current != null),
            "receiverHost" to current?.endpoint?.host,
            "remainingSeconds" to current?.let { ((it.deadline - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0) },
            "lastSentAt" to current?.lastSentAt,
            "sentCount" to (current?.sentCount ?: 0),
            "error" to current?.error,
        )
    }

    /** Only fixed checkpoint names, method names and elapsed time; never arguments. */
    fun checkpoint(owner: String, method: String, stage: String, elapsedMs: Long = 0) {
        synchronized(lock) { if (session == null) return }
        val safeMethod = method.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,79}")) } ?: "unknown"
        scope.launch {
            RuntimeLogStore.append(RuntimeLogEntry(
                level = "INFO", tag = owner,
                message = "$safeMethod $stage elapsedMs=$elapsedMs",
            ))
        }
    }

    private fun deviceInfo(): Map<String, Any> = mapOf(
        "appVersion" to BuildConfig.VERSION_NAME,
        "appVersionCode" to BuildConfig.VERSION_CODE,
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "androidVersion" to Build.VERSION.RELEASE,
        "sdk" to Build.VERSION.SDK_INT,
        "abis" to Build.SUPPORTED_ABIS.toList(),
        "pageSize" to android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE),
        "uptimeMs" to SystemClock.elapsedRealtime(),
    )
}
