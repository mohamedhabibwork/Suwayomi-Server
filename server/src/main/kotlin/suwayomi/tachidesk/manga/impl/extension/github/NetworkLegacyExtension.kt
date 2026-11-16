package suwayomi.tachidesk.manga.impl.extension.github

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NetworkLegacyExtension(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val version: String,
    val code: Long,
    val nsfw: Int,
    val sources: List<Source>? = null,
) {
    @Serializable
    data class Source(
        val id: Long,
        val lang: String,
        val name: String,
        val baseUrl: String,
    )
}

fun NetworkLegacyExtension.toExtensionInfo(
    store: ExtensionStore,
    storeBaseUrl: String,
): ExtensionInfo {
    val isAnime = pkg.contains(".animeextension.") || name.startsWith("Aniyomi:", ignoreCase = true)
    val cleanBaseName =
        name
            .removePrefix("Tachiyomi: ")
            .removePrefix("Aniyomi: ")
            .trim()
    val displayName =
        if (isAnime && !cleanBaseName.contains("(Video)", ignoreCase = true)) {
            "$cleanBaseName (Video)"
        } else {
            cleanBaseName
        }
    val warning = if (nsfw == 1) ContentWarning.NSFW else ContentWarning.SAFE
    val mappedSources =
        if (sources.isNullOrEmpty()) {
            listOf(
                ExtensionSource(
                    id = NetworkAidokuExtensionStore.deterministicSourceId(pkg, lang),
                    name = displayName,
                    lang = lang,
                    homeUrl = "",
                    message = null,
                    contentWarning = warning,
                ),
            )
        } else {
            sources.map { source ->
                val srcName =
                    if (isAnime && !source.name.contains("(Video)", ignoreCase = true)) {
                        "${source.name.trim()} (Video)"
                    } else {
                        source.name.trim()
                    }
                ExtensionSource(
                    id = source.id,
                    name = srcName,
                    lang = source.lang,
                    homeUrl = source.baseUrl,
                    message = null,
                    contentWarning = warning,
                )
            }
        }
    val webSourcesPayload =
        if (isAnime) {
            "web-sources:" +
                Json.encodeToString(
                    mappedSources.take(4).map { src ->
                        NetworkExtensionStore.Source(
                            id = src.id,
                            name = src.name,
                            language = src.lang,
                            homeUrl = src.homeUrl,
                        )
                    },
                )
        } else {
            null
        }

    return ExtensionInfo(
        storeIndexUrl = store.indexUrl,
        name = displayName,
        pkgName = pkg,
        apkUrl = "$storeBaseUrl/apk/$apk",
        jarUrl = webSourcesPayload,
        iconUrl = "$storeBaseUrl/icon/$pkg.png",
        extensionLib = version.substringBeforeLast('.'),
        versionCode = code,
        versionName = version,
        lang = lang,
        contentWarning = warning,
        sources = mappedSources,
    )
}
