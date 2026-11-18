package me.rerere.rikkahub.data.ai.broker

/**
 * Context source identifiers.
 * One declaration of every context source that can be gathered into the prompt.
 *
 * Distinguishes [renderOrder] (where a block prints in the assembled prompt)
 * from [defaultPriority] (which blocks survive token trimming when the context ceiling is tight).
 */
enum class ContextSource(
    val identifier: String,
    val defaultPriority: Int,
    val renderOrder: Int,
    val isPinned: Boolean = false,
) {
    // Pinned sources: highest priorities (90-100), never dropped during token budget trimming.
    // Static standing rules render in the stable prefix so prompt caching can be reused.
    SYSTEM_INSTRUCTION("system_instruction", defaultPriority = 80, renderOrder = 0, isPinned = true),
    SUBAGENT_PROFILE("subagent_profile", defaultPriority = 70, renderOrder = 1),
    RULES("rules", defaultPriority = 100, renderOrder = 2, isPinned = true),
    PROJECT_DOC("project_doc", defaultPriority = 50, renderOrder = 3),
    TOOL_GUIDANCE("tool_guidance", defaultPriority = 60, renderOrder = 4),

    // Situational volatile sources: subject to token budgeting and dynamic per-turn change.
    SKILLS("skills", defaultPriority = 40, renderOrder = 5),
    WORKSPACE_STATE("workspace_state", defaultPriority = 30, renderOrder = 6),
    MEMORY("memory", defaultPriority = 90, renderOrder = 7, isPinned = true),
    RECENT_CHATS("recent_chats", defaultPriority = 20, renderOrder = 8);

    companion object {
        val ORDER: List<ContextSource> = entries.sortedBy { it.renderOrder }
        val PINNED: Set<ContextSource> = entries.filter { it.isPinned }.toSet()

        fun fromIdentifier(id: String): ContextSource? =
            entries.firstOrNull { it.identifier.equals(id, ignoreCase = true) }
    }
}
