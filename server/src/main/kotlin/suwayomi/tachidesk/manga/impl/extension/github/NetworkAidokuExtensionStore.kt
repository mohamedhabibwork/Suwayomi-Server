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
import kotlinx.serialization.json.JsonNames
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore
import java.security.MessageDigest

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NetworkAidokuExtensionStore(
    val name: String = "Aidoku Sources",
    val sources: List<AidokuSource>,
) : BaseNetworkExtensionStore {
    @Serializable
    data class AidokuSource(
        val id: String,
        val name: String,
        val version: Long = 1,
        @JsonNames("iconURL", "iconUrl", "icon")
        val iconUrl: String = "",
        @JsonNames("downloadURL", "downloadUrl")
        val downloadUrl: String = "",
        val languages: List<String> = emptyList(),
        val lang: String? = null,
        val contentRating: Int = 0,
        @JsonNames("baseURL", "baseUrl")
        val baseUrl: String? = null,
    )

    override fun toExtensionStore(indexUrl: String): ExtensionStore {
        val baseUrl = indexUrl.substringBeforeLast('/')
        return ExtensionStore(
            indexUrl = indexUrl,
            name = name,
            badgeLabel = name.take(6).uppercase(),
            signingKey = "aidoku:${indexUrl.hashCode()}",
            contact =
                ExtensionStore.Contact(
                    website = baseUrl,
                    discord = null,
                ),
            isLegacy = false,
            extensionListUrl = null,
        )
    }

    fun toExtensionInfos(store: ExtensionStore): List<ExtensionInfo> {
        val storeBaseUrl = store.indexUrl.substringBeforeLast('/')
        return sources.map { src ->
            val resolvedLangs =
                when {
                    src.languages.isNotEmpty() -> src.languages
                    !src.lang.isNullOrBlank() -> listOf(src.lang)
                    else -> listOf("all")
                }
            val primaryLang = if (resolvedLangs.size == 1) resolvedLangs.first() else "all"
            val pkgName = "aidoku.${src.id}"
            val icon =
                resolveUrl(storeBaseUrl, src.iconUrl).ifBlank {
                    "https://raw.githubusercontent.com/tachiyomiorg/tachiyomi/64ba127e7d43b1d7e6d58a6f5c9b2bd5fe0543f7/app/src/main/res/mipmap-xxxhdpi/ic_local_source.webp"
                }
            val download = resolveUrl(storeBaseUrl, src.downloadUrl)
            val home = src.baseUrl.orEmpty()
            val warning =
                when {
                    src.contentRating >= 2 -> ContentWarning.NSFW
                    src.contentRating == 1 -> ContentWarning.MIXED
                    else -> ContentWarning.SAFE
                }
            val sourceId = deterministicSourceId(pkgName, primaryLang)
            val netSource =
                NetworkExtensionStore.Source(
                    id = sourceId,
                    name = src.name,
                    language = primaryLang,
                    homeUrl = home,
                )
            ExtensionInfo(
                storeIndexUrl = store.indexUrl,
                name = src.name,
                pkgName = pkgName,
                apkUrl = download,
                jarUrl = "web-sources:" + Json.encodeToString(listOf(netSource)),
                iconUrl = icon,
                extensionLib = "1.5",
                versionCode = src.version,
                versionName = "1.${src.version}",
                lang = primaryLang,
                contentWarning = warning,
                sources =
                    listOf(
                        ExtensionSource(
                            id = sourceId,
                            name = src.name,
                            lang = primaryLang,
                            homeUrl = home,
                            message = null,
                            contentWarning = warning,
                        ),
                    ),
            )
        }
    }

    companion object {
        fun resolveUrl(
            baseUrl: String,
            pathOrUrl: String,
        ): String {
            if (pathOrUrl.isBlank()) return ""
            if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
                return pathOrUrl
            }
            return "${baseUrl.trimEnd('/')}/${pathOrUrl.trimStart('/')}"
        }

        fun deterministicSourceId(
            pkgName: String,
            lang: String,
        ): Long {
            val bytes = MessageDigest.getInstance("MD5").digest("$pkgName:$lang/1".toByteArray())
            return (0..7)
                .map { (bytes[it].toLong() and 0xff) shl (8 * (7 - it)) }
                .reduce(Long::or) and Long.MAX_VALUE
        }
    }
}
