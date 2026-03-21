/**
 * ownCloud Android client application
 *
 * @author Bruce Tsai
 *
 * Copyright (C) 2026 ownCloud GmbH.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.data.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.owncloud.android.data.ProviderMeta

val MIGRATION_48_49 = object : Migration(48, 49) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE ${ProviderMeta.ProviderTableMeta.FOLDER_BACKUP_TABLE_NAME} ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1")
        database.execSQL("ALTER TABLE ${ProviderMeta.ProviderTableMeta.FOLDER_BACKUP_TABLE_NAME} ADD COLUMN `useSubfolders` INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE ${ProviderMeta.ProviderTableMeta.FOLDER_BACKUP_TABLE_NAME} ADD COLUMN `excludeHidden` INTEGER NOT NULL DEFAULT 1")
    }
}
