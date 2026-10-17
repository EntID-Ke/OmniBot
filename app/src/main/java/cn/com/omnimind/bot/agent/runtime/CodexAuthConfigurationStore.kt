package cn.com.omnimind.bot.agent.runtime

import android.content.Context

/** Stores only mode and a concurrency revision; never stores account tokens. */
internal class CodexAuthConfigurationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "codex_auth_configuration", Context.MODE_PRIVATE,
    )

    fun read(): CodexAuthMode = CodexAuthMode.parse(preferences.getString("mode", null))
    fun revision(): Long = preferences.getLong("revision", 0L)
    fun model(): String? = preferences.getString("model", null)?.takeIf(String::isNotBlank)
    fun effort(): String? = preferences.getString("effort", null)?.takeIf(String::isNotBlank)
    fun diagnosticsEnabled(): Boolean = preferences.getBoolean("diagnostics", false)

    @Synchronized
    fun write(mode: CodexAuthMode, expectedRevision: Long?, model: String? = null, effort: String? = null, diagnostics: Boolean? = null) {
        val currentRevision = revision()
        require(expectedRevision == null || expectedRevision == currentRevision) {
            "Codex authentication configuration changed; reopen settings."
        }
        if (read() == mode && model == null && effort == null && diagnostics == null) return
        check(preferences.edit().putString("mode", mode.value)
            .putString("model", model ?: this.model())
            .putString("effort", effort ?: this.effort())
            .putBoolean("diagnostics", diagnostics ?: diagnosticsEnabled())
            .putLong("revision", currentRevision + 1).commit()) {
            "Failed to save Codex authentication mode."
        }
    }
}
