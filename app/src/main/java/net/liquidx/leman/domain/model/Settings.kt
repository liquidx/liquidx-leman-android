package net.liquidx.leman.domain.model

/** User settings (spec 03 table). Defaults match the DataStore keys' defaults. */
data class Settings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val agentName: String = DEFAULT_AGENT_NAME,
    val agentGlyph: String = DEFAULT_AGENT_GLYPH,
    val biometricUnlock: Boolean = false,
    val expandTracesByDefault: Boolean = false,
    val showToolArgs: Boolean = true,
) {
    val agentProfile: AgentProfile get() = AgentProfile(agentName, agentGlyph)

    companion object {
        const val DEFAULT_SERVER_URL = "https://api.gent.ino.ink"
        const val DEFAULT_AGENT_NAME = "juno"
        const val DEFAULT_AGENT_GLYPH = "✳"
        val GLYPH_CHOICES = listOf("✳", "◆", "▲", "●", "⌬")

        /** The model id sent to the gateway is always this (spec 02/03). */
        const val MODEL_ID = "hermes-agent"
    }
}
