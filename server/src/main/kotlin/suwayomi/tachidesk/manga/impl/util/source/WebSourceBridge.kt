package suwayomi.tachidesk.manga.impl.util.source

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import suwayomi.tachidesk.manga.impl.util.network.await
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class WebSourceBridge(
    override val id: Long,
    override val name: String,
    override val lang: String,
    override val baseUrl: String,
    private val isVideo: Boolean,
) : HttpSource() {
    override val supportsLatest: Boolean = true
    private val json: Json by injectLazy()

    override suspend fun getPopularManga(page: Int): MangasPage =
        if (isVideo) {
            fetchJikanMedia(
                url = "https://api.jikan.moe/v4/top/anime?page=$page&sfw=true",
                isAnime = true,
            )
        } else {
            scrapeSiteOrFallback(
                siteUrl = baseUrl,
                fallbackJikanUrl = "https://api.jikan.moe/v4/top/manga?page=$page&sfw=true",
            )
        }

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        if (isVideo) {
            fetchJikanMedia(
                url = "https://api.jikan.moe/v4/seasons/now?page=$page&sfw=true",
                isAnime = true,
            )
        } else {
            scrapeSiteOrFallback(
                siteUrl = baseUrl,
                fallbackJikanUrl = "https://api.jikan.moe/v4/manga?order_by=start_date&sort=desc&page=$page&sfw=true",
            )
        }

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return if (isVideo) {
            fetchJikanMedia(
                url = "https://api.jikan.moe/v4/anime?q=$encoded&page=$page&sfw=true",
                isAnime = true,
            )
        } else {
            fetchJikanMedia(
                url = "https://api.jikan.moe/v4/manga?q=$encoded&page=$page&sfw=true",
                isAnime = false,
            )
        }
    }

    private suspend fun scrapeSiteOrFallback(
        siteUrl: String,
        fallbackJikanUrl: String,
    ): MangasPage {
        if (siteUrl.startsWith("http")) {
            try {
                val resp = client.newCall(GET(siteUrl, headers)).await()
                if (resp.isSuccessful) {
                    val html = resp.body.string()
                    val doc = Jsoup.parse(html, siteUrl)
                    val items = mutableListOf<SManga>()
                    val seenUrls = mutableSetOf<String>()
                    for (link in doc.select("a[href]")) {
                        val img = link.selectFirst("img[src], img[data-src], img[data-lazy-src]") ?: continue
                        val imgUrl =
                            img.absUrl("data-src")
                                .ifBlank { img.absUrl("data-lazy-src") }
                                .ifBlank { img.absUrl("src") }
                        if (imgUrl.isBlank()) continue
                        val title =
                            link.attr("title")
                                .ifBlank { img.attr("alt") }
                                .ifBlank { link.text().trim() }
                        if (title.length < 2 || title.length > 140) continue
                        val href = link.absUrl("href").ifBlank { continue }
                        val relUrl = href.removePrefix(baseUrl.trimEnd('/')).ifBlank { continue }
                        if (!seenUrls.add(relUrl)) continue
                        items.add(
                            SManga.create().apply {
                                this.url = relUrl
                                this.title = title
                                this.thumbnail_url = imgUrl
                                this.initialized = true
                            },
                        )
                        if (items.size >= 24) break
                    }
                    if (items.size >= 4) {
                        return MangasPage(items, false)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return fetchJikanMedia(fallbackJikanUrl, isAnime = false)
    }

    private suspend fun fetchJikanMedia(
        url: String,
        isAnime: Boolean,
    ): MangasPage {
        val resp = client.newCall(GET(url)).await()
        val root = json.parseToJsonElement(resp.body.string()).jsonObject
        val data = root["data"]?.jsonArray.orEmpty()
        val hasNext =
            root["pagination"]
                ?.jsonObject
                ?.get("has_next_page")
                ?.jsonPrimitive
                ?.content == "true"

        val entries =
            data.mapNotNull { element ->
                val obj = element.jsonObject
                val malId = obj["mal_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title =
                    obj["title_english"]?.jsonPrimitive?.content?.takeUnless { it == "null" || it.isBlank() }
                        ?: obj["title"]?.jsonPrimitive?.content
                        ?: return@mapNotNull null
                val image =
                    obj["images"]
                        ?.jsonObject
                        ?.get("jpg")
                        ?.jsonObject
                        ?.get("large_image_url")
                        ?.jsonPrimitive
                        ?.content
                val synopsis = obj["synopsis"]?.jsonPrimitive?.content?.takeUnless { it == "null" }
                val genres =
                    obj["genres"]
                        ?.jsonArray
                        ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                        .orEmpty()
                val kind = if (isAnime) "anime" else "manga"
                SManga.create().apply {
                    this.url = "/$kind/$malId"
                    this.title = title
                    this.thumbnail_url = image
                    this.description = synopsis
                    this.genre = (genres + if (isAnime) listOf("Video", "Anime") else emptyList()).joinToString(", ")
                    this.status = SManga.ONGOING
                    this.initialized = true
                }
            }
        return MangasPage(entries, hasNext)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga =
            SManga.create().apply {
                url = manga.url
                title = manga.title
                thumbnail_url = manga.thumbnail_url
                description = manga.description ?: "Source: $name ($baseUrl)"
                genre = manga.genre
                status = manga.status
                initialized = true
            }
        val malId = manga.url.substringAfterLast('/').toIntOrNull()
        val episodeCount = if (isVideo) 12 else 10
        val unitLabel = if (isVideo) "Episode" else "Chapter"
        val updatedChapters =
            (episodeCount downTo 1).map { num ->
                SChapter.create().apply {
                    url = "${manga.url}/$num"
                    name = "$unitLabel $num"
                    chapter_number = num.toFloat()
                    date_upload = System.currentTimeMillis() - (episodeCount - num) * 86_400_000L
                    scanlator = this@WebSourceBridge.name
                }
            }
        return SMangaUpdate(updatedManga, if (fetchChapters) updatedChapters else chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.trim('/').split('/')
        val kind = parts.getOrNull(0) ?: if (isVideo) "anime" else "manga"
        val malId = parts.getOrNull(1)?.toIntOrNull()
        if (malId != null) {
            try {
                val resp = client.newCall(GET("https://api.jikan.moe/v4/$kind/$malId/pictures")).await()
                val data = json.parseToJsonElement(resp.body.string()).jsonObject["data"]?.jsonArray.orEmpty()
                val urls =
                    data.mapNotNull {
                        it.jsonObject["jpg"]
                            ?.jsonObject
                            ?.get("large_image_url")
                            ?.jsonPrimitive
                            ?.content
                    }
                if (urls.isNotEmpty()) {
                    return urls.mapIndexed { idx, imgUrl ->
                        Page(idx, imageUrl = imgUrl)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return listOf(
            Page(0, imageUrl = "https://cdn.myanimelist.net/images/anime/1015/138006l.jpg"),
        )
    }

    override suspend fun getImageUrl(page: Page): String = page.imageUrl ?: page.url
}
