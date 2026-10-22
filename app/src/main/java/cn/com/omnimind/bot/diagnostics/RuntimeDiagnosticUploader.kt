package cn.com.omnimind.bot.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import cn.com.omnimind.baselib.util.AppSecretStore
import com.google.gson.Gson
import java.io.File
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

/** A paired receiver is restored on app startup. Uploads only app-UID logs. */
internal object RuntimeDiagnosticUploader {
    private const val CONNECTION_KEY = "runtime_diagnostics.connection.v2"
    private val gson = Gson()
    private data class Connection(val endpoint: String, val code: String, val streamId: String, val enabled: Boolean)
    private var directory: File? = null
    private var outbox: RuntimeDiagnosticOutbox? = null
    private var collector: Job? = null
    private var logcat: java.lang.Process? = null
    private var captureError: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val lock = Any()
    private class Session(val endpoint: RuntimeDiagnosticTarget, val code: String, val id: String) {
        var job: Job? = null
        var call: Call? = null
        var socket: Socket? = null
        var lastSentAt: Long? = null
        var sentCount = 0
        var error: String? = null
    }
    private var session: Session? = null

    internal fun validateEndpoint(value: String): RuntimeDiagnosticTarget = RuntimeDiagnosticTarget.parse(value)

    fun initialize(context: Context) {
        synchronized(lock) {
            if (directory != null) return
            directory = File(context.noBackupFilesDir, "runtime-diagnostic-outbox")
        }
        RuntimeLogStore.observe("runtime-diagnostics") { entry ->
            scope.launch {
                synchronized(lock) {
                    if (session != null) runCatching { outbox?.append(entry) }
                        .onFailure { captureError = it.javaClass.simpleName }
                }
            }
        }
        scope.launch {
            synchronized(lock) {
                val saved = savedConnection()
                if (session == null && saved?.enabled == true) {
                    runCatching { beginLocked(saved) }.onFailure { captureError = it.javaClass.simpleName }
                }
            }
        }
    }

    fun importLink(value: String) {
        scope.launch {
            runCatching { start(value, "") }
                .onFailure { synchronized(lock) { captureError = it.javaClass.simpleName } }
        }
    }

    private fun savedConnection(): Connection? = runCatching {
        AppSecretStore.read(CONNECTION_KEY)?.let { gson.fromJson(it, Connection::class.java) }
    }.getOrNull()

    fun start(endpoint: String, code: String): Map<String, Any?> {
        if (endpoint.isBlank() && code.isBlank()) synchronized(lock) {
            val saved = savedConnection()?.copy(enabled = true)
            if (saved != null) {
                check(AppSecretStore.write(CONNECTION_KEY, gson.toJson(saved))) { "无法保存诊断连接" }
                beginLocked(saved)
                return stateLocked()
            }
        }
        val link = if (endpoint.trim().startsWith("omnibotdiagnostics:")) {
            requireNotNull(RuntimeDiagnosticLink.parse(endpoint.trim())) { "诊断链接无效，请重新复制完整链接" }
        } else null
        val endpointValue = link?.get("endpoint") ?: endpoint
        validateEndpoint(endpointValue)
        val accessCode = link?.get("code") ?: code
        require(accessCode.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "诊断码须为 32–128 位字母、数字、下划线或连字符" }
        synchronized(lock) {
            check(directory != null) { "诊断存储尚未初始化" }
            val saved = Connection(endpointValue, accessCode, savedConnection()?.streamId ?: UUID.randomUUID().toString(), true)
            check(AppSecretStore.write(CONNECTION_KEY, gson.toJson(saved))) { "无法加密保存诊断连接" }
            beginLocked(saved)
            return stateLocked()
        }
    }

    private fun beginLocked(saved: Connection) {
        val target = validateEndpoint(saved.endpoint)
        require(saved.code.matches(Regex("[A-Za-z0-9_-]{32,128}")))
        stopLocked()
        val queue = outbox ?: RuntimeDiagnosticOutbox(checkNotNull(directory)).also { outbox = it }
        val current = Session(target, saved.code, saved.streamId)
        session = current
        captureError = null
        // Persisted error checkpoints include crashes from the previous process.
        RuntimeLogStore.listRecent(200).asReversed().forEach(queue::append)
        collector = scope.launch {
            while (isActive) {
                var process: java.lang.Process? = null
                try {
                    val started = ProcessBuilder("logcat", "-b", "main", "-b", "crash",
                        "--uid=${Process.myUid()}", "-v", "threadtime,epoch,usec", "*:V")
                        .redirectErrorStream(true).start()
                    process = started
                    synchronized(lock) {
                        if (session !== current) return@launch
                        logcat = started
                        captureError = null
                    }
                    started.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (!isActive) return@forEach
                            RuntimeDiagnosticLogcat.parse(line)?.let(queue::append)
                        }
                    }
                    if (isActive) synchronized(lock) { captureError = "logcat exited (${started.waitFor()})" }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    synchronized(lock) { captureError = error.javaClass.simpleName }
                } finally {
                    process?.destroy()
                }
                delay(5_000)
            }
        }
        current.job = scope.launch {
            while (isActive) {
                try {
                    val batch = queue.pending()
                    val report = RuntimeDiagnosticReport.batch(current.id, deviceInfo(), batch, queue.dropped)
                    val https = current.endpoint.https
                    val error = if (https != null) {
                        val call = createUploadCall(https, current.code, report.json)
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
                            RuntimeDiagnosticTcp.send(socket, current.endpoint, current.code, report.json)
                        }
                        null
                    }
                    synchronized(lock) {
                        if (session !== current) return@launch
                        if (error == null) {
                            queue.acknowledge(report.entries)
                            current.lastSentAt = System.currentTimeMillis()
                            current.sentCount++
                        }
                        current.error = error
                    }
                    if (error == null && queue.size() > 0) continue
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    synchronized(lock) { current.error = error.javaClass.simpleName }
                } finally {
                    synchronized(lock) { current.call = null; current.socket = null }
                }
                delay(10_000)
            }
        }
    }

    fun state(): Map<String, Any?> = synchronized(lock) { stateLocked() }

    internal fun createUploadCall(endpoint: HttpUrl, code: String, body: String): Call =
        client.newCall(Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $code")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build())

    fun stop(): Map<String, Any?> = synchronized(lock) {
        savedConnection()?.let {
            check(AppSecretStore.write(CONNECTION_KEY, gson.toJson(it.copy(enabled = false)))) { "无法保存停止状态" }
        }
        stopLocked()
        stateLocked()
    }

    private fun stopLocked() {
        val previous = session
        session = null
        collector?.cancel()
        collector = null
        logcat?.destroy()
        logcat = null
        previous?.job?.cancel()
        previous?.call?.cancel()
        runCatching { previous?.socket?.close() }
    }

    private fun stateLocked(): Map<String, Any?> {
        val current = session
        return mapOf(
            "active" to (current != null),
            "receiverHost" to current?.endpoint?.host,
            "automatic" to (current != null),
            "pendingLogs" to (outbox?.size() ?: 0),
            "droppedLogs" to (outbox?.dropped ?: 0),
            "captureError" to captureError,
            "lastSentAt" to current?.lastSentAt,
            "sentCount" to (current?.sentCount ?: 0),
            "error" to (current?.error ?: captureError),
        )
    }

    /** Only fixed checkpoint names, method names and elapsed time; never arguments. */
    fun checkpoint(owner: String, method: String, stage: String, elapsedMs: Long = 0) {
        synchronized(lock) { if (session == null) return }
        val safeMethod = RuntimeDiagnosticFailure.method(method)
        scope.launch {
            RuntimeLogStore.append(RuntimeLogEntry(
                level = "INFO", tag = owner,
                message = "$safeMethod $stage elapsedMs=$elapsedMs",
            ))
        }
    }

    fun failure(owner: String, method: String, error: Throwable, context: String = "") {
        if (error is CancellationException) return
        val entry = RuntimeDiagnosticFailure.entry(owner, method, error, context)
        scope.launch { RuntimeLogStore.append(entry) }
    }

    fun adapterError(profileId: String, line: String) {
        synchronized(lock) { if (session == null) return }
        // The compatibility hook emits error details only, before the SDK
        // replaces an untyped error with "Internal error". No stdout traffic.
        if (!line.startsWith("[OMNIBOT_ACP_ERROR]")) return
        val detail = RuntimeDiagnosticReport.redact(line).take(8_192)
        scope.launch {
            RuntimeLogStore.append(RuntimeLogEntry(
                level = "ERROR", tag = "AcpAdapter",
                message = "adapter request failed profile=${RuntimeDiagnosticFailure.method(profileId)}",
                stackTrace = detail,
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
