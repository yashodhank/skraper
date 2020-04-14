/**
 * Copyright (c) 2019-present Mikhael Sokolov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.sokomishalov.skraper.provider.twitter

import com.fasterxml.jackson.databind.JsonNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ru.sokomishalov.skraper.*
import ru.sokomishalov.skraper.client.HttpMethodType.POST
import ru.sokomishalov.skraper.client.jdk.DefaultBlockingSkraperClient
import ru.sokomishalov.skraper.internal.jsoup.getFirstElementByClass
import ru.sokomishalov.skraper.internal.jsoup.getFirstElementByTag
import ru.sokomishalov.skraper.internal.jsoup.getStyle
import ru.sokomishalov.skraper.internal.serialization.*
import ru.sokomishalov.skraper.model.*
import java.time.Duration
import kotlin.text.Charsets.UTF_8


/**
 * @author sokomishalov
 */
class TwitterSkraper @JvmOverloads constructor(
        override val client: SkraperClient = DefaultBlockingSkraperClient,
        override val baseUrl: URLString = "https://twitter.com",
        private val apiBaseUrl: URLString = "https://api.twitter.com"
) : Skraper {

    override suspend fun getPosts(path: String, limit: Int): List<Post> {
        val page = getUserPage(path = path)

        val posts = page
                ?.body()
                ?.getElementById("stream-items-id")
                ?.getElementsByClass("stream-item")
                ?.take(limit)
                ?.mapNotNull { it.getFirstElementByClass("tweet") }
                .orEmpty()

        return posts.map {
            with(it) {
                Post(
                        id = extractTweetId(),
                        text = extractTweetText(),
                        rating = extractTweetLikes(),
                        commentsCount = extractTweetReplies(),
                        publishedAt = extractTweetPublishDate(),
                        media = extractTweetMediaItems()
                )
            }
        }
    }

    override suspend fun getPageInfo(path: String): PageInfo? {
        val page = getUserPage(path = path)

        val userJson = page
                ?.extractJsonData()
                ?.get("profile_user")

        return userJson?.run {
            PageInfo(
                    nick = getString("screen_name"),
                    name = getString("name"),
                    description = getString("description"),
                    postsCount = getInt("statuses_count"),
                    followersCount = getInt("followers_count"),
                    avatarsMap = singleImageMap(url = getFirstByPath("profile_image_url_https", "profile_image_url")?.asText()),
                    coversMap = singleImageMap(url = getFirstByPath("profile_background_image_url_https", "profile_background_image_url")?.asText())
            )
        }
    }

    override suspend fun resolve(media: Media): Media {
        return when (media) {
            is Image -> client.fetchOpenGraphMedia(media)
            is Video -> {
                val ogVideo = client.fetchOpenGraphMedia(media) as Video

                val page = client.fetchDocument(ogVideo.url)

                val urlFromPage = page
                        ?.getFirstElementByClass("js-tweet-text")
                        ?.getFirstElementByTag("a")
                        ?.attr("data-expanded-url")
                        .orEmpty()

                if (urlFromPage.isNotBlank()) {
                    ogVideo.copy(
                            url = urlFromPage,
                            thumbnail = null
                    )
                } else {
                    val jsUrl = page
                            ?.getElementsByTag("script")
                            ?.lastOrNull()
                            ?.attr("src")

                    val jsPage = jsUrl?.let {
                        client.fetchBytes(it)?.toString(UTF_8)
                    }

                    val token = jsPage?.let {
                        "Bearer ([a-zA-Z0-9%-])+"
                                .toRegex()
                                .find(it)
                                ?.groupValues
                                ?.firstOrNull()
                    }

                    val guestTokenNode = token?.let {
                        client.fetchJson(
                                url = apiBaseUrl.buildFullURL(path = "/1.1/guest/activate.json"),
                                method = POST,
                                headers = mapOf("Authorization" to it)
                        )
                    }

                    val guestToken = guestTokenNode?.getString("guest_token")

                    val playlistNode = guestToken?.let {
                        val tweetId = media
                                .url
                                .substringAfterLast("/status/")
                                .substringBefore("?")

                        client.fetchJson(
                                url = apiBaseUrl.buildFullURL(path = "/1.1/videos/tweet/config/${tweetId}.json"),
                                headers = mapOf(
                                        "x-guest-token" to it,
                                        "Authorization" to token
                                )
                        )
                    }

                    ogVideo.copy(
                            url = playlistNode
                                    ?.getString("track.playbackUrl")
                                    ?: ogVideo.url,
                            duration = playlistNode
                                    ?.getLong("track.durationMs")
                                    ?.let { Duration.ofMillis(it) }
                                    ?: ogVideo.duration
                    )
                }
            }
            else -> media
        }
    }

    private suspend fun getUserPage(path: String): Document? {
        return client.fetchDocument(url = baseUrl.buildFullURL(path = path))
    }

    private fun Document.extractJsonData(): JsonNode? {
        return getElementById("init-data")
                ?.attr("value")
                ?.readJsonNodes()
    }

    private fun Element.extractTweetId(): String {
        return getFirstElementByClass("js-stream-tweet")
                ?.attr("data-tweet-id")
                .orEmpty()
    }

    private fun Element.extractTweetText(): String? {
        return getFirstElementByClass("tweet-text")
                ?.apply {
                    allElements
                            .filter { it.tag().name == "a" && it.attr("href").startsWith("/").not() }
                            .forEach { it.remove() }
                }
                ?.wholeText()
    }

    private fun Element.extractTweetPublishDate(): Long? {
        return getFirstElementByClass("js-short-timestamp")
                ?.attr("data-time-ms")
                ?.toLongOrNull()
                ?.div(1000)
    }

    private fun Element.extractTweetLikes(): Int? {
        return getFirstElementByClass("ProfileTweet-action--favorite")
                ?.getFirstElementByClass("ProfileTweet-actionCount")
                ?.attr("data-tweet-stat-count")
                ?.toIntOrNull()
    }

    private fun Element.extractTweetReplies(): Int? {
        return getFirstElementByClass("ProfileTweet-action--reply")
                ?.getFirstElementByClass("ProfileTweet-actionCount")
                ?.attr("data-tweet-stat-count")
                ?.toIntOrNull()
    }

    private fun Element.extractTweetMediaItems(): List<Media> {
        val imagesElements = getElementsByClass("AdaptiveMedia-photoContainer")
        val videosElement = getFirstElementByClass("AdaptiveMedia-videoContainer")

        return when {
            imagesElements.isNotEmpty() -> {
                val aspectRatio = getFirstElementByClass("AdaptiveMedia-singlePhoto")
                        ?.getStyle("padding-top")
                        ?.substringAfter("calc(")
                        ?.substringBefore("* 100%")
                        ?.toDoubleOrNull()
                        ?.let { 1.0 / it }

                imagesElements.map { element ->
                    Image(
                            url = element.attr("data-image-url"),
                            aspectRatio = aspectRatio
                    )
                }
            }
            videosElement != null -> listOf(
                    Video(
                            url = "${baseUrl}/i/status/${extractTweetId()}",
                            aspectRatio = videosElement
                                    .getFirstElementByClass("PlayableMedia-player")
                                    ?.getStyle("padding-bottom")
                                    ?.removeSuffix("%")
                                    ?.toDoubleOrNull()
                                    ?.let { 100 / it }
                    )
            )
            else -> emptyList()
        }
    }
}
