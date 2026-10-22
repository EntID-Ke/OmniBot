package cn.com.omnimind.bot.agent.runtime

/** Non-secret authentication policy. OAuth credentials belong only to Codex. */
internal enum class CodexAuthMode(val value: String) {
    API("api"), CHATGPT("chatgpt");

    companion object {
        fun parse(value: String?): CodexAuthMode = when (value) {
            null, "api" -> API
            "chatgpt" -> CHATGPT
            else -> throw IllegalArgumentException("Unsupported Codex authentication mode.")
        }
    }
}

internal const val CODEX_SUBSCRIPTION_HOME = "/root/.codex-chatgpt"
internal const val CODEX_SUBSCRIPTION_CONFIG = "$CODEX_SUBSCRIPTION_HOME/config.toml"
internal const val CODEX_SUBSCRIPTION_AUTH = "$CODEX_SUBSCRIPTION_HOME/auth.json"
// codex-acp also installs an older transitive CLI. Both authentication and ACP
// must use the same managed executable as the terminal's `codex /model`.
internal const val CODEX_MANAGED_COMMAND = "/root/.npm-global/bin/codex"

// Clear these after the login shell has loaded its profile, immediately before
// exec. Empty environment values alone do not remove inherited credentials.
internal val CODEX_SUBSCRIPTION_UNSET_KEYS = setOf(
    "OPENAI_API_KEY", "CODEX_API_KEY", "CODEX_ACCESS_TOKEN", "OPENAI_BASE_URL",
    "CODEX_CONFIG", "CODEX_REFRESH_TOKEN_URL_OVERRIDE", "CODEX_REVOKE_TOKEN_URL_OVERRIDE",
    "CODEX_APP_SERVER_LOGIN_CLIENT_ID", "OPENAI_FEDERATION_RULE_ID",
    "OPENAI_IDENTITY_TOKEN_FILE", "OPENAI_WORKLOAD_IDENTITY_CONTEXT",
)

internal fun codexSubscriptionConfig(model: String? = null, effort: String? = null): String = buildString {
    append("forced_login_method = \"chatgpt\"\ncli_auth_credentials_store = \"file\"\n")
    model?.takeIf(String::isNotBlank)?.let { append("model = ").append(tomlString(it)).append('\n') }
    effort?.takeIf(String::isNotBlank)?.let { append("model_reasoning_effort = ").append(tomlString(it)).append('\n') }
}
