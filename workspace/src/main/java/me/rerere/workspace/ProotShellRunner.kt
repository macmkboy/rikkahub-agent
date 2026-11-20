package me.rerere.workspace

import java.io.File
import java.util.UUID

data class WorkspaceBindMount(
    val source: File,
    val target: String,
    /** AI 写入该挂载点时是否免强制审批 */
    val writable: Boolean = false,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val pidFile = File(context.linuxDir, "${EXEC_DIR.trimStart('/')}/${UUID.randomUUID()}.pid")
        pidFile.parentFile?.mkdirs()
        val process = newProcessBuilder(context, proot, loader, "$EXEC_DIR/${pidFile.name}").start()

        return try {
            // 超时或取消时直接结束, 不留宽限期: 调用方已经不再等这条命令
            process.readResult(context.timeoutMillis, context.stdin) { terminateProot(this, pidFile) }
        } finally {
            pidFile.delete()
        }
    }

    override fun start(context: WorkspaceShellContext): Process {
        if (!context.linuxDir.hasUsableRootfs()) {
            throw IllegalStateException("Rootfs is not installed")
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            throw IllegalStateException("proot executable not found: ${proot.absolutePath}")
        }
        if (!loader.isFile) {
            throw IllegalStateException("proot loader not found: ${loader.absolutePath}")
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        return newProcessBuilder(context, proot, loader).start()
    }

    private fun newProcessBuilder(
        context: WorkspaceShellContext,
        proot: File,
        loader: File,
        pidFilePath: String? = null,
    ): ProcessBuilder =
        ProcessBuilder(buildCommand(context, proot, pidFilePath))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                if (context.shellCompatibilityMode) {
                    environment()["PROOT_NO_SECCOMP"] = "1"
                } else {
                    environment().remove("PROOT_NO_SECCOMP")
                }
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
        pidFilePath: String? = null,
    ): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            context.prootCwd(),
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
            "CI=true",
            "NO_COLOR=1",
            "PAGER=cat",
            "/bin/bash",
            "-l",
            "-c",
            if (pidFilePath != null) TRACKED_SCRIPT else EXEC_SCRIPT,
            "rikkahub",
            context.prootCwd(),
            context.command,
        )
        if (pidFilePath != null) {
            command += pidFilePath
        }
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR

        // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
        private const val EXEC_SCRIPT = "cd -- \"\$1\" && eval \"\$2\""

        // 先把 shell 与 PRoot 的进程号写入 $3, 终止时据此给整组进程发信号, 见 terminateProot
        private const val TRACKED_SCRIPT = "echo \"\$\$ \$PPID\" > \"\$3\"; $EXEC_SCRIPT"

        /** Rootfs 内存放前台命令进程号的目录 */
        private const val EXEC_DIR = "/tmp/rikkahub/exec"
    }
}
