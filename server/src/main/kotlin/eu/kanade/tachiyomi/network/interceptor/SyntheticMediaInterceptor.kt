package eu.kanade.tachiyomi.network.interceptor

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class SyntheticMediaInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != "synthetic.suwayomi.local") {
            return chain.proceed(request)
        }

        val kind = url.encodedPath.trim('/')
        val title = url.queryParameter("title").orEmpty().ifBlank { "Story" }
        val chapter = url.queryParameter("chapter").orEmpty().ifBlank { "Chapter 1" }
        val pageNum = url.queryParameter("page")?.toIntOrNull() ?: 1
        val totalPages = url.queryParameter("total")?.toIntOrNull() ?: 4
        val sourceName = url.queryParameter("source").orEmpty()
        val bodyText = url.queryParameter("text").orEmpty()
        val streamUrl = url.queryParameter("streamUrl").orEmpty()

        val pngBytes =
            if (kind == "video-frame") {
                renderVideoCard(
                    title = title,
                    episode = chapter,
                    pageNum = pageNum,
                    sourceName = sourceName,
                    synopsis = bodyText,
                    streamUrl = streamUrl,
                )
            } else {
                renderNovelPage(
                    title = title,
                    chapter = chapter,
                    pageNum = pageNum,
                    totalPages = totalPages,
                    sourceName = sourceName,
                    bodyText = bodyText,
                )
            }

        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "image/png")
            .body(pngBytes.toResponseBody("image/png".toMediaType()))
            .build()
    }

    private fun renderNovelPage(
        title: String,
        chapter: String,
        pageNum: Int,
        totalPages: Int,
        sourceName: String,
        bodyText: String,
    ): ByteArray {
        val width = 1080
        val height = 1620
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            // Warm book paper background
            g.color = Color(0xFA, 0xF6, 0xEE)
            g.fillRect(0, 0, width, height)

            // Subtle page border
            g.color = Color(0xE2, 0xD9, 0xC8)
            g.drawRect(44, 44, width - 88, height - 88)

            // Header: Book Title & Source
            g.color = Color(0x6E, 0x5D, 0x4F)
            g.font = Font(Font.SERIF, Font.ITALIC, 26)
            val headerText = if (sourceName.isNotBlank()) "$title  •  $sourceName" else title
            g.drawString(headerText.take(65), 88, 100)

            // Chapter heading on page 1, smaller on subsequent pages
            g.color = Color(0x23, 0x1F, 0x1C)
            if (pageNum == 1) {
                g.font = Font(Font.SERIF, Font.BOLD, 44)
                drawWrappedText(g, chapter, 88, 175, width - 176, 56, maxLines = 2)
                g.color = Color(0xC5, 0xB4, 0x9E)
                g.fillRect(88, 250, width - 176, 3)
            } else {
                g.font = Font(Font.SERIF, Font.BOLD, 30)
                g.drawString("$chapter (Cont.)", 88, 155)
                g.color = Color(0xD8, 0xCC, 0xBA)
                g.fillRect(88, 178, width - 176, 2)
            }

            // Body paragraphs
            val startY = if (pageNum == 1) 315 else 235
            g.color = Color(0x2B, 0x26, 0x23)
            g.font = Font(Font.SERIF, Font.PLAIN, 33)

            val paragraphs =
                bodyText
                    .split("\n\n", "\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

            var currentY = startY
            for (para in paragraphs) {
                currentY = drawWrappedText(g, "    $para", 88, currentY, width - 176, 50, maxLines = 22)
                currentY += 26
                if (currentY > height - 140) break
            }

            // Footer: Page number
            g.color = Color(0x7A, 0x6A, 0x58)
            g.font = Font(Font.SERIF, Font.PLAIN, 26)
            val footer = "— Page $pageNum of $totalPages —"
            val fm = g.fontMetrics
            g.drawString(footer, (width - fm.stringWidth(footer)) / 2, height - 78)
        } finally {
            g.dispose()
        }

        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun renderVideoCard(
        title: String,
        episode: String,
        pageNum: Int,
        sourceName: String,
        synopsis: String,
        streamUrl: String,
    ): ByteArray {
        val width = 1080
        val height = 1520
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            // Cinema dark background
            g.color = Color(0x0F, 0x14, 0x1C)
            g.fillRect(0, 0, width, height)

            // Video player frame box
            g.color = Color(0x1B, 0x24, 0x32)
            g.fillRoundRect(60, 80, width - 120, 560, 28, 28)

            // Play button circle in center of video frame
            val cx = width / 2
            val cy = 360
            g.color = Color(0xE5, 0x39, 0x35)
            g.fillOval(cx - 70, cy - 70, 140, 140)
            g.color = Color.WHITE
            val playTriangleX = intArrayOf(cx - 22, cx - 22, cx + 34)
            val playTriangleY = intArrayOf(cy - 34, cy + 34, cy)
            g.fillPolygon(playTriangleX, playTriangleY, 3)

            // Badge
            g.color = Color(0x90, 0xCA, 0xF9)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
            g.drawString(sourceName.ifBlank { "Video & Anime Stream" }, 68, 710)

            // Anime title
            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 44)
            var y = drawWrappedText(g, title, 68, 775, width - 136, 56, maxLines = 2)

            // Episode label
            g.color = Color(0xFF, 0xCA, 0x28)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 36)
            y += 20
            g.drawString("$episode  •  HD Stream Ready", 68, y)

            // Synopsis / instructions
            y += 65
            g.color = Color(0xCF, 0xD8, 0xDC)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 30)
            y = drawWrappedText(
                g,
                synopsis.ifBlank {
                    "Tap 'Watch Video' in the top bar or episode list to launch the interactive video stream player."
                },
                68,
                y,
                width - 136,
                44,
                maxLines = 10,
            )

            if (streamUrl.isNotBlank()) {
                y += 40
                g.color = Color(0x64, 0xB5, 0xF6)
                g.font = Font(Font.MONOSPACED, Font.PLAIN, 24)
                g.drawString("Stream Source: ${streamUrl.take(58)}", 68, y)
            }

            // Footer
            g.color = Color(0x78, 0x90, 0x9C)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 24)
            g.drawString("Scene Overview Card #$pageNum", 68, height - 68)
        } finally {
            g.dispose()
        }

        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun drawWrappedText(
        g: Graphics2D,
        text: String,
        x: Int,
        startY: Int,
        maxWidth: Int,
        lineHeight: Int,
        maxLines: Int,
    ): Int {
        val fm = g.fontMetrics
        val words = text.split(Regex("\\s+"))
        var line = StringBuilder()
        var y = startY
        var linesDrawn = 0

        for (word in words) {
            val testLine = if (line.isEmpty()) word else "$line $word"
            if (fm.stringWidth(testLine) > maxWidth && line.isNotEmpty()) {
                g.drawString(line.toString(), x, y)
                y += lineHeight
                linesDrawn++
                if (linesDrawn >= maxLines) return y
                line = StringBuilder(word)
            } else {
                line = StringBuilder(testLine)
            }
        }
        if (line.isNotEmpty() && linesDrawn < maxLines) {
            g.drawString(line.toString(), x, y)
            y += lineHeight
        }
        return y
    }
}
