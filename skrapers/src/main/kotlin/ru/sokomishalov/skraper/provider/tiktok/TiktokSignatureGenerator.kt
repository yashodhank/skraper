package ru.sokomishalov.skraper.provider.tiktok

import com.fasterxml.jackson.databind.JsonNode
import ru.sokomishalov.skraper.SkraperClient
import ru.sokomishalov.skraper.model.URLString


/**
 * Represents tiktok signature generating algorithm.
 * Tiktok changes this algorithm so often and that's why this interface exposed to the public library API.
 * In case of it changes again, you can reimplement (or modify) it by yourself.
 *
 * Current js-implementation looks like: @see [signature.js](https://github.com/drawrowfly/tiktok-scraper/blob/master/lib/helpers/signature.js)
 *
 * The easy way to not break up your head is to write simple NodeJS web-app which will generate a signature
 * and to write own JVM-implementation which will request it by HTTP.
 */
interface TiktokSignatureGenerator {

    /**
     * @param client http client
     * @param url request url
     * @param metadata user/trend info
     * @return generated signature
     */
    suspend fun generate(client: SkraperClient, url: URLString, metadata: JsonNode?): String

}