package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.leftJoin
import suwayomi.tachidesk.global.model.table.UserAccountTable

object ExtensionUserTable : IntIdTable() {
    val pkgName = varchar("pkg_name", 128)
    val user = reference("user_id", UserAccountTable, ReferenceOption.CASCADE)
    val isInstalled = bool("is_installed").default(false)

    init {
        uniqueIndex(user, pkgName)
    }
}

fun ExtensionTable.getWithUserData(userId: Int) =
    leftJoin(
        ExtensionUserTable,
        onColumn = { ExtensionTable.pkgName },
        otherColumn = { ExtensionUserTable.pkgName },
        additionalConstraint = { ExtensionUserTable.user eq userId },
    )
