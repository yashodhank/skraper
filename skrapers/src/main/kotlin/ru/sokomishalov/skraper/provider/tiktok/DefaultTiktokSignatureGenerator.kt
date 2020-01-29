package ru.sokomishalov.skraper.provider.tiktok

import com.fasterxml.jackson.databind.JsonNode
import ru.sokomishalov.skraper.SkraperClient
import ru.sokomishalov.skraper.model.URLString
import java.util.*


/**
 * Default implementation for tiktok signature generator
 */
object DefaultTiktokSignatureGenerator : TiktokSignatureGenerator {
    override suspend fun generate(client: SkraperClient, url: URLString, metadata: JsonNode?): String {
        return UUID.randomUUID().toString()
    }
}