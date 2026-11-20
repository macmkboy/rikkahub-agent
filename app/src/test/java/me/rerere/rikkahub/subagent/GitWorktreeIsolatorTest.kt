package me.rerere.rikkahub.subagent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitWorktreeIsolatorTest {

    private class FakeWorktreeShellExecutor : WorktreeShellExecutor {
        val workspaceCalls = mutableListOf<Triple<String, String, String>>()
        val termuxCalls = mutableListOf<Pair<String, String>>()
        var workspaceHandler: (workspaceId: String, cwd: String, command: String) -> WorktreeShellOutput? = { _, _, _ -> null }
        var termuxHandler: (cwd: String, command: String) -> WorktreeShellOutput? = { _, _ -> null }

        override suspend fun runInWorkspace(
            workspaceId: String,
            cwd: String,
            command: String,
            timeoutMs: Long,
        ): WorktreeShellOutput? {
            workspaceCalls += Triple(workspaceId, cwd, command)
            return workspaceHandler(workspaceId, cwd, command)
        }

        override suspend fun runInTermux(
            cwd: String,
            command: String,
            timeoutMs: Long,
        ): WorktreeShellOutput? {
            termuxCalls += cwd to command
            return termuxHandler(cwd, command)
        }
    }

    @Test
    fun `prefers workspace rootfs shell when workspaceId is present and succeeds`() = runBlocking {
        val executor = FakeWorktreeShellExecutor().apply {
            workspaceHandler = { _, _, _ -> WorktreeShellOutput(exitCode = 0, stdout = "") }
            termuxHandler = { _, _ -> WorktreeShellOutput(exitCode = 0, stdout = "") }
        }
        val isolator = GitWorktreeIsolator(executor)
        val policy = SubAgentToolPolicy(allowedNames = emptySet())

        val worktree = isolator.prepareIfEligible(
            workspaceId = "ws-1",
            baseCwd = "/workspace/repo",
            runId = "run-123",
            policy = policy,
        )

        assertNotNull(worktree)
        assertEquals(WorktreeBackend.WORKSPACE_ROOTFS, worktree!!.backend)
        assertEquals("/workspace/repo/.rikkahub-worktrees/run-123", worktree.worktreePath)
        assertEquals(1, executor.workspaceCalls.size)
        assertTrue(executor.termuxCalls.isEmpty())
    }

    @Test
    fun `falls back to Termux shell when workspace rootfs fails or is unavailable`() = runBlocking {
        val executor = FakeWorktreeShellExecutor().apply {
            workspaceHandler = { _, _, _ -> WorktreeShellOutput(exitCode = 128, stdout = "", stderr = "not a git repo") }
            termuxHandler = { _, _ -> WorktreeShellOutput(exitCode = 0, stdout = "") }
        }
        val isolator = GitWorktreeIsolator(executor)
        val policy = SubAgentToolPolicy(allowedNames = setOf("termux_run_command"))

        val worktree = isolator.prepareIfEligible(
            workspaceId = "ws-1",
            baseCwd = "/data/data/com.termux/files/home/proj",
            runId = "run-456",
            policy = policy,
        )

        assertNotNull(worktree)
        assertEquals(WorktreeBackend.TERMUX, worktree!!.backend)
        assertEquals("/data/data/com.termux/files/home/proj/.rikkahub-worktrees/run-456", worktree.worktreePath)
        assertEquals(1, executor.workspaceCalls.size)
        assertEquals(1, executor.termuxCalls.size)
    }

    @Test
    fun `skips isolation for read-only non-workspace sub-agents`() = runBlocking {
        val executor = FakeWorktreeShellExecutor()
        val isolator = GitWorktreeIsolator(executor)
        val policy = SubAgentToolPolicy(allowedNames = setOf("web_search"))

        val worktree = isolator.prepareIfEligible(
            workspaceId = null,
            baseCwd = "/data/data/com.termux/files/home/proj",
            runId = "run-789",
            policy = policy,
        )

        assertNull(worktree)
        assertTrue(executor.workspaceCalls.isEmpty())
        assertTrue(executor.termuxCalls.isEmpty())
    }

    @Test
    fun `routes cleanup to the backend that created the worktree`() = runBlocking {
        val executor = FakeWorktreeShellExecutor().apply {
            workspaceHandler = { _, _, _ -> WorktreeShellOutput(exitCode = 0, stdout = "") }
            termuxHandler = { _, _ -> WorktreeShellOutput(exitCode = 1, stdout = "dirty") }
        }
        val isolator = GitWorktreeIsolator(executor)

        val wsCleaned = isolator.cleanupIfClean(
            IsolatedWorktree(
                baseCwd = "/workspace/repo",
                worktreePath = "/workspace/repo/.rikkahub-worktrees/run-1",
                backend = WorktreeBackend.WORKSPACE_ROOTFS,
                workspaceId = "ws-1",
            )
        )
        assertTrue(wsCleaned)
        assertEquals(1, executor.workspaceCalls.size)
        assertTrue(executor.termuxCalls.isEmpty())

        val termuxCleaned = isolator.cleanupIfClean(
            IsolatedWorktree(
                baseCwd = "/data/data/com.termux/files/home/repo",
                worktreePath = "/data/data/com.termux/files/home/repo/.rikkahub-worktrees/run-2",
                backend = WorktreeBackend.TERMUX,
            )
        )
        assertFalse(termuxCleaned)
        assertEquals(1, executor.termuxCalls.size)
    }

    @Test
    fun `relative baseCwd uses relative worktree path without redundant cd`() = runBlocking {
        val executor = FakeWorktreeShellExecutor().apply {
            workspaceHandler = { _, _, _ -> WorktreeShellOutput(exitCode = 0, stdout = "") }
        }
        val isolator = GitWorktreeIsolator(executor)
        val policy = SubAgentToolPolicy(allowedNames = emptySet())

        val worktree = isolator.prepareIfEligible(
            workspaceId = "ws-1",
            baseCwd = "myrepo",
            runId = "run-rel",
            policy = policy,
        )

        assertNotNull(worktree)
        assertEquals("myrepo/.rikkahub-worktrees/run-rel", worktree!!.worktreePath)
        val cmd = executor.workspaceCalls.single().third
        assertFalse("Should not redundantly cd into relative baseCwd", cmd.contains("cd 'myrepo'"))
        assertTrue(cmd.contains("'.rikkahub-worktrees/run-rel'"))
    }

    @Test
    fun `does not fall back to Termux for virtual workspace paths`() = runBlocking {
        val executor = FakeWorktreeShellExecutor().apply {
            workspaceHandler = { _, _, _ -> WorktreeShellOutput(exitCode = 128, stdout = "", stderr = "not a git repo") }
            termuxHandler = { _, _ -> WorktreeShellOutput(exitCode = 0, stdout = "") }
        }
        val isolator = GitWorktreeIsolator(executor)
        val policy = SubAgentToolPolicy(allowedNames = emptySet())

        val worktree = isolator.prepareIfEligible(
            workspaceId = "ws-1",
            baseCwd = "/workspace/repo",
            runId = "run-virtual",
            policy = policy,
        )

        assertNull(worktree)
        assertEquals(1, executor.workspaceCalls.size)
        assertTrue(executor.termuxCalls.isEmpty())
    }

    @Test
    fun `shellQuote escapes single quotes safely`() {
        assertEquals("'/workspace/my project/it'\\''s a repo'", GitWorktreeIsolator.shellQuote("/workspace/my project/it's a repo"))
    }
}
