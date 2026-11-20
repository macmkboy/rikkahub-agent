package me.rerere.rikkahub.subagent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.local.CaptureResult
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.ai.tools.local.runCommandCapture
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

private const val WORKTREE_TAG = "GitWorktreeIsolator"
private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
private const val WORKTREE_CREATE_TIMEOUT_MS = 10_000L
private const val WORKTREE_CLEANUP_TIMEOUT_MS = 8_000L

internal enum class WorktreeBackend {
    WORKSPACE_ROOTFS,
    TERMUX,
}

internal data class WorktreeShellOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
)

internal data class IsolatedWorktree(
    val baseCwd: String,
    val worktreePath: String,
    val backend: WorktreeBackend,
    val workspaceId: String? = null,
)

internal interface WorktreeShellExecutor {
    suspend fun runInWorkspace(
        workspaceId: String,
        cwd: String,
        command: String,
        timeoutMs: Long,
    ): WorktreeShellOutput?

    suspend fun runInTermux(
        cwd: String,
        command: String,
        timeoutMs: Long,
    ): WorktreeShellOutput?
}

internal class DefaultWorktreeShellExecutor(
    private val workspaceRepositoryProvider: () -> WorkspaceRepository?,
    private val contextProvider: () -> Context?,
) : WorktreeShellExecutor {

    override suspend fun runInWorkspace(
        workspaceId: String,
        cwd: String,
        command: String,
        timeoutMs: Long,
    ): WorktreeShellOutput? {
        val repo = workspaceRepositoryProvider() ?: return null
        val workspace = runCatching { repo.getById(workspaceId) }.getOrNull() ?: return null
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(WORKTREE_TAG, "Workspace $workspaceId shell not ready (${workspace.shellStatus}); skipping rootfs worktree")
            return null
        }
        val relativeCwd = cwd.removePrefix("/workspace/").removePrefix("/workspace")
        return runCatching {
            val result = repo.executeCommand(
                id = workspaceId,
                command = command,
                cwd = relativeCwd,
                timeoutMillis = timeoutMs,
            )
            if (result.timedOut) {
                Log.w(WORKTREE_TAG, "Workspace git worktree command timed out in $workspaceId")
                null
            } else {
                WorktreeShellOutput(result.exitCode, result.stdout, result.stderr)
            }
        }.onFailure { err ->
            Log.w(WORKTREE_TAG, "Workspace git worktree command failed in $workspaceId", err)
        }.getOrNull()
    }

    override suspend fun runInTermux(
        cwd: String,
        command: String,
        timeoutMs: Long,
    ): WorktreeShellOutput? {
        val ctx = contextProvider() ?: return null
        if (TermuxIntegration.state(ctx) != TermuxIntegration.State.READY) {
            return null
        }
        return when (
            val result = runCatching {
                runCommandCapture(
                    ctx = ctx,
                    executable = TERMUX_BASH,
                    arguments = arrayOf("-c", command),
                    workingDir = cwd,
                    timeoutMs = timeoutMs,
                )
            }.onFailure { err ->
                Log.w(WORKTREE_TAG, "Termux git worktree command threw", err)
            }.getOrNull()
        ) {
            is CaptureResult.Success -> WorktreeShellOutput(result.exitCode, result.stdout, result.stderr)
            is CaptureResult.Timeout -> {
                Log.w(WORKTREE_TAG, "Termux git worktree command timed out")
                null
            }
            is CaptureResult.Denied -> {
                Log.w(WORKTREE_TAG, "Termux git worktree command denied")
                null
            }
            is CaptureResult.OtherError -> {
                Log.w(WORKTREE_TAG, "Termux git worktree error: ${result.message}")
                null
            }
            null -> null
        }
    }
}

/**
 * Creates and cleans up isolated git worktrees for mutating sub-agent runs using the Android-safe
 * execution backends: Workspace Rootfs shell (preferred) with Termux shell as fallback.
 */
internal class GitWorktreeIsolator(
    private val executor: WorktreeShellExecutor,
) {
    private val mutatingTools = setOf(
        "workspace_write_file",
        "workspace_edit_file",
        "workspace_shell",
        "write_file",
        "edit_file",
        "write_text_file",
        "termux_run_command",
    )

    suspend fun prepareIfEligible(
        workspaceId: String?,
        baseCwd: String?,
        runId: String,
        policy: SubAgentToolPolicy,
    ): IsolatedWorktree? {
        val hasMutatingCapability = if (!workspaceId.isNullOrBlank()) {
            mutatingTools.any { policy.allows(it) }
        } else {
            policy.allowedNames.any { it in mutatingTools }
        }
        if (!hasMutatingCapability) return null
        val normalizedBase = baseCwd?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: if (!workspaceId.isNullOrBlank()) "/workspace" else return null

        val safeId = runId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32).ifEmpty { "run" }
        val relativeTarget = ".rikkahub-worktrees/$safeId"
        val targetPath = "$normalizedBase/$relativeTarget"
        val createCmd = buildCreateCommand(relativeTarget)

        // 1. Preferred: Workspace Rootfs shell
        if (!workspaceId.isNullOrBlank()) {
            val wsResult = executor.runInWorkspace(
                workspaceId = workspaceId,
                cwd = normalizedBase,
                command = createCmd,
                timeoutMs = WORKTREE_CREATE_TIMEOUT_MS,
            )
            if (wsResult != null && wsResult.exitCode == 0) {
                return IsolatedWorktree(
                    baseCwd = normalizedBase,
                    worktreePath = targetPath,
                    backend = WorktreeBackend.WORKSPACE_ROOTFS,
                    workspaceId = workspaceId,
                )
            }
            if (wsResult != null) {
                safeLogWarn(
                    "Workspace rootfs worktree setup exited ${wsResult.exitCode}: ${wsResult.stderr.ifBlank { wsResult.stdout }.take(200)}"
                )
            }
        }

        // 2. Fallback: Termux shell (only meaningful for non-virtual paths)
        val isVirtualWorkspacePath = normalizedBase == "/workspace" || normalizedBase.startsWith("/workspace/")
        if (!baseCwd.isNullOrBlank() && !( !workspaceId.isNullOrBlank() && isVirtualWorkspacePath )) {
            val termuxResult = executor.runInTermux(
                cwd = normalizedBase,
                command = createCmd,
                timeoutMs = WORKTREE_CREATE_TIMEOUT_MS,
            )
            if (termuxResult != null && termuxResult.exitCode == 0) {
                return IsolatedWorktree(
                    baseCwd = normalizedBase,
                    worktreePath = targetPath,
                    backend = WorktreeBackend.TERMUX,
                    workspaceId = null,
                )
            }
            if (termuxResult != null) {
                safeLogWarn(
                    "Termux worktree setup exited ${termuxResult.exitCode}: ${termuxResult.stderr.ifBlank { termuxResult.stdout }.take(200)}"
                )
            }
        }

        return null
    }

    suspend fun cleanupIfClean(worktree: IsolatedWorktree): Boolean = withContext(NonCancellable) {
        val relativeTarget = worktree.worktreePath
            .removePrefix(worktree.baseCwd.trimEnd('/') + "/")
            .ifBlank { worktree.worktreePath }
        val cleanupCmd = buildCleanupCommand(relativeTarget)
        val result = when (worktree.backend) {
            WorktreeBackend.WORKSPACE_ROOTFS -> {
                val wsId = worktree.workspaceId ?: return@withContext false
                executor.runInWorkspace(
                    workspaceId = wsId,
                    cwd = worktree.baseCwd,
                    command = cleanupCmd,
                    timeoutMs = WORKTREE_CLEANUP_TIMEOUT_MS,
                )
            }
            WorktreeBackend.TERMUX -> {
                executor.runInTermux(
                    cwd = worktree.baseCwd,
                    command = cleanupCmd,
                    timeoutMs = WORKTREE_CLEANUP_TIMEOUT_MS,
                )
            }
        }
        val cleaned = result != null && result.exitCode == 0
        if (!cleaned && result != null) {
            safeLogDebug(
                "Worktree ${worktree.worktreePath} retained or cleanup exited ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }.take(200)}"
            )
        }
        cleaned
    }

    companion object {
        private fun safeLogWarn(message: String) {
            runCatching { Log.w(WORKTREE_TAG, message) }
        }

        private fun safeLogDebug(message: String) {
            runCatching { Log.d(WORKTREE_TAG, message) }
        }

        internal fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"

        internal fun buildCreateCommand(relativeTarget: String): String {
            val qTarget = shellQuote(relativeTarget)
            return "git rev-parse --is-inside-work-tree >/dev/null 2>&1 && " +
                "mkdir -p .rikkahub-worktrees && " +
                "(test -d $qTarget || git worktree add --detach $qTarget HEAD)"
        }

        internal fun buildCleanupCommand(relativeTarget: String): String {
            val qTarget = shellQuote(relativeTarget)
            return "test -d $qTarget && " +
                "(cd $qTarget && test -z \"\$(git status --porcelain)\") && " +
                "git worktree remove --force $qTarget"
        }
    }
}
