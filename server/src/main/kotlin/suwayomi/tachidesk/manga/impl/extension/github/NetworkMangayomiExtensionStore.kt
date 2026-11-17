package suwayomi.tachidesk.manga.impl.extension.github

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore

@Serializable
data class NetworkMangayomiExtension(
    val name: String,
    val id: Long,
    val baseUrl: String = "",
    val apiUrl: String = "",
    val lang: String = "all",
    val typeSource: String = "",
    val iconUrl: String = "",
    val isNsfw: Boolean = false,
    val sourceCodeUrl: String = "",
    val version: String = "0.1.0",
    val isManga: Boolean = true,
    val itemType: Int = 0,
) {
    fun toExtensionInfo(store: ExtensionStore): ExtensionInfo {
        val isNovel = itemType == 2 || store.indexUrl.contains("novel", ignoreCase = true)
        val isVideo = !isNovel && (!isManga || itemType == 1 || store.indexUrl.contains("anime", ignoreCase = true))
        val cleanSlug =
            name
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "")
                .ifBlank { id.toString() }
        val pkgPrefix =
            when {
                isNovel -> "mangayomi.novelextension"
                isVideo -> "mangayomi.animeextension"
                else -> "mangayomi.extension"
            }
        val pkgName = "$pkgPrefix.${lang.lowercase()}.$cleanSlug.$id".take(120)
        val resolvedBaseUrl =
            baseUrl
                .ifBlank { apiUrl }
                .ifBlank { "https://github.com/m2k3a/mangayomi-extensions" }
        val displayName =
            when {
                isNovel && !name.contains("(Novel)", ignoreCase = true) -> "$name (Novel)"
                isVideo && !name.contains("(Video)", ignoreCase = true) -> "$name (Video)"
                else -> name
            }
        val versionCode =
            version
                .split('.')
                .fold(0L) { acc, part -> acc * 100 + (part.toLongOrNull() ?: 0L) }
                .coerceAtLeast(1L)
        val warning = if (isNsfw) ContentWarning.NSFW else ContentWarning.SAFE
        val webSourcesPayload =
            "web-sources:" +
                Json.encodeToString(
                    listOf(
                        NetworkExtensionStore.Source(
                            id = id,
                            name = displayName,
                            language = lang.ifBlank { "all" },
                            homeUrl = resolvedBaseUrl,
                        ),
                    ),
                )

        return ExtensionInfo(
            storeIndexUrl = store.indexUrl,
            name = displayName.take(120),
            pkgName = pkgName,
            apkUrl = sourceCodeUrl.ifBlank { resolvedBaseUrl },
            jarUrl = webSourcesPayload,
            iconUrl =
                iconUrl.ifBlank {
                    "https://raw.githubusercontent.com/tachiyomiorg/tachiyomi/64ba127e7d43b1d7e6d58a6f5c9b2bd5fe0543f7/app/src/main/res/mipmap-xxxhdpi/ic_local_source.webp"
                },
            extensionLib = "1.4",
            versionCode = versionCode,
            versionName = version.take(16),
            lang = lang.ifBlank { "all" }.take(32),
            contentWarning = warning,
            sources =
                listOf(
                    ExtensionSource(
                        id = id,
                        name = displayName.take(120),
                        lang = lang.ifBlank { "all" }.take(32),
                        homeUrl = resolvedBaseUrl,
                        message = null,
                        contentWarning = warning,
                    ),
                ),
        )
    }
}
