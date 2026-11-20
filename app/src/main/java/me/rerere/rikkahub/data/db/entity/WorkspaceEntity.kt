package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.model.WorkspaceStorageMount
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    @ColumnInfo("shell_status")
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // 工具审批的用户覆盖项 (toolName -> needsApproval)，未覆盖的工具沿用默认值
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    @ColumnInfo("shell_compatibility_mode", defaultValue = "0")
    val shellCompatibilityMode: Boolean = false,
    // 挂载进 Rootfs 的共享存储目录 (WorkspaceStorageMount 列表的 JSON), 只对本地工作区生效
    @ColumnInfo("storage_mounts", defaultValue = "[]")
    val storageMounts: String = "[]",
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    fun storageMountList(): List<WorkspaceStorageMount> = runCatching {
        JsonInstant.decodeFromString<List<WorkspaceStorageMount>>(storageMounts)
    }.getOrDefault(emptyList())

    /** 叠加在全局挂载表之后的工作区自有挂载; 共享存储目录在 Rootfs 内挂到同一路径 */
    fun extraBindMounts(): List<WorkspaceBindMount> =
        storageMountList().map { mount ->
            WorkspaceBindMount(source = File(mount.path), target = mount.path, writable = mount.writable)
        }

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        shellStatus = runCatching { WorkspaceShellStatus.valueOf(shellStatus) }
            .getOrDefault(WorkspaceShellStatus.DISABLED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
    )
}
