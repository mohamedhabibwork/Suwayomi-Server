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
data class NetworkLnReaderExtension(
    val id: String,
    val name: String,
    val site: String = "",
    val lang: String = "en",
    val version: String = "1.0.0",
    val url: String = "",
    val iconUrl: String = "",
) {
    fun toExtensionInfo(store: ExtensionStore): ExtensionInfo {
        val normalizedLang = normalizeLanguage(lang)
        val cleanSlug =
            id
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "")
                .ifBlank { name.lowercase().replace(Regex("[^a-z0-9]+"), "") }
                .ifBlank { "novel" }
        val pkgName = "lnreader.novelextension.$normalizedLang.$cleanSlug".take(120)
        val displayName =
            if (!name.contains("(Novel)", ignoreCase = true)) {
                "$name (Novel)"
            } else {
                name
            }
        val sourceId = NetworkAidokuExtensionStore.deterministicSourceId(pkgName, normalizedLang)
        val versionCode =
            version
                .split('.')
                .fold(0L) { acc, part -> acc * 100 + (part.toLongOrNull() ?: 0L) }
                .coerceAtLeast(1L)
        val isAdult =
            name.contains("18+", ignoreCase = true) ||
                name.contains("adult", ignoreCase = true) ||
                name.contains("hentai", ignoreCase = true) ||
                site.contains("adult", ignoreCase = true)
        val warning = if (isAdult) ContentWarning.NSFW else ContentWarning.SAFE
        val resolvedHome = site.ifBlank { "https://github.com/LNReader/lnreader-plugins" }
        val webSourcesPayload =
            "web-sources:" +
                Json.encodeToString(
                    listOf(
                        NetworkExtensionStore.Source(
                            id = sourceId,
                            name = displayName,
                            language = normalizedLang,
                            homeUrl = resolvedHome,
                        ),
                    ),
                )

        return ExtensionInfo(
            storeIndexUrl = store.indexUrl,
            name = displayName.take(120),
            pkgName = pkgName,
            apkUrl = url.ifBlank { resolvedHome },
            jarUrl = webSourcesPayload,
            iconUrl =
                iconUrl.ifBlank {
                    "https://raw.githubusercontent.com/tachiyomiorg/tachiyomi/64ba127e7d43b1d7e6d58a6f5c9b2bd5fe0543f7/app/src/main/res/mipmap-xxxhdpi/ic_local_source.webp"
                },
            extensionLib = "1.4",
            versionCode = versionCode,
            versionName = version.take(16),
            lang = normalizedLang,
            contentWarning = warning,
            sources =
                listOf(
                    ExtensionSource(
                        id = sourceId,
                        name = displayName.take(120),
                        lang = normalizedLang,
                        homeUrl = resolvedHome,
                        message = null,
                        contentWarning = warning,
                    ),
                ),
        )
    }

    companion object {
        private fun normalizeLanguage(raw: String): String {
            val cleaned = raw.replace("\u200e", "").replace("\u200f", "").trim().lowercase()
            return when {
                cleaned.contains("english") || cleaned == "en" -> "en"
                cleaned.contains("عرب") || cleaned == "ar" -> "ar"
                cleaned.contains("español") || cleaned == "es" -> "es"
                cleaned.contains("français") || cleaned == "fr" -> "fr"
                cleaned.contains("português") || cleaned == "pt" -> "pt"
                cleaned.contains("русский") || cleaned == "ru" -> "ru"
                cleaned.contains("中文") || cleaned == "zh" -> "zh"
                cleaned.contains("日本語") || cleaned == "ja" -> "ja"
                cleaned.contains("한국어") || cleaned == "ko" -> "ko"
                cleaned.contains("indonesia") || cleaned == "id" -> "id"
                cleaned.contains("türkçe") || cleaned == "tr" -> "tr"
                cleaned.contains("tiếng việt") || cleaned == "vi" -> "vi"
                cleaned.length in 2..5 -> cleaned
                else -> "en"
            }
        }
    }
}
