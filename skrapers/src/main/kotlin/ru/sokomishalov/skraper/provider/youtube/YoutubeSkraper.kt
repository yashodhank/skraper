package ru.sokomishalov.skraper.provider.youtube

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ru.sokomishalov.skraper.Skraper
import ru.sokomishalov.skraper.SkraperClient
import ru.sokomishalov.skraper.client.jdk.DefaultBlockingSkraperClient
import ru.sokomishalov.skraper.fetchDocument
import ru.sokomishalov.skraper.internal.url.uriCleanUp
import ru.sokomishalov.skraper.model.Attachment
import ru.sokomishalov.skraper.model.AttachmentType.VIDEO
import ru.sokomishalov.skraper.model.ImageSize
import ru.sokomishalov.skraper.model.Post
import java.time.Duration
import java.time.Period
import java.time.temporal.ChronoUnit.DAYS

class YoutubeSkraper(
        override val client: SkraperClient = DefaultBlockingSkraperClient
) : Skraper {

    companion object {
        private const val YOUTUBE_BASE_URL = "https://www.youtube.com"
        private const val YOUTUBE_DEFAULT_VIDEO_ASPECT_RATIO = 210 / 117.5
    }

    override suspend fun getLatestPosts(uri: String, limit: Int): List<Post> {
        val document = getPage(uri)
        val videos = document
                ?.getElementsByClass("yt-lockup-video")
                ?.take(limit)
                .orEmpty()

        return videos.map {
            val linkElement = it.getElementsByClass("yt-uix-tile-link").firstOrNull()

            Post(
                    id = linkElement.parseId(),
                    caption = linkElement.parseCaption(),
                    publishTimestamp = it.parsePublishDate(),
                    attachments = listOf(Attachment(
                            url = "${YOUTUBE_BASE_URL}${linkElement?.attr("href")}",
                            type = VIDEO,
                            aspectRatio = YOUTUBE_DEFAULT_VIDEO_ASPECT_RATIO
                    ))
            )
        }
    }

    override suspend fun getPageLogoUrl(uri: String, imageSize: ImageSize): String? {
        val document = getPage(uri)

        return document
                ?.getElementsByAttributeValue("rel", "image_src")
                ?.firstOrNull()
                ?.attr("href")
    }

    private suspend fun getPage(uri: String): Document? {
        val finalUri = when {
            uri.endsWith("/videos") -> "${uri.uriCleanUp()}?gl=EN&hl=en"
            else -> "${uri.uriCleanUp()}/videos?gl=EN&hl=en"
        }

        return client.fetchDocument("${YOUTUBE_BASE_URL}/${finalUri}")
    }

    private fun Element?.parseId(): String {
        return this
                ?.attr("href")
                ?.substringAfter("/watch?v=")
                .orEmpty()
    }

    private fun Element?.parseCaption(): String {
        return this
                ?.attr("title")
                .orEmpty()
    }

    private fun Element?.parsePublishDate(): Long? {
        return this
                ?.getElementsByClass("yt-lockup-meta-info")
                ?.firstOrNull()
                ?.getElementsByTag("li")
                ?.getOrNull(1)
                ?.wholeText()
                ?.parseTimeAgo()
    }

    private fun CharSequence.parseTimeAgo(): Long {
        val now = System.currentTimeMillis()

        val amount = split(" ")
                .firstOrNull()
                ?.toIntOrNull()
                ?: 0

        val millisAgo: Long = when {
            contains("second", ignoreCase = true) -> Duration.ofSeconds(amount.toLong()).toMillis()
            contains("minute", ignoreCase = true) -> Duration.ofMinutes(amount.toLong()).toMillis()
            contains("hour", ignoreCase = true) -> Duration.ofHours(amount.toLong()).toMillis()
            contains("day", ignoreCase = true) -> Duration.ofDays(amount.toLong()).toMillis()
            contains("week", ignoreCase = true) -> Duration.ofDays(Period.ofWeeks(amount).get(DAYS)).toMillis()
            contains("month", ignoreCase = true) -> Duration.ofDays(Period.ofMonths(amount).get(DAYS)).toMillis()
            contains("year", ignoreCase = true) -> Duration.ofDays(Period.ofYears(amount).get(DAYS)).toMillis()
            else -> 0
        }

        return now - millisAgo
    }
}