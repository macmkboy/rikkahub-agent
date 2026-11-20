package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_31_32"

internal const val MEMORY_FILE_DDL =
    "CREATE TABLE IF NOT EXISTS `memory_file` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, `content` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`assistant_id`, `path`))"

internal const val MEMORY_CONSOLIDATION_DDL =
    "CREATE TABLE IF NOT EXISTS `memory_consolidation` (`conversation_id` TEXT NOT NULL, `consolidated_at` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`), FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

/**
 * Migration from v31 to v32: adds memory_file, memory_consolidation, media_creation tables,
 * translation_history, and config column on ConversationEntity.
 *
 * Hand-written rather than an AutoMigration so Room does not require 32.json for subsequent
 * manual migrations, and to make table/column creation idempotent across backup imports.
 */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 31 to 32")
        db.beginTransaction()
        try {
            if (!hasColumn(db, "ConversationEntity", "config")) {
                db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `config` TEXT NOT NULL DEFAULT ''")
            }
            if (!hasColumn(db, "workspaces", "type")) {
                db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'LOCAL'")
            }
            if (!hasColumn(db, "workspaces", "ssh_config")) {
                db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `ssh_config` TEXT NOT NULL DEFAULT ''")
            }
            db.execSQL(MEMORY_FILE_DDL)
            db.execSQL(MEMORY_CONSOLIDATION_DDL)
            repairMemorySchemaIfNeeded(db)
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_creation_session` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_creation_node` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `prompt` TEXT NOT NULL, `model_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_creation_record` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `node_id` TEXT, `parent_id` TEXT, `provider_id` TEXT NOT NULL, `model_id` TEXT NOT NULL, `prompt` TEXT NOT NULL, `params` TEXT NOT NULL, `status` TEXT NOT NULL, `error_code` TEXT, `error_message` TEXT, `metadata` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `translation_history` (`id` TEXT NOT NULL, `source_text` TEXT NOT NULL, `target_text` TEXT NOT NULL, `source_lang` TEXT NOT NULL, `target_lang` TEXT NOT NULL, `provider` TEXT NOT NULL, `model` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")

            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 31 to 32 success")
        } finally {
            db.endTransaction()
        }
    }
}

/**
 * Idempotent repair for databases that previously ran a broken Migration_31_32 which created
 * `memory_file(name, content, update_at)` instead of `(assistant_id, path, content, updated_at)`
 * and omitted the CASCADE foreign key on `memory_consolidation`.
 */
internal fun repairMemorySchemaIfNeeded(db: SupportSQLiteDatabase) {
    val needsMemoryFileRepair =
        hasTable(db, "memory_file") && !hasColumn(db, "memory_file", "assistant_id")
    val needsConsolidationRepair =
        hasTable(db, "memory_consolidation") && !hasForeignKeyTo(db, "memory_consolidation", "ConversationEntity")
    if (!needsMemoryFileRepair && !needsConsolidationRepair) return

    db.beginTransaction()
    try {
        if (needsMemoryFileRepair) {
            Log.w(TAG, "repairMemorySchemaIfNeeded: rebuilding broken memory_file table")
            db.execSQL("DROP TABLE `memory_file`")
            db.execSQL(MEMORY_FILE_DDL)
        }

        if (needsConsolidationRepair) {
            Log.w(TAG, "repairMemorySchemaIfNeeded: rebuilding memory_consolidation with ConversationEntity FK")
            db.execSQL("DROP TABLE IF EXISTS `memory_consolidation_repair`")
            db.execSQL(
                "CREATE TABLE `memory_consolidation_repair` (`conversation_id` TEXT NOT NULL, `consolidated_at` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`), FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO `memory_consolidation_repair` (`conversation_id`, `consolidated_at`) " +
                    "SELECT mc.`conversation_id`, mc.`consolidated_at` FROM `memory_consolidation` mc " +
                    "INNER JOIN `ConversationEntity` c ON c.`id` = mc.`conversation_id`"
            )
            db.execSQL("DROP TABLE `memory_consolidation`")
            db.execSQL("ALTER TABLE `memory_consolidation_repair` RENAME TO `memory_consolidation`")
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private fun hasTable(db: SupportSQLiteDatabase, table: String): Boolean {
    db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { cursor ->
        return cursor.moveToFirst()
    }
}

private fun hasForeignKeyTo(db: SupportSQLiteDatabase, table: String, referencedTable: String): Boolean {
    db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
        val tableIdx = cursor.getColumnIndex("table")
        if (tableIdx < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(tableIdx) == referencedTable) return true
        }
    }
    return false
}

private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}
