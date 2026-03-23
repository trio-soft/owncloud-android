package com.owncloud.android.data.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.owncloud.android.data.ProviderMeta

val MIGRATION_49_50 = object : Migration(49, 50) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE ${ProviderMeta.ProviderTableMeta.FOLDER_BACKUP_TABLE_NAME} ADD COLUMN `fileExistsPolicy` TEXT NOT NULL DEFAULT 'SKIP'")
    }
}
