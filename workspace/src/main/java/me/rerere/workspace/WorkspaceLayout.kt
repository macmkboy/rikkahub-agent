package me.rerere.workspace

/**
 * 工作区的目录约定，用于判断 AI 写入哪些路径免强制审批。
 */
data class WorkspaceLayout(
    val workspaceDir: String,
    /** AI 写入时免强制审批的目录 */
    val writableRoots: List<String>,
    /** AI 写入时需要审批的挂载目录; 嵌套在 [writableRoots] 里时以更深的那一层为准 */
    val approvalRoots: List<String> = emptyList(),
) {
    /** 按最长匹配的目录判断: 例如 /a 免审批、/a/b 需审批时, /a/b 下的路径需要审批 */
    fun isWritable(path: String): Boolean {
        val normalized = normalizeAbsolutePath(path) ?: return false
        fun List<String>.deepestMatch(): Int? = filter { root ->
            normalized == root || normalized.startsWith("$root/")
        }.maxOfOrNull { it.length }
        val writable = writableRoots.deepestMatch() ?: return false
        val approval = approvalRoots.deepestMatch() ?: return true
        return writable > approval
    }

    private companion object {
        /** 消掉 . 和 .., 避免 /workspace/../x 这类路径绕过审批; 越过根目录时返回 null */
        fun normalizeAbsolutePath(path: String): String? {
            if (!path.startsWith("/")) return null
            val segments = ArrayDeque<String>()
            path.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> segments.removeLastOrNull() ?: return null
                    else -> segments.addLast(segment)
                }
            }
            return segments.joinToString(separator = "/", prefix = "/")
        }
    }
}
