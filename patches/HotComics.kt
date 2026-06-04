package org.koitharu.kotatsu.parsers.site.hotcomics.en

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.hotcomics.HotComicsParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat

@MangaSourceParser("HOTCOMICS", "HotComics", "en")
internal class HotComics(context: MangaLoaderContext) :
	HotComicsParser(context, MangaParserSource.HOTCOMICS, "hotcomics.me/en") {

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		val redirectHeaders = Headers.Builder()
			.set("Referer", "https://hotcomics.me/")
			.set("Cookie", "hc_vfs=Y")
			.build()
		val doc = webClient.httpGet(mangaUrl, redirectHeaders).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)

		val chapters = doc.select("#tab-chapter a").asReversed().mapChapters { i, element ->
			val url = element.attr("onclick").substringAfter("popupLogin('").substringBefore("'")
			val name = element.selectFirst(".cell-num")?.text() ?: "Unknown"
			val dateUpload = dateFormat.parseSafe(element.selectFirst(".cell-time")?.text())
			val chapterNum = element.selectFirst(".num")?.text()?.toFloatOrNull() ?: (i + 1f)
			MangaChapter(
				id = generateUid(url),
				title = name,
				number = chapterNum,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = dateUpload,
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = doc.selectFirst("h2.episode-title")?.text() ?: manga.title,
			description = buildString {
				doc.selectFirst("div.episode-contents header")?.text()?.let {
					append(it)
					append("\n\n")
				}
				doc.selectFirst("div.title_content > h2:not(.episode-title)")?.text()?.let {
					append(it)
				}
			}.trim().ifEmpty { manga.description },
			chapters = chapters,
		)
	}
}
