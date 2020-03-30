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

package ru.sokomishalov.skraper.cli

import com.andreapivetta.kolor.cyan
import com.andreapivetta.kolor.green
import com.andreapivetta.kolor.magenta
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.mainBody
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import ru.sokomishalov.skraper.Skraper
import ru.sokomishalov.skraper.cli.model.Args
import ru.sokomishalov.skraper.cli.model.OutputType.*
import ru.sokomishalov.skraper.cli.model.Provider
import ru.sokomishalov.skraper.model.*
import ru.sokomishalov.skraper.provider.youtube.YoutubeSkraper
import java.io.File
import java.io.File.separator
import java.net.URL
import java.nio.channels.Channels
import java.time.LocalDateTime.now
import java.time.format.DateTimeFormatter.ofPattern
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import kotlin.text.Charsets.UTF_8


fun main(args: Array<String>) = mainBody(columns = 100) {
    val parsedArgs = ArgParser(args = args.ifEmpty { arrayOf("--help") }).parseInto(::Args)

    println("${"Skraper".green()} ${"v.0.4.0".magenta()} started")

    val posts = runBlocking {
        parsedArgs.skraper.getPosts(
                path = "/${parsedArgs.path.removePrefix("/")}",
                limit = parsedArgs.amount
        )
    }

    when {
        parsedArgs.onlyMedia -> posts.persistMedia(parsedArgs)
        else -> posts.persistMeta(parsedArgs)
    }
}

private fun List<Post>.persistMedia(parsedArgs: Args) {
    val provider = parsedArgs.skraper.javaClass.simpleName.toString().toLowerCase().replace("skraper", "")
    val requestedPath = parsedArgs.path
    val root = when {
        parsedArgs.output.isFile -> parsedArgs.output.parentFile.absolutePath
        else -> parsedArgs.output.absolutePath
    }
    val targetDir = File("${root}/${provider}/${requestedPath}").apply { mkdirs() }

    runBlocking(context = Executors.newFixedThreadPool(parsedArgs.parallelDownloads).asCoroutineDispatcher()) {
        flatMap { post ->
            post.media.map { media ->
                async {
                    parsedArgs.skraper.download(
                            post = post,
                            media = media,
                            targetDir = targetDir
                    )
                }
            }
        }.awaitAll()
    }

    exitProcess(1)
}

private fun List<Post>.persistMeta(parsedArgs: Args) {
    val provider = parsedArgs.skraper.javaClass.simpleName.toString().replace("Skraper", "").toLowerCase()
    val requestedPath = parsedArgs.path

    val content = when (parsedArgs.outputType) {
        LOG -> joinToString("\n") { it.toString() }
                .also { println(it) }
        JSON -> JsonMapper()
                .registerModule(JavaTimeModule())
                .registerModule(Jdk8Module())
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(this)
        XML -> XmlMapper()
                .registerModule(JavaTimeModule())
                .registerModule(Jdk8Module())
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(this)
        YAML -> YAMLMapper()
                .registerModule(JavaTimeModule())
                .registerModule(Jdk8Module())
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(this)
        CSV -> {
            CsvMapper()
                    .registerKotlinModule()
                    .registerModule(JavaTimeModule())
                    .registerModule(Jdk8Module())
                    .registerModule(SimpleModule().apply {
                        addSerializer(Post::class.java, object : JsonSerializer<Post>() {
                            override fun serialize(item: Post, jgen: JsonGenerator, serializerProvider: SerializerProvider) {
                                jgen.writeStartObject()
                                jgen.writeStringField("ID", item.id)
                                jgen.writeStringField("Text", item.text)
                                jgen.writeStringField("Published at", item.publishedAt?.toString(10))
                                jgen.writeStringField("Rating", item.rating?.toString(10).orEmpty())
                                jgen.writeStringField("Comments count", item.commentsCount?.toString(10).orEmpty())
                                jgen.writeStringField("Views count", item.viewsCount?.toString(10).orEmpty())
                                jgen.writeStringField("Media", item.media.joinToString("   ") { it.url })
                                jgen.writeEndObject()
                            }
                        })
                    })
                    .writer(CsvSchema
                            .builder()
                            .addColumn("ID")
                            .addColumn("Text")
                            .addColumn("Published at")
                            .addColumn("Rating")
                            .addColumn("Comments count")
                            .addColumn("Views count")
                            .addColumn("Media")
                            .build()
                            .withHeader()
                    )
                    .writeValueAsString(this)

        }
    }

    val fileToWrite = when {
        parsedArgs.output.isFile -> parsedArgs.output
        else -> {
            val root = parsedArgs.output.absolutePath
            val now = now().format(ofPattern("ddMMyyyy'_'hhmmss"))
            val ext = parsedArgs.outputType.extension

            File("${root}/${provider}/${requestedPath}_${now}.${ext}")
        }
    }

    fileToWrite
            .apply { parentFile.mkdirs() }
            .writeText(text = content, charset = UTF_8)

    println(fileToWrite.path.cyan())
}

@Suppress("BlockingMethodInNonBlockingContext")
private suspend fun Skraper.download(post: Post, media: Media, targetDir: File) {

    val (directMediaUrl, filename) = lookForDirectMediaLinkRecursively(media, post)
    val targetFile = File("${targetDir.absolutePath}$separator${filename}")

    runCatching {
        when {
            // m3u8 download with ffmpeg
            filename.endsWith("m3u8") -> {
                val target = targetFile.absolutePath.replace("m3u8", "mp4")
                val cmd = "ffmpeg -i $directMediaUrl -c copy -bsf:a aac_adtstoasc $target"
                Runtime.getRuntime().exec(cmd).waitFor()
                target
            }
            // otherwise try to download as is
            else -> {
                Channels.newChannel(URL(directMediaUrl).openStream()).use { rbc ->
                    targetFile.outputStream().use { fos ->
                        fos.channel.transferFrom(rbc, 0, Long.MAX_VALUE)
                        targetFile.absolutePath
                    }
                }
            }
        }
    }.onSuccess { path ->
        println(path)
    }.onFailure {
        println("Cannot download $directMediaUrl")
    }
}

@Suppress("BlockingMethodInNonBlockingContext")
private suspend fun Skraper.lookForDirectMediaLinkRecursively(
        media: Media,
        post: Post,
        lookupDepth: Int = 2
): Pair<URLString, String> {
    val mediaUrl = URL(media.url)

    return when {
        // has some possible extension
        mediaUrl
                .path
                .substringAfterLast("/")
                .substringAfterLast(".", "")
                .isNotEmpty() -> media.url to media.extractFileName()

        // youtube video
        mediaUrl.host in YoutubeSkraper.HOSTS -> {
            val resolved = Provider.YOUTUBE.skraper.resolve(media)
            val name = post.text ?: post.id
            val filename = "${name.abbreviate()}.mp4"

            resolved.url to filename
        }

        // otherwise
        else -> {
            resolve(media).run {
                when {
                    lookupDepth > 0 -> url to extractFileName()
                    else -> lookForDirectMediaLinkRecursively(
                            media = this,
                            post = post,
                            lookupDepth = lookupDepth - 1
                    )
                }
            }
        }
    }
}

private fun Media.extractFileName(): String {
    val filename = URL(url).path

    val filenameWithoutExtension = filename
            .substringAfterLast("/")
            .substringBeforeLast(".")

    val extension = when (this) {
        is Image -> filename.substringAfterLast(".", "png")
        is Video -> filename.substringAfterLast(".", "mp4")
        is Audio -> filename.substringAfterLast(".", "mp3")
    }

    return "${filenameWithoutExtension}.${extension}"
}

private fun String.abbreviate(maxLength: Int = 100): String {
    return when {
        length > 100 -> "${substring((0..maxLength - 3))}..."
        else -> this
    }
}