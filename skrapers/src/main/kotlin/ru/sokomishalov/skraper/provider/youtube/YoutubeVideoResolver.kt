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
package ru.sokomishalov.skraper.provider.youtube

import com.fasterxml.jackson.databind.JsonNode
import ru.sokomishalov.skraper.SkraperClient
import ru.sokomishalov.skraper.fetchBytes
import ru.sokomishalov.skraper.internal.consts.DEFAULT_USER_AGENT
import ru.sokomishalov.skraper.internal.number.div
import ru.sokomishalov.skraper.internal.serialization.getFirstByPath
import ru.sokomishalov.skraper.internal.serialization.getInt
import ru.sokomishalov.skraper.internal.serialization.getString
import ru.sokomishalov.skraper.internal.serialization.readJsonNodes
import ru.sokomishalov.skraper.internal.string.unescapeJson
import ru.sokomishalov.skraper.internal.string.unescapeUrl
import ru.sokomishalov.skraper.model.Video
import java.util.regex.Pattern.DOTALL
import kotlin.text.Charsets.UTF_8

/**
 * A reduced version of this library
 * @see <a href="https://github.com/sealedtx/java-youtube-downloader">sealedtx/java-youtube-downloader</a>
 */
class YoutubeVideoResolver(private val client: SkraperClient) {

    suspend fun getVideo(video: Video): Video? {
        val page = client.fetchBytes(
                url = video.url,
                headers = mapOf(
                        "Accept-language" to "en-US,en;",
                        "User-Agent" to DEFAULT_USER_AGENT
                )
        )?.toString(UTF_8)

        val config = page?.let {
            ";ytplayer\\.config = (\\{.*?});"
                    .toRegex()
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.readJsonNodes()
        }

        return config?.firstAudioAndVideo()
    }

    private suspend fun JsonNode.firstAudioAndVideo(): Video? {
        val streamingData = getFirstByPath("args.player_response")
                .toString()
                .removeSurrounding("\"")
                .unescapeJson()
                .readJsonNodes()
                ?.get("streamingData")

        val formats = streamingData?.get("formats")?.toList().orEmpty()
        val adaptiveFormats = streamingData?.get("adaptiveFormats")?.toList().orEmpty()

        return (formats + adaptiveFormats)
                .firstOrNull { it.getInt("itag") in VIDEO_AND_AUDIO_TAGS }
                ?.parseVideo(getString("assets.js"))
    }

    private suspend fun JsonNode.parseVideo(jsPath: String?): Video? {
        val url = when {
            has("cipher") -> {
                val cipherData = getString("cipher")
                        .orEmpty()
                        .replace("\\u0026", "&")
                        .split("&".toRegex())
                        .toTypedArray()

                val jsonCipher = cipherData
                        .map { it.split("=".toRegex()) }
                        .map { it[0] to it[1] }
                        .toMap()

                val urlWithSig = jsonCipher["url"]
                        ?.unescapeUrl()
                        .orEmpty()

                when {
                    "signature" in urlWithSig
                            || "s" !in jsonCipher
                            && ("&sig=" in urlWithSig || "&lsig=" in urlWithSig) -> {
                        getString("url")
                    }
                    else -> {
                        val jsUrl = "https://youtube.com${jsPath}"
                        val signature = getSignature(jsUrl = jsUrl, s = jsonCipher["s"]?.unescapeUrl().orEmpty())
                        val decipheredUrl = "$urlWithSig&sig=$signature"
                        decipheredUrl
                    }
                }
            }
            else -> {
                getString("url")
            }
        }

        return url?.let {
            Video(
                    url = it,
                    aspectRatio = getInt("width") / getInt("height")
            )
        }
    }

    private suspend fun getSignature(jsUrl: String, s: String): String {
        val js = client.fetchBytes(
                url = jsUrl,
                headers = mapOf(
                        "Accept-language" to "en-US,en;",
                        "User-Agent" to DEFAULT_USER_AGENT
                )
        )?.toString(UTF_8)

        val transformFunctions = js.getTransformFunctions()
        val variable = transformFunctions.firstOrNull()?.variable
        val transformFunctionsMap = js.getTransformFunctionsMap(variable)

        return transformFunctions
                .fold(s.toCharArray(), { sign: CharArray?, jsFun: JsFunction ->
                    transformFunctionsMap[jsFun.name]?.apply(sign, jsFun.argument)
                })
                .let { String(it!!) }
    }

    private fun String?.getTransformFunctions(): List<JsFunction> {
        val name = this?.getInitialFunctionName()?.replace("[^A-Za-z0-9_]".toRegex(), "")
        val matcher = "$name=function\\(\\w\\)\\{[a-z=.(\")]*;(.*);(?:.+)}".toPattern().matcher(this)
        return when {
            matcher.find() -> {
                val split = matcher.group(1).split(";".toRegex()).toTypedArray()
                val jsFunctions: MutableList<JsFunction> = ArrayList(split.size)
                for (jsFunction in split) {
                    val funVar = jsFunction.split("\\.".toRegex()).toTypedArray()[0]
                    val (funName, funArgument) = jsFunction.parseFunction()
                    jsFunctions.add(JsFunction(funVar, funName, funArgument))
                }
                jsFunctions
            }
            else -> emptyList()
        }
    }

    private fun String.getInitialFunctionName(): String? {
        return knownInitialFunctionRegexes
                .map { it.find(this)?.groupValues?.get(1) }
                .first { it != null }
    }

    private fun String?.getTransformFunctionsMap(variable: String?): Map<String?, CipherFunction?> {
        return getTransformObject(variable)
                .map { obj ->
                    val split = obj.split(":".toRegex(), 2).toTypedArray()
                    split[0] to split[1].mapFunction()
                }
                .toMap()
    }

    private fun String?.getTransformObject(variable: String?): List<String> {
        val mutVariable = variable?.replace("[^A-Za-z0-9_]".toRegex(), "").orEmpty()
        val pattern = "var ${mutVariable}=\\{(.*?)\\};".toPattern(DOTALL)
        val matcher = pattern.matcher(this)

        return when {
            matcher.find() -> matcher.group(1).replace("\n".toRegex(), " ").split(", ".toRegex()).toList()
            else -> emptyList()
        }
    }

    private fun String.mapFunction(): CipherFunction? {
        for ((regex, value) in functionsEquivalentMap) {
            val matcher = regex.toPattern().matcher(this)
            if (matcher.find()) return value
        }
        return null
    }

    private fun String.parseFunction(): Pair<String, String> {
        return "\\w+\\.(\\w+)\\(\\w,(\\d+)\\)"
                .toRegex()
                .find(this)
                ?.run { groupValues[1] to groupValues[2] }
                ?: "" to ""
    }

    companion object {
        private val VIDEO_AND_AUDIO_TAGS = arrayOf(5, 6, 13, 17, 18, 22, 34, 35, 36, 37, 38, 43, 44, 45, 46, 82, 83, 84, 85, 100, 101, 102, 91, 92, 93, 94, 95, 96, 132, 151)

        private val knownInitialFunctionRegexes: List<Regex> = listOf(
                "\\b[cs]\\s*&&\\s*[adf]\\.set\\([^,]+\\s*,\\s*encodeURIComponent\\s*\\(\\s*([a-zA-Z0-9$]+)\\(".toRegex(),
                "\\b[a-zA-Z0-9]+\\s*&&\\s*[a-zA-Z0-9]+\\.set\\([^,]+\\s*,\\s*encodeURIComponent\\s*\\(\\s*([a-zA-Z0-9$]+)\\(".toRegex(),
                "\\b([a-zA-Z0-9$]{2})\\s*=\\s*function\\(\\s*a\\s*\\)\\s*\\{\\s*a\\s*=\\s*a\\.split\\(\\s*\"\"\\s*\\)".toRegex(),
                "([a-zA-Z0-9$]+)\\s*=\\s*function\\(\\s*a\\s*\\)\\s*\\{\\s*a\\s*=\\s*a\\.split\\(\\s*\"\"\\s*\\)".toRegex(),
                "([\"'])signature\\1\\s*,\\s*([a-zA-Z0-9$]+)\\(".toRegex(),
                "\\.sig\\|\\|([a-zA-Z0-9$]+)\\(".toRegex(),
                "yt\\.akamaized\\.net/\\)\\s*\\|\\|\\s*.*?\\s*[cs]\\s*&&\\s*[adf]\\.set\\([^,]+\\s*,\\s*(?:encodeURIComponent\\s*\\()?\\s*()$".toRegex(),
                "\\b[cs]\\s*&&\\s*[adf]\\.set\\([^,]+\\s*,\\s*([a-zA-Z0-9$]+)\\(".toRegex(),
                "\\b[a-zA-Z0-9]+\\s*&&\\s*[a-zA-Z0-9]+\\.set\\([^,]+\\s*,\\s*([a-zA-Z0-9$]+)\\(".toRegex(),
                "\\bc\\s*&&\\s*a\\.set\\([^,]+\\s*,\\s*\\([^)]*\\)\\s*\\(\\s*([a-zA-Z0-9$]+)\\(".toRegex(),
                "\\bc\\s*&&\\s*[a-zA-Z0-9]+\\.set\\([^,]+\\s*,\\s*\\([^)]*\\)\\s*\\(\\s*([a-zA-Z0-9$]+)\\(".toRegex()
        )
        private val functionsEquivalentMap: MutableMap<Regex, CipherFunction> = mutableMapOf(
                "\\{\\w\\.reverse\\(\\)\\}".toRegex() to ReverseFunction(),
                "\\{\\w\\.splice\\(0,\\w\\)\\}".toRegex() to SpliceFunction(),
                "\\{var\\s\\w=\\w\\[0];\\w\\[0]=\\w\\[\\w%\\w.length];\\w\\[\\w]=\\w\\}".toRegex() to SwapFunctionV1(),
                "\\{var\\s\\w=\\w\\[0];\\w\\[0]=\\w\\[\\w%\\w.length];\\w\\[\\w%\\w.length]=\\w\\}".toRegex() to SwapFunctionV2()
        )
    }
}

private data class JsFunction(
        val variable: String,
        val name: String?,
        val argument: String?
)

private interface CipherFunction {
    fun apply(array: CharArray?, argument: String?): CharArray?
}

private class ReverseFunction : CipherFunction {
    override fun apply(array: CharArray?, argument: String?): CharArray? {
        return array?.reversed()?.toCharArray()
    }
}

private class SpliceFunction : CipherFunction {
    override fun apply(array: CharArray?, argument: String?): CharArray? {
        val deleteCount = argument!!.toInt()
        val spliced = CharArray(array!!.size - deleteCount)
        System.arraycopy(array, 0, spliced, 0, deleteCount)
        System.arraycopy(array, deleteCount * 2, spliced, deleteCount, spliced.size - deleteCount)
        return spliced
    }
}

private class SwapFunctionV1 : CipherFunction {
    override fun apply(array: CharArray?, argument: String?): CharArray? {
        val position = argument!!.toInt()
        val c = array!![0]
        array[0] = array[position % array.size]
        array[position] = c
        return array
    }
}

private class SwapFunctionV2 : CipherFunction {
    override fun apply(array: CharArray?, argument: String?): CharArray? {
        val position = argument!!.toInt()
        val c = array!![0]
        array[0] = array[position % array.size]
        array[position % array.size] = c
        return array
    }
}