package ru.sokomishalov.skraper.provider.tiktok

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import ru.sokomishalov.skraper.Skraper
import ru.sokomishalov.skraper.SkraperClient
import ru.sokomishalov.skraper.client.jdk.DefaultBlockingSkraperClient
import ru.sokomishalov.skraper.fetchDocument
import ru.sokomishalov.skraper.fetchJson
import ru.sokomishalov.skraper.internal.number.div
import ru.sokomishalov.skraper.internal.serialization.*
import ru.sokomishalov.skraper.model.*
import ru.sokomishalov.skraper.model.MediaSize.*
import java.time.Duration


class TikTokSkraper @JvmOverloads constructor(
        override val client: SkraperClient = DefaultBlockingSkraperClient,
        override val baseUrl: String = "https://tiktok.com",
        private val signer: TiktokSignatureGenerator = DefaultTiktokSignatureGenerator
) : Skraper {

    override suspend fun getPosts(path: String, limit: Int): List<Post> {
        val userData = getUser(path = path)

        val secUid = userData?.getString("secUid").orEmpty()
        val userId = userData?.getString("userId").orEmpty()

        val url = baseUrl.buildFullURL(path = "/share/item/list", queryParams = mapOf(
                "secUid" to secUid,
                "id" to userId,
                "type" to 1,
                "count" to limit,
                "minCursor" to 0,
                "maxCursor" to 0,
                "shareUid" to "",
                "lang" to "en"
        ))

        val signature = signer.generate(client = client, url = url, metadata = userData)

        val finalUrl = "${url}&_signature=${signature}"
        val headers = mapOf(
                "Referer" to "${baseUrl}${path}",
                "Origin" to baseUrl,
                "User-Agent" to USER_AGENT
        )

        val data = client.fetchJson(url = finalUrl, headers = headers)

        val items = data
                ?.getByPath("body.itemListData")
                ?.mapNotNull { it.getByPath("itemInfos") }
                ?.toList()
                .orEmpty()

        return items.map { item ->
            Post(
                    id = item.getString("id").orEmpty(),
                    text = item.getString("text"),
                    rating = item.getInt("diggCount"),
                    commentsCount = item.getInt("commentCount"),
                    viewsCount = item.getInt("playCount"),
                    media = item.getByPath("video")?.let { video ->
                        listOf(Video(
                                url = video.get("urls")?.firstOrNull()?.asText().orEmpty(),
                                aspectRatio = video.getDouble("videoMeta.width") / video.getDouble("videoMeta.height"),
                                duration = video.getLong("videoMeta.duration")?.let { sec -> Duration.ofSeconds(sec) }
                        ))
                    }.orEmpty()
            )
        }
    }

    override suspend fun getPageInfo(path: String): PageInfo? {
        val user = getUser(path = path)

        return user?.run {
            PageInfo(
                    nick = getString("uniqueId").orEmpty(),
                    name = getString("nickName"),
                    description = getString("signature"),
                    followersCount = getInt("fans"),
                    avatarsMap = mapOf(
                            SMALL to user.getFirstAvatar("covers").toImage(),
                            MEDIUM to user.getFirstAvatar("coversMedium", "covers").toImage(),
                            LARGE to user.getFirstAvatar("coversLarge", "coversMedium", "covers").toImage()
                    )
            )
        }
    }


    private suspend fun getUser(path: String): JsonNode? {
        val document = client.fetchDocument(url = "${baseUrl}${path}")

        val json = document
                ?.getElementById("__NEXT_DATA__")
                ?.html()
                ?.readJsonNodes()

        return json?.getByPath("props.pageProps.userData").apply {

            val tac = document
                    ?.getElementsByTag("script")
                    ?.firstOrNull { it.html().startsWith("tac=") }
                    ?.html()
                    ?.removeSurrounding("tac='", "'")

            tac?.let { (this as? ObjectNode)?.put("tac", it) }
        }
    }

    private fun JsonNode?.getFirstAvatar(vararg names: String): String {
        return names
                .mapNotNull {
                    this
                            ?.get(it)
                            ?.firstOrNull()
                            ?.asText()
                }
                .firstOrNull()
                .orEmpty()
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1"
    }
}