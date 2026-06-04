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
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.requireSrc
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
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
		.add("User-Agent", UserAgents.CHROME_DESKTOP)
		.add("Referer", "https://${domain.substringBefore('/')}/")
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
					append("/search?keyword=")
					append(filter.query.urlEncoded())
					append("&page=")
					append(page)
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

	protected open val selectMangas = "li[itemtype*=ComicSeries]:not(.no-comic)"

	protected open fun parseMangaList(doc: Document, tagMap: ArrayMap<String, MangaTag>): List<Manga> {
		return doc.select(selectMangas).mapNotNull { li ->
			val a = li.selectFirst("a") ?: return@mapNotNull null
			val href = a.attr("href")

			val url = if (href.startsWith("/")) {
				"/" + href.removePrefix("/").substringAfter('/')
			} else {
				href
			}

			val tags = li.select(".etc span").mapNotNullToSet { tagMap[it.text()] }
			val isNsfwSource = a.selectFirst(".ico-18plus") != null
			val author = li.selectFirst(".writer")?.text().orEmpty()

			Manga(
				id = generateUid(url),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				coverUrl = li.selectFirst("img")?.imageSrc(),
				title = li.selectFirst(".title")?.text().orEmpty(),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				description = li.selectFirst("p[itemprop*=description]")?.text().orEmpty(),
				tags = tags,
				authors = setOf(author).filterTo(HashSet()) { it.isNotBlank() },
				state = if (li.selectFirst(".ico_fin") != null) {
					MangaState.FINISHED
				} else {
					MangaState.ONGOING
				},
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	protected open val selectMangaChapters = "#tab-chapter li"
	protected open val datePattern = "MMM dd, yyyy"

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		val redirectHeaders = Headers.Builder()
			.set("Referer", "https://${domain.substringBefore('/')}/")
			.set("Cookie", "hc_vfs=Y")
			.build()
		val doc = webClient.httpGet(mangaUrl, redirectHeaders).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return manga.copy(
			description = doc.selectFirst("div.title_content_box h2")?.text() ?: manga.description,
			chapters = doc.select(selectMangaChapters)
				.mapChapters(reversed = true) { i, li ->
					val a = li.selectFirst("a") ?: return@mapChapters null
					val href = a.attr("href")
					val url = if (href.startsWith("/")) {
						"/" + href.removePrefix("/").substringAfter('/')
					} else if (href.startsWith("javascript")) {
						val h = a.attr("onclick").substringAfterLast("href='").substringBefore("'")
						"/" + h.removePrefix("/").substringAfter('/')
					} else {
						href
					}
					val chapterNum = li.selectFirst(".num")?.text()?.toFloatOrNull() ?: (i + 1f)
					MangaChapter(
						id = generateUid(url),
						title = null,
						number = chapterNum,
						volume = 0,
						url = url,
						scanlator = null,
						uploadDate = dateFormat.parseSafe(li.selectFirst("time")?.attr("datetime")),
						branch = null,
						source = source,
					)
				},
		)
	}

	protected open val selectPages = "#viewer-img img"

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.select(selectPages).mapIndexed { index, img ->
			val url = img.imageSrc() ?: img.requireSrc()
			MangaPage(
				id = generateUid("$url#$index"),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun Element.imageSrc(): String? {
		val dataSrc = attr("data-src").takeIf { it.isNotBlank() }
		if (dataSrc != null) {
			return dataSrc.toAbsoluteUrl(domain.substringBefore('/'))
		}
		return runCatching { src() }.getOrNull()
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
			val key = item.attr("href").substringAfterLast('/')
			if (key.isNotEmpty() && title.isNotEmpty()) {
				result[title] = MangaTag(title = title, key = key, source = source)
			}
		}
		tagCache = result
		result
	}
}
