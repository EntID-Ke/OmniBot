package cn.com.omnimind.bot.diagnostics

import cn.com.omnimind.baselib.util.RuntimeLogEntry
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Private, redacted, at-least-once queue. Only acknowledged records are removed. */
internal class RuntimeDiagnosticOutbox(
    private val directory: File,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val maxEntries: Int = 16_384,
) {
    private val gson = Gson()
    private val files = linkedMapOf<String, File>()
    private var bytes = 0L
    private val droppedFile = File(directory, "dropped")
    @Volatile var dropped: Long = 0; private set

    init {
        check(directory.isDirectory || directory.mkdirs())
        directory.setReadable(false, false); directory.setReadable(true, true)
        directory.setExecutable(false, false); directory.setExecutable(true, true)
        directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .forEach { files[it.name] = it; bytes += it.length() }
        directory.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        dropped = droppedFile.takeIf { it.isFile }?.readText()?.toLongOrNull() ?: 0
        enforceLimit()
    }

    @Synchronized fun append(entry: RuntimeLogEntry) {
        val safe = RuntimeDiagnosticReport.sanitize(entry)
        val name = filename(safe.id)
        if (files.containsKey(name)) return
        val file = File(directory, name)
        atomicWrite(file, gson.toJson(safe))
        files[name] = file
        bytes += file.length()
        enforceLimit()
    }

    @Synchronized fun pending(limit: Int = 100): List<RuntimeLogEntry> =
        files.values.take(limit).map { gson.fromJson(it.readText(), RuntimeLogEntry::class.java) }

    @Synchronized fun acknowledge(entries: List<RuntimeLogEntry>) {
        for (entry in entries) {
            val name = filename(entry.id)
            val file = files[name] ?: continue
            val size = file.length()
            check(file.delete()) { "Cannot acknowledge diagnostic record" }
            bytes -= size
            files.remove(name)
        }
    }

    @Synchronized fun size(): Int = files.size

    private fun filename(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) } + ".json"

    private fun enforceLimit() {
        var removed = 0
        while (files.isNotEmpty() && (bytes > maxBytes || files.size > maxEntries)) {
            val first = files.entries.first()
            val size = first.value.length()
            check(first.value.delete())
            files.remove(first.key)
            bytes -= size; removed++
        }
        if (removed > 0) {
            dropped += removed
            atomicWrite(droppedFile, dropped.toString())
        }
    }

    private fun atomicWrite(file: File, text: String) {
        val temp = File(directory, file.name + ".tmp")
        FileOutputStream(temp).use { stream ->
            temp.setReadable(false, false); temp.setReadable(true, true)
            temp.setWritable(false, false); temp.setWritable(true, true)
            stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
        check(temp.renameTo(file)) { "Cannot persist diagnostic record" }
    }
}
