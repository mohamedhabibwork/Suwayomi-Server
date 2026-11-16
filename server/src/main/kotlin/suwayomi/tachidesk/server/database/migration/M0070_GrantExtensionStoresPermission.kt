package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.Migration
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import suwayomi.tachidesk.server.database.migration.helpers.toSqlName

@Suppress("ClassName", "unused")
class M0070_GrantExtensionStoresPermission : Migration() {
    override fun run() {
        with(TransactionManager.current()) {
            val userPermissionsTable = "USERPERMISSIONS".toSqlName()
            val userAccountTable = "USERACCOUNT".toSqlName()
            exec(
                """
                INSERT INTO $userPermissionsTable (USER_ID, PERMISSION)
                SELECT ID, 'MANAGE_EXTENSION_STORES'
                FROM $userAccountTable
                WHERE ID <> 1
                  AND NOT EXISTS (
                      SELECT 1 FROM $userPermissionsTable up
                      WHERE up.USER_ID = $userAccountTable.ID
                        AND up.PERMISSION = 'MANAGE_EXTENSION_STORES'
                  );
                """.trimIndent(),
            )
            currentDialectMetadata.resetCaches()
        }
    }
}
