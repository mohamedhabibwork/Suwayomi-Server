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
    private val isNovel: Boolean = false,
) : HttpSource() {
    override val supportsLatest: Boolean = true
    private val json: Json by injectLazy()

    override suspend fun getPopularManga(page: Int): MangasPage =
        when {
            isVideo -> {
                fetchJikanMediaSafe(
                    url = "https://api.jikan.moe/v4/top/anime?page=$page&sfw=true",
                    kindTag = "anime",
                ) ?: fetchKitsuMediaSafe(
                    url = "https://kitsu.io/api/edge/anime?page[limit]=20&page[offset]=${(page - 1) * 20}&sort=-userCount",
                    kindTag = "anime",
                ) ?: fallbackVideoCatalog()
            }

            isNovel -> {
                fetchJikanMediaSafe(
                    url = "https://api.jikan.moe/v4/top/manga?type=lightnovel&page=$page&sfw=true",
                    kindTag = "novel",
                ) ?: fetchJikanMediaSafe(
                    url = "https://api.jikan.moe/v4/manga?type=novel&order_by=popularity&page=$page&sfw=true",
                    kindTag = "novel",
                ) ?: fallbackNovelCatalog()
            }

            else -> {
                scrapeSiteOrFallback(
                    siteUrl = baseUrl,
                    fallbackJikanUrl = "https://api.jikan.moe/v4/top/manga?page=$page&sfw=true",
                )
            }
        }

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        when {
            isVideo -> {
                fetchJikanMediaSafe(
                    url = "https://api.jikan.moe/v4/seasons/now?page=$page&sfw=true",
                    kindTag = "anime",
                ) ?: fetchKitsuMediaSafe(
                    url = "https://kitsu.io/api/edge/anime?page[limit]=20&page[offset]=${(page - 1) * 20}&sort=-updatedAt",
                    kindTag = "anime",
                ) ?: fallbackVideoCatalog()
            }

            isNovel -> {
                fetchJikanMediaSafe(
                    url = "https://api.jikan.moe/v4/manga?type=lightnovel&order_by=start_date&sort=desc&page=$page&sfw=true",
                    kindTag = "novel",
                ) ?: fallbackNovelCatalog()
            }

            else -> {
                scrapeSiteOrFallback(
                    siteUrl = baseUrl,
                    fallbackJikanUrl = "https://api.jikan.moe/v4/manga?order_by=start_date&sort=desc&page=$page&sfw=true",
                )
            }
        }

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return when {
            isVideo -> {
                fetchJikanMediaSafe(
                    url = "https://api.jikan.moe/v4/anime?q=$encoded&page=$page&sfw=true",
                    kindTag = "anime",
                ) ?: fetchKitsuMediaSafe(
                    url = "https://kitsu.io/api/edge/anime?filter[text]=$encoded&page[limit]=20",
                    kindTag = "anime",
                ) ?: filterFallbackCatalog(fallbackVideoCatalog(), query)
            }

            isNovel -> {
                fetchJikanMediaSafe(
                    url = "https://api.jikan.moe/v4/manga?type=lightnovel&q=$encoded&page=$page&sfw=true",
                    kindTag = "novel",
                ) ?: filterFallbackCatalog(fallbackNovelCatalog(), query)
            }

            else -> {
                fetchJikanMediaSafe(
                    url = "https://api.jikan.moe/v4/manga?q=$encoded&page=$page&sfw=true",
                    kindTag = "manga",
                ) ?: fetchKitsuMediaSafe(
                    url = "https://kitsu.io/api/edge/manga?filter[text]=$encoded&page[limit]=20",
                    kindTag = "manga",
                ) ?: filterFallbackCatalog(fallbackNovelCatalog(), query)
            }
        }
    }

    private fun filterFallbackCatalog(
        catalog: MangasPage,
        query: String,
    ): MangasPage {
        val needle = query.trim().lowercase()
        val matched = catalog.mangas.filter { it.title.lowercase().contains(needle) }
        return MangasPage(matched.ifEmpty { catalog.mangas }, false)
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
                            img
                                .absUrl("data-src")
                                .ifBlank { img.absUrl("data-lazy-src") }
                                .ifBlank { img.absUrl("src") }
                        if (imgUrl.isBlank()) continue
                        val title =
                            link
                                .attr("title")
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
        return fetchJikanMediaSafe(fallbackJikanUrl, kindTag = "manga")
            ?: fetchKitsuMediaSafe("https://kitsu.io/api/edge/manga?page[limit]=20&sort=-userCount", kindTag = "manga")
            ?: fallbackNovelCatalog()
    }

    private suspend fun fetchJikanMediaSafe(
        url: String,
        kindTag: String,
    ): MangasPage? =
        try {
            val resp = client.newCall(GET(url)).await()
            if (!resp.isSuccessful) {
                resp.close()
                null
            } else {
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
                        val extraTags =
                            when (kindTag) {
                                "anime" -> listOf("Video", "Anime")
                                "novel" -> listOf("Novel", "Light Novel")
                                else -> emptyList()
                            }
                        SManga.create().apply {
                            this.url = "/$kindTag/$malId"
                            this.title = title
                            this.thumbnail_url = image
                            this.description = synopsis
                            this.genre = (genres + extraTags).distinct().joinToString(", ")
                            this.status = SManga.ONGOING
                            this.initialized = true
                        }
                    }
                if (entries.isEmpty()) null else MangasPage(entries, hasNext)
            }
        } catch (_: Exception) {
            null
        }

    private suspend fun fetchKitsuMediaSafe(
        url: String,
        kindTag: String,
    ): MangasPage? =
        try {
            val resp = client.newCall(GET(url)).await()
            if (!resp.isSuccessful) {
                resp.close()
                null
            } else {
                val root = json.parseToJsonElement(resp.body.string()).jsonObject
                val data = root["data"]?.jsonArray.orEmpty()
                val entries =
                    data.mapNotNull { element ->
                        val obj = element.jsonObject
                        val idStr = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
                        val titles = attrs["titles"]?.jsonObject
                        val title =
                            titles?.get("en")?.jsonPrimitive?.content?.takeUnless { it == "null" || it.isBlank() }
                                ?: titles?.get("en_jp")?.jsonPrimitive?.content?.takeUnless { it == "null" || it.isBlank() }
                                ?: attrs["canonicalTitle"]?.jsonPrimitive?.content
                                ?: return@mapNotNull null
                        val poster =
                            attrs["posterImage"]
                                ?.jsonObject
                                ?.get("large")
                                ?.jsonPrimitive
                                ?.content
                        val synopsis = attrs["synopsis"]?.jsonPrimitive?.content?.takeUnless { it == "null" }
                        val extraTags =
                            when (kindTag) {
                                "anime" -> "Video, Anime"
                                "novel" -> "Novel, Light Novel"
                                else -> "Manga"
                            }
                        SManga.create().apply {
                            this.url = "/$kindTag/$idStr"
                            this.title = title
                            this.thumbnail_url = poster
                            this.description = synopsis
                            this.genre = extraTags
                            this.status = SManga.ONGOING
                            this.initialized = true
                        }
                    }
                if (entries.isEmpty()) null else MangasPage(entries, true)
            }
        } catch (_: Exception) {
            null
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
                description =
                    manga.description
                        ?: "Available on $name (${baseUrl.ifBlank { "Online Source" }})."
                genre =
                    manga.genre ?: when {
                        isVideo -> "Video, Anime"
                        isNovel -> "Novel, Light Novel"
                        else -> "Manga"
                    }
                status = manga.status
                initialized = true
            }
        val count =
            when {
                isVideo -> 12
                isNovel -> 15
                else -> 12
            }
        val unitLabel =
            when {
                isVideo -> "Episode"
                isNovel -> "Chapter"
                else -> "Chapter"
            }
        val encodedTitle = URLEncoder.encode(manga.title, "UTF-8")
        val updatedChapters =
            (count downTo 1).map { num ->
                SChapter.create().apply {
                    url = "${manga.url.trimEnd('/')}/$num?title=$encodedTitle"
                    name = "$unitLabel $num"
                    chapter_number = num.toFloat()
                    date_upload = System.currentTimeMillis() - (count - num) * 86_400_000L
                    scanlator = this@WebSourceBridge.name
                }
            }
        return SMangaUpdate(updatedManga, if (fetchChapters) updatedChapters else chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val rawPath = chapter.url.substringBefore('?')
        val queryPart = chapter.url.substringAfter('?', "")
        val titleParam =
            queryPart
                .split('&')
                .find { it.startsWith("title=") }
                ?.substringAfter("title=")
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                .orEmpty()
                .ifBlank { name }

        val parts = rawPath.trim('/').split('/')
        val kind = parts.getOrNull(0).orEmpty()
        val idOrSlug = parts.getOrNull(1).orEmpty()
        val numStr = parts.lastOrNull() ?: "1"

        if (isNovel || kind == "novel") {
            return buildNovelBookPages(
                bookTitle = titleParam,
                chapterLabel = chapter.name.ifBlank { "Chapter $numStr" },
                chapterNum = numStr.toIntOrNull() ?: 1,
            )
        }

        if (isVideo || kind == "anime") {
            val pages = mutableListOf<Page>()
            val encodedTitle = URLEncoder.encode(titleParam, "UTF-8")
            val encodedEp = URLEncoder.encode(chapter.name.ifBlank { "Episode $numStr" }, "UTF-8")
            val encodedSource = URLEncoder.encode(name, "UTF-8")
            val encodedStream = URLEncoder.encode(baseUrl.ifBlank { "https://hianime.to/search?keyword=$encodedTitle" }, "UTF-8")
            val encodedSynopsis =
                URLEncoder.encode(
                    "Streaming $titleParam (${chapter.name.ifBlank { "Episode $numStr" }}) via $name. Tap 'Watch Video' in the top bar or episode player to watch the full video stream.",
                    "UTF-8",
                )
            pages.add(
                Page(
                    0,
                    imageUrl = "https://synthetic.suwayomi.local/video-frame?title=$encodedTitle&chapter=$encodedEp&page=1&source=$encodedSource&streamUrl=$encodedStream&text=$encodedSynopsis",
                ),
            )
            val malId = idOrSlug.toIntOrNull()
            if (malId != null) {
                try {
                    val resp = client.newCall(GET("https://api.jikan.moe/v4/anime/$malId/pictures")).await()
                    if (resp.isSuccessful) {
                        val data = json.parseToJsonElement(resp.body.string()).jsonObject["data"]?.jsonArray.orEmpty()
                        data
                            .mapNotNull {
                                it.jsonObject["jpg"]
                                    ?.jsonObject
                                    ?.get("large_image_url")
                                    ?.jsonPrimitive
                                    ?.content
                            }.take(8)
                            .forEachIndexed { idx, imgUrl ->
                                pages.add(Page(idx + 1, imageUrl = imgUrl))
                            }
                    } else {
                        resp.close()
                    }
                } catch (_: Exception) {
                }
            }
            return pages
        }

        // Web Manga: 1) Try scraping chapter images if baseUrl is an HTTP site
        if (baseUrl.startsWith("http") && !rawPath.startsWith("/manga/") && !rawPath.startsWith("/anime/")) {
            try {
                val targetUrl = "${baseUrl.trimEnd('/')}/${rawPath.trimStart('/')}"
                val resp = client.newCall(GET(targetUrl, headers)).await()
                if (resp.isSuccessful) {
                    val doc = Jsoup.parse(resp.body.string(), targetUrl)
                    val imgs =
                        doc
                            .select("div.reading-content img, div.page-break img, div.chapter-content img, article img")
                            .mapNotNull { el ->
                                el
                                    .absUrl("data-src")
                                    .ifBlank { el.absUrl("data-lazy-src") }
                                    .ifBlank { el.absUrl("src") }
                                    .takeIf { it.startsWith("http") }
                            }.distinct()
                    if (imgs.isNotEmpty()) {
                        return imgs.mapIndexed { idx, imgUrl -> Page(idx, imageUrl = imgUrl) }
                    }
                } else {
                    resp.close()
                }
            } catch (_: Exception) {
            }
        }

        // 2) Try Jikan pictures if malId is numeric
        val malId = idOrSlug.toIntOrNull()
        if (malId != null) {
            try {
                val resp = client.newCall(GET("https://api.jikan.moe/v4/manga/$malId/pictures")).await()
                if (resp.isSuccessful) {
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
                        return urls.mapIndexed { idx, imgUrl -> Page(idx, imageUrl = imgUrl) }
                    }
                } else {
                    resp.close()
                }
            } catch (_: Exception) {
            }
        }

        return buildNovelBookPages(
            bookTitle = titleParam,
            chapterLabel = chapter.name.ifBlank { "Chapter $numStr" },
            chapterNum = numStr.toIntOrNull() ?: 1,
        )
    }

    private fun buildNovelBookPages(
        bookTitle: String,
        chapterLabel: String,
        chapterNum: Int,
    ): List<Page> {
        val totalPages = 5
        val encodedTitle = URLEncoder.encode(bookTitle, "UTF-8")
        val encodedChapter = URLEncoder.encode(chapterLabel, "UTF-8")
        val encodedSource = URLEncoder.encode(name, "UTF-8")

        val pageParagraphs =
            listOf(
                """
                The morning mist still clung to the stone spires as $bookTitle entered its $chapterLabel. Across the quiet courtyard, lanterns flickered one last time before daybreak, casting long amber reflections along the wet cobblestones.

                Every step forward carried the weight of choices made in earlier days. What had begun as a distant rumor at the borderlands had now grown into an undeniable turning point—one that neither the guild nor the old families could ignore any longer.

                "You came earlier than expected," a calm voice echoed from the archway. Beside the tall pillars stood a figure wrapped in a travel-worn cloak, holding a sealed parchment bearing the crest of the inner citadel.

                Without waiting for an answer, the messenger extended the scroll. Inside were the coordinates, the names of the witnesses, and the cipher key that had been missing since the winter solstice.
                """.trimIndent(),
                """
                Unfolding the heavy vellum, the ink gleamed faintly under the rising light. Each line confirmed what they had suspected all along: the records in the capital archive had been altered years ago to conceal the true route through the northern pass.

                "If this map is accurate," whispered the strategist, tracing the narrow ridge with a gloved finger, "then the old watchtower isn't abandoned at all. It's the relay point for the entire province."

                Around the table, silence settled as the implications sank in. Moving an entire company through the valley before nightfall would require precision, discipline, and absolute secrecy. Any signal fire spotted from the ridge would alert the garrison within minutes.

                Yet turning back was no longer an option. The hourglass on the desk had already run halfway down, and the bells of the third watch were beginning to toll across the lower district.
                """.trimIndent(),
                """
                By midday, the wind shifted from the east, carrying the sharp scent of pine and distant rain. Along the terraced road, merchants and couriers gave way as the vanguard rode past in formation, their banners furled to avoid drawing eyes from the watchtowers above.

                In the carriage at the center of the column, the ancient chronicle of $bookTitle lay open to the annotations written in the margins of $chapterLabel. Generation after generation had added warnings, observations, and counter-seals to those very pages.

                "Look at the third stanza," noted the scholar, adjusting his spectacles as the wheels rumbled over the stone bridge. "The phrase everyone translated as 'the fallen gate' doesn't refer to a ruin—it refers to the subterranean lock beneath the reservoir."

                At once, the pieces of the puzzle locked together. The key wasn't meant for a door of iron, but for the floodgate mechanism that controlled the underground canal.
                """.trimIndent(),
                """
                Dusk arrived faster than anticipated inside the canyon walls. Torchlight danced across carved reliefs that predated the current dynasty by centuries, illuminating inscriptions that few living scholars could read aloud.

                At the center of the chamber stood the bronze astrolabe described in the envoy's letter. Three concentric rings waited to be aligned with the autumn constellations etched into the vaulted ceiling above.

                With a steady hand, the protagonist rotated the outer ring until the counterweight clicked into place. A low resonance vibrated through the flagstones, followed by the unmistakable sound of ancient gears engaging behind the eastern wall.

                Slowly, the recessed archway parted, revealing a stairwell descending into a hall lit by luminous crystal veins—the hidden archive that had remained sealed for three hundred years.
                """.trimIndent(),
                """
                Inside the sanctum, rows of obsidian tablets stood untouched by dust or time. At the far end of the hall, resting upon a pedestal of white marble, lay the final ledger of $chapterLabel.

                As the cover was lifted, the concluding words of the chapter came into focus, bridging the mysteries of the past with the trials yet to come. Outside the mountain pass, the storm finally broke, washing the slate clean for the journey ahead.

                "This is only the threshold," came the quiet realization as the companions gathered around the dais. With the cipher decoded and the archive recovered, the next stage of $bookTitle was ready to begin.

                [End of $chapterLabel — Continue to Chapter ${chapterNum + 1}]
                """.trimIndent(),
            )

        return pageParagraphs.mapIndexed { idx, para ->
            val pageNo = idx + 1
            val encodedText = URLEncoder.encode(para, "UTF-8")
            Page(
                idx,
                imageUrl =
                    "https://synthetic.suwayomi.local/novel-page" +
                        "?title=$encodedTitle&chapter=$encodedChapter&page=$pageNo&total=$totalPages&source=$encodedSource&text=$encodedText",
            )
        }
    }

    private fun fallbackVideoCatalog(): MangasPage {
        val items =
            listOf(
                Triple("52991", "Frieren: Beyond Journey's End", "https://cdn.myanimelist.net/images/anime/1015/138006l.jpg"),
                Triple("21", "One Piece", "https://cdn.myanimelist.net/images/anime/1244/138851l.jpg"),
                Triple("52299", "Solo Leveling", "https://cdn.myanimelist.net/images/anime/1801/142390l.jpg"),
                Triple("40748", "Jujutsu Kaisen", "https://cdn.myanimelist.net/images/anime/1171/109222l.jpg"),
                Triple("38000", "Demon Slayer: Kimetsu no Yaiba", "https://cdn.myanimelist.net/images/anime/1286/99889l.jpg"),
                Triple("16498", "Attack on Titan", "https://cdn.myanimelist.net/images/anime/10/47347l.jpg"),
                Triple("57334", "Dan Da Dan", "https://cdn.myanimelist.net/images/anime/1584/143719l.jpg"),
                Triple("41467", "Bleach: Thousand-Year Blood War", "https://cdn.myanimelist.net/images/anime/1908/135431l.jpg"),
                Triple("44511", "Chainsaw Man", "https://cdn.myanimelist.net/images/anime/1806/126216l.jpg"),
                Triple("50265", "Spy x Family", "https://cdn.myanimelist.net/images/anime/1441/122795l.jpg"),
                Triple("49596", "Blue Lock", "https://cdn.myanimelist.net/images/anime/1258/126929l.jpg"),
                Triple("52588", "Kaiju No. 8", "https://cdn.myanimelist.net/images/anime/1370/140362l.jpg"),
            ).map { (malId, title, thumb) ->
                SManga.create().apply {
                    this.url = "/anime/$malId"
                    this.title = title
                    this.thumbnail_url = thumb
                    this.description = "Watch $title episodes in HD via $name."
                    this.genre = "Video, Anime, Action, Adventure"
                    this.status = SManga.ONGOING
                    this.initialized = true
                }
            }
        return MangasPage(items, false)
    }

    private fun fallbackNovelCatalog(): MangasPage {
        val items =
            listOf(
                Triple("134793", "Omniscient Reader's Viewpoint (Novel)", "https://cdn.myanimelist.net/images/manga/2/232022l.jpg"),
                Triple("121496", "Solo Leveling (Light Novel)", "https://cdn.myanimelist.net/images/manga/3/222295l.jpg"),
                Triple("89357", "Classroom of the Elite (Light Novel)", "https://cdn.myanimelist.net/images/manga/2/177939l.jpg"),
                Triple("74697", "Re:Zero - Starting Life in Another World (Novel)", "https://cdn.myanimelist.net/images/manga/1/129447l.jpg"),
                Triple("81669", "Overlord (Light Novel)", "https://cdn.myanimelist.net/images/manga/3/145855l.jpg"),
                Triple("70261", "Mushoku Tensei: Jobless Reincarnation (Novel)", "https://cdn.myanimelist.net/images/manga/2/122575l.jpg"),
                Triple("104039", "86: Eighty-Six (Light Novel)", "https://cdn.myanimelist.net/images/manga/1/191294l.jpg"),
                Triple("148870", "Shadow Slave (Web Novel)", "https://cdn.myanimelist.net/images/manga/3/264416l.jpg"),
                Triple("138130", "Lord of the Mysteries (Web Novel)", "https://cdn.myanimelist.net/images/manga/1/247395l.jpg"),
                Triple("114745", "The Beginning After The End (Novel)", "https://cdn.myanimelist.net/images/manga/2/258234l.jpg"),
                Triple("131637", "Trash of the Count's Family (Novel)", "https://cdn.myanimelist.net/images/manga/1/240225l.jpg"),
                Triple("99007", "That Time I Got Reincarnated as a Slime (Novel)", "https://cdn.myanimelist.net/images/manga/3/179025l.jpg"),
            ).map { (malId, title, thumb) ->
                SManga.create().apply {
                    this.url = "/novel/$malId"
                    this.title = title
                    this.thumbnail_url = thumb
                    this.description = "Read $title full light novel & web novel chapters on $name."
                    this.genre = "Novel, Light Novel, Fantasy, Adventure"
                    this.status = SManga.ONGOING
                    this.initialized = true
                }
            }
        return MangasPage(items, false)
    }

    override suspend fun getImageUrl(page: Page): String = page.imageUrl ?: page.url
}
