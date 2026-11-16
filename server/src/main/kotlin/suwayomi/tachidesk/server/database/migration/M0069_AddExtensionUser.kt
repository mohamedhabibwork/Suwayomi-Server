package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.Migration
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import suwayomi.tachidesk.server.database.migration.helpers.toSqlName

@Suppress("ClassName", "unused")
class M0069_AddExtensionUser : Migration() {
    private object UserAccountTable : IntIdTable()

    private object ExtensionUserTable : IntIdTable() {
        val pkgName = varchar("pkg_name", 128)
        val user = reference("user_id", UserAccountTable, ReferenceOption.CASCADE)
        val isInstalled = bool("is_installed").default(false)

        init {
            uniqueIndex(user, pkgName)
        }
    }

    override fun run() {
        with(TransactionManager.current()) {
            SchemaUtils
                .createStatements(
                    ExtensionUserTable,
                ).forEach {
                    exec(it)
                }

            val extUserTable = "EXTENSIONUSER".toSqlName()
            val extTable = "EXTENSION".toSqlName()
            val userAccountTable = "USERACCOUNT".toSqlName()
            exec(
                """
                INSERT INTO $extUserTable (PKG_NAME, USER_ID, IS_INSTALLED)
                SELECT PKG_NAME, 1, TRUE
                FROM $extTable
                WHERE IS_INSTALLED = TRUE
                  AND EXISTS (SELECT 1 FROM $userAccountTable WHERE ID = 1)
                  AND NOT EXISTS (
                      SELECT 1 FROM $extUserTable eu
                      WHERE eu.PKG_NAME = $extTable.PKG_NAME AND eu.USER_ID = 1
                  );
                """.trimIndent(),
            )
            currentDialectMetadata.resetCaches()
        }
    }
}
