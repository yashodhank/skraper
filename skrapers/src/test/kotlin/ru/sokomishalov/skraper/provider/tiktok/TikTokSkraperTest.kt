package ru.sokomishalov.skraper.provider.tiktok

import ru.sokomishalov.skraper.Skraper
import ru.sokomishalov.skraper.provider.SkraperTck

class TikTokSkraperTest : SkraperTck() {
    override val skraper: Skraper = TikTokSkraper(client = client)
    override val path: String = "/@meme"
}