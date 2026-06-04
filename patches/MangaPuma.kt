package org.koitharu.kotatsu.parsers.site.madtheme.en

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
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
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlBuilder
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("MANGAPUMA", "MangaK", "en")
internal class MangaPuma(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAPUMA, PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain(
		"mangak.io",
		"mangapuma.com",
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	init {
		setFirstPage(1)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder()
			.scheme("https")
			.host(API_DOMAIN)
			.addPathSegment("titles")
			.addPathSegment("search")
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", PAGE_SIZE.toString())

		when (order) {
			SortOrder.POPULARITY -> {
				url.addQueryParameter("sort", "popular")
				url.addQueryParameter("window", "week")
			}
			SortOrder.UPDATED,
			SortOrder.NEWEST -> {
				url.addQueryParameter("sort", "latest")
			}
			else -> {
				url.addQueryParameter("sort", "latest")
			}
		}

		filter.query?.takeIf { it.isNotBlank() }?.let {
			url.addQueryParameter("q", it)
		}

		filter.states.oneOrThrowIfMany()?.let {
			url.addQueryParameter(
				"status",
				when (it) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					MangaState.PAUSED -> "hiatus"
					MangaState.ABANDONED -> "cancelled"
					else -> return@let
				},
			)
		}

		filter.types.forEach {
			url.addQueryParameter(
				"type",
				when (it) {
					ContentType.MANGA -> "manga"
					ContentType.MANHWA -> "manhwa"
					ContentType.MANHUA -> "manhua"
					else -> return@forEach
				},
			)
		}

		val root = webClient.httpGet(url.build()).parseJson()
		val items = root.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()

		return items.mapJSON { it.toManga() }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val path = manga.url.substringBefore('#')
		val idFromUrl = manga.url.substringAfter('#', "")

		val doc = webClient.httpGet(path.toAbsoluteUrl(siteDomain)).parseHtml()
		val pageProps = doc.nextPageProps()
		val initialManga = pageProps.optJSONObject("initialManga")
			?: throw ParseException("initialManga not found", path)

		val mangaId = initialManga.getStringOrNull("id")
			?: idFromUrl.takeIf { it.isNotBlank() }
			?: throw ParseException("Manga ID not found", path)

		return manga.copy(
			title = initialManga.getStringOrNull("name") ?: manga.title,
			altTitles = emptySet(),
			coverUrl = initialManga.getStringOrNull("cover") ?: manga.coverUrl,
			description = initialManga.getStringOrNull("summary") ?: manga.description,
			authors = initialManga.optJSONArray("authors")?.mapJSONNotNullToSet {
				it.getStringOrNull("name")
			} ?: emptySet(),
			tags = initialManga.optJSONArray("genres")?.mapJSONNotNullToSet {
				it.toMangaTag()
			} ?: emptySet(),
			state = initialManga.getStringOrNull("status").toMangaState() ?: manga.state,
			contentRating = initialManga.getStringOrNull("content_rating").toContentRating()
				?: manga.contentRating
				?: sourceContentRating
				?: ContentRating.SAFE,
			chapters = getChapters(mangaId),
		)
	}

	private suspend fun getChapters(mangaId: String): List<MangaChapter> {
		val root = webClient.httpGet(
			"https://$API_DOMAIN/titles/$mangaId/chapters?cv=${System.currentTimeMillis()}",
		).parseJson()

		val chapters = root.optJSONObject("data")?.optJSONArray("chapters") ?: return emptyList()

		return chapters.asTypedList<JSONObject>()
			.sortedBy { it.getFloatOrDefault("chapter_number", 0f) }
			.mapChapters { index, jo ->
				val path = canonicalPath(jo.getStringOrNull("url") ?: return@mapChapters null)

				MangaChapter(
					id = generateUid(path),
					title = jo.getStringOrNull("name"),
					number = jo.getFloatOrDefault("chapter_number", index + 1f),
					volume = 0,
					url = path,
					uploadDate = dateFormat.parseSafe(jo.getStringOrNull("updated_at")),
					source = source,
					scanlator = null,
					branch = null,
				)
			}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(siteDomain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val pageProps = doc.nextPageProps()
		val initialChapter = pageProps.optJSONObject("initialChapter")
			?: throw ParseException("initialChapter not found", fullUrl)

		val images = initialChapter.optJSONArray("images")
			?: throw ParseException("chapter images not found", fullUrl)

		return images.asTypedList<String>().mapIndexed { index, imageUrl ->
			MangaPage(
				id = generateUid("$imageUrl#$index"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun JSONObject.toManga(): Manga {
		val apiId = getStringOrNull("id")
			?: getStringOrNull("_id")
			?: getStringOrNull("slug")
			.orEmpty()

		val rawPath = getStringOrNull("url")
			?: getStringOrNull("slug")?.let { "/manga/$it" }
			?: "/manga/$apiId"

		val path = canonicalPath(rawPath)

		return Manga(
			id = generateUid(path),
			title = getStringOrNull("name") ?: getStringOrNull("title") ?: "No title",
			altTitles = emptySet(),
			url = "$path#$apiId",
			publicUrl = path.toAbsoluteUrl(siteDomain),
			rating = RATING_UNKNOWN,
			coverUrl = getStringOrNull("cover"),
			tags = emptySet(),
			state = getStringOrNull("status").toMangaState(),
			authors = emptySet(),
			source = source,
			contentRating = getStringOrNull("content_rating").toContentRating()
				?: sourceContentRating
				?: ContentRating.SAFE,
		)
	}

	private fun JSONObject.toMangaTag(): MangaTag? {
		val name = getStringOrNull("name") ?: return null
		val key = getStringOrNull("slug")
			?: name.lowercase(Locale.ROOT).replace(Regex("\\s+"), "-")

		return MangaTag(
			key = key,
			title = name.toTitleCase(),
			source = source,
		)
	}

	private fun Document.nextPageProps(): JSONObject {
		val nextData = selectFirst("script#__NEXT_DATA__")?.data()
			?.takeIf { it.isNotBlank() }
			?: selectFirst("script#__NEXT_DATA__")?.html()
				?.takeIf { it.isNotBlank() }
			?: throw ParseException("__NEXT_DATA__ not found", location())

		val root = JSONObject(nextData)

		return root.optJSONObject("props")?.optJSONObject("pageProps")
			?: root.optJSONObject("pageProps")
			?: throw ParseException("pageProps not found", location())
	}

	private fun canonicalPath(raw: String): String {
		return raw
			.substringBefore('#')
			.removePrefix("https://mangapuma.com")
			.removePrefix("http://mangapuma.com")
			.removePrefix("https://www.mangapuma.com")
			.removePrefix("http://www.mangapuma.com")
			.removePrefix("https://mangak.io")
			.removePrefix("http://mangak.io")
			.removePrefix("https://www.mangak.io")
			.removePrefix("http://www.mangak.io")
			.let { if (it.startsWith("/")) it else "/$it" }
			.removeSuffix("/")
	}

	private fun String?.toMangaState(): MangaState? = when (this?.lowercase(Locale.ROOT)) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		"cancelled", "canceled" -> MangaState.ABANDONED
		else -> null
	}

	private fun String?.toContentRating(): ContentRating? = when (this?.lowercase(Locale.ROOT)) {
		"safe" -> ContentRating.SAFE
		"suggestive" -> ContentRating.SUGGESTIVE
		"erotica", "adult", "mature", "nsfw" -> ContentRating.ADULT
		else -> null
	}

	private val siteDomain: String
		get() = if (domain.contains("mangapuma", ignoreCase = true)) {
			"mangak.io"
		} else {
			domain
		}

	private companion object {
		const val PAGE_SIZE = 24
		const val API_DOMAIN = "api.mangak.io"

		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
	}
}
