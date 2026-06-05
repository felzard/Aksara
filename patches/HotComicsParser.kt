package org.koitharu.kotatsu.parsers.site.hotcomics

import androidx.collection.ArrayMap
import androidx.collection.ArraySet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.net.URI
import java.text.SimpleDateFormat
import java.util.EnumSet

internal abstract class HotComicsParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 24,
) : PagedMangaParser(context, source, pageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.add("Referer", "https://$domain/")
		.add("Cookie", "hc_vfs=Y")
		.build()

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

	protected open val isSearchSupported: Boolean = true

	final override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = isSearchSupported,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	protected open val homeUrl = "/"
	protected open val latestUrl = "/new"
	protected open val searchUrl = "/search"
	protected open val mangasUrl = "/genres"
	protected open val onePage = false

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (onePage && page > 1) {
			return emptyList()
		}

		val url = buildString {
			append("https://")
			append(domain)

			when {
				!filter.query.isNullOrEmpty() -> {
					append(searchUrl)
					append("?keyword=")
					append(filter.query.urlEncoded())
					append("&page=")
					append(page)
				}

				filter.tags.isEmpty() && order == SortOrder.NEWEST -> {
					append(latestUrl)
					if (!onePage) {
						append("?page=")
						append(page)
					}
				}

				filter.tags.isEmpty() -> {
					append(homeUrl)
					if (!onePage) {
						append("?page=")
						append(page)
					}
				}

				else -> {
					append(mangasUrl)
					filter.tags.oneOrThrowIfMany()?.let {
						append('/')
						append(it.key)
					}

					if (!onePage) {
						append("?page=")
						append(page)
					}
				}
			}
		}

		val tagMap = getOrCreateTagMap()
		return parseMangaList(webClient.httpGet(url).parseHtml(), tagMap)
	}

	protected open val selectMangas = "li[itemtype*=ComicSeries]:not(.no-comic) > a"

	protected open fun parseMangaList(doc: Document, tagMap: ArrayMap<String, MangaTag>): List<Manga> {
		return doc.select(selectMangas).mapNotNull { a ->
			val rawUrl = a.absUrl("href").ifEmpty { a.attr("href") }
			val url = normalizeSourcePath(rawUrl)
			if (url.isEmpty()) {
				return@mapNotNull null
			}

			val tags = a.select(".etc span").mapNotNullToSet { tagMap[it.text()] }
			val isAdult = a.selectFirst(".ico-18plus") != null
			val author = a.selectFirst(".writer")?.text().orEmpty()

			Manga(
				id = generateUid(url),
				url = url,
				publicUrl = sitePath(url).toAbsoluteUrl(domain),
				coverUrl = a.selectFirst("img")?.imgAttr(),
				title = a.selectFirst(".title")?.text()
					?: a.selectFirst("div.main-text > h4.title")?.text()
					?: a.text(),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				description = a.selectFirst("p[itemprop*=description]")?.text().orEmpty(),
				tags = tags,
				authors = setOfNotNull(author.takeIf { it.isNotBlank() }),
				state = if (a.selectFirst(".ico_fin") != null) {
					MangaState.FINISHED
				} else {
					MangaState.ONGOING
				},
				source = source,
				contentRating = if (isAdult || isNsfwSource) ContentRating.ADULT else null,
			)
		}.distinctBy { it.id }
	}

	protected open val selectMangaChapters = "#tab-chapter a"
	protected open val datePattern = "MMM dd, yyyy"

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = sitePath(manga.url).toAbsoluteUrl(domain)
		val redirectHeaders = Headers.Builder()
			.set("User-Agent", config[userAgentKey])
			.set("Referer", mangaUrl)
			.set("Cookie", "hc_vfs=Y")
			.build()

		val doc = webClient.httpGet(mangaUrl, redirectHeaders).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)

		val title = doc.selectFirst("h2.episode-title")?.text() ?: manga.title
		val info = doc.selectFirst("p.type_box")

		val author = info?.selectFirst("span.writer")?.text()
			?.substringAfter("ⓒ")
			?.trim()
			?.takeIf { it.isNotBlank() }

		val state = when (info?.selectFirst("span.date")?.text()) {
			"End", "Ende" -> MangaState.FINISHED
			null -> manga.state
			else -> MangaState.ONGOING
		}

		val description = buildString {
			doc.selectFirst("div.episode-contents header")
				?.text()
				?.takeIf { it.isNotBlank() }
				?.let {
					append(it)
					append("\n\n")
				}

			doc.selectFirst("div.title_content > h2:not(.episode-title)")
				?.text()
				?.takeIf { it.isNotBlank() }
				?.let { append(it) }
		}.trim().takeIf { it.isNotBlank() } ?: manga.description

		return manga.copy(
			title = title,
			description = description,
			authors = setOfNotNull(author).ifEmpty { manga.authors },
			state = state,
			chapters = doc.select(selectMangaChapters)
				.mapChapters { i, a ->
					val rawUrl = a.extractChapterUrl()
					val url = normalizeSourcePath(rawUrl)
					if (url.isEmpty()) {
						return@mapChapters null
					}

					val name = a.selectFirst(".cell-num")?.text()
					val chapterNum = a.extractChapterNumber(i)

					MangaChapter(
						id = generateUid(url),
						title = name,
						number = chapterNum,
						volume = 0,
						url = url,
						scanlator = null,
						uploadDate = dateFormat.parseSafe(a.selectFirst(".cell-time")?.text()),
						branch = null,
						source = source,
					)
				}
				.sortedBy { it.number },
		)
	}

	protected open val selectPages = "#viewer-img img"

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = sitePath(chapter.url).toAbsoluteUrl(domain)
		val headers = Headers.Builder()
			.set("User-Agent", config[userAgentKey])
			.set("Referer", fullUrl)
			.set("Cookie", "hc_vfs=Y")
			.build()

		val doc = webClient.httpGet(fullUrl, headers).parseHtml()
		return doc.select(selectPages).mapIndexedNotNull { _, img ->
			val url = img.imgAttr()
			if (url.isBlank()) {
				return@mapIndexedNotNull null
			}

			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val map = getOrCreateTagMap()
		val tagSet = ArraySet<MangaTag>(map.size)
		for (entry in map) {
			tagSet.add(entry.value)
		}
		return tagSet
	}

	protected open val mutex = Mutex()
	protected open var tagCache: ArrayMap<String, MangaTag>? = null

	protected open val selectTagsList = ".genres-list li:not(.on) a"

	protected open suspend fun getOrCreateTagMap(): ArrayMap<String, MangaTag> = mutex.withLock {
		tagCache?.let { return@withLock it }

		val doc = webClient.httpGet("https://$domain$mangasUrl").parseHtml()
		val tagItems = doc.select(selectTagsList)
		val result = ArrayMap<String, MangaTag>(tagItems.size)
		for (item in tagItems) {
			val title = item.text()
			val key = normalizeSourcePath(item.attr("href")).substringAfterLast('/')
			if (key.isNotEmpty() && title.isNotEmpty()) {
				result[title] = MangaTag(title = title, key = key, source = source)
			}
		}
		tagCache = result
		result
	}

	private fun sitePath(path: String): String {
		val normalized = normalizeSourcePath(path)
		return if (normalized.isEmpty()) {
			"/en"
		} else if (normalized.startsWith("/en/") || normalized == "/en") {
			normalized
		} else {
			"/en$normalized"
		}
	}

	private fun normalizeSourcePath(raw: String): String {
		var value = raw
			.trim()
			.substringBefore('#')
			.substringBefore('?')
			.trim()

		if (value.isBlank() || value == "#") {
			return ""
		}

		// Repair previously broken/stored paths like:
		// /https:/w1.hotcomics.me/en/suicide-boy/ongDK9jF.html
		if (value.startsWith("/https:/") || value.startsWith("/http:/")) {
			value = value.removePrefix("/")
		}
		if (value.startsWith("https:/") && !value.startsWith("https://")) {
			value = "https://" + value.removePrefix("https:/").removePrefix("/")
		}
		if (value.startsWith("http:/") && !value.startsWith("http://")) {
			value = "http://" + value.removePrefix("http:/").removePrefix("/")
		}

		// Strip any HotComics host, including w1.hotcomics.me, w2.hotcomics.me, etc.
		if (value.startsWith("http://") || value.startsWith("https://")) {
			val uri = runCatching { URI(value) }.getOrNull()
			val host = uri?.host.orEmpty().lowercase()

			if (host == "hotcomics.me" || host.endsWith(".hotcomics.me")) {
				value = uri?.rawPath.orEmpty()
			}
		}

		if (value.isBlank()) {
			return ""
		}

		value = if (value.startsWith("/")) value else "/$value"

		// Store internally without /en prefix so chapter IDs stay stable.
		if (value.startsWith("/en/")) {
			value = "/" + value.removePrefix("/en/")
		}

		return value.removeSuffix("/")
	}

	private fun Element.extractChapterNumber(fallbackIndex: Int): Float {
		val text = listOfNotNull(
			selectFirst(".num")?.text(),
			selectFirst(".cell-num")?.text(),
			ownText(),
		).joinToString(" ")

		return Regex("""\d+(?:\.\d+)?""")
			.find(text)
			?.value
			?.toFloatOrNull()
			?: (fallbackIndex + 1f)
	}

	private fun Element.extractChapterUrl(): String {
		val onclickUrl = attr("onclick")
			.substringAfter("popupLogin('", "")
			.substringBefore("'", "")
			.trim()
			.takeIf { it.isNotBlank() && it != "#" && !it.startsWith("javascript:", ignoreCase = true) }

		return onclickUrl
			?: absUrl("href").ifEmpty { attr("href") }
	}

	private fun Element.imgAttr(): String = when {
		hasAttr("data-src") -> absUrl("data-src")
		else -> absUrl("src")
	}
}
