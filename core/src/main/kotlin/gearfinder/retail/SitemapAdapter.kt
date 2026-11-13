package gearfinder.retail

import gearfinder.model.Listing
import gearfinder.net.Fetch
import gearfinder.net.Http
import gearfinder.net.Robots
import gearfinder.net.sitemapUrls
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

typealias PageParser = (html: String, url: String, retailerId: String) -> Listing?

/**
 * A shop read by walking its sitemap and opening every product page.
 * With [sliceOf] = n, a page already seen is re-read only every n-th day, so a very large shop is spread over n runs.
 */
class SitemapAdapter(
    override val id: String,
    override val name: String,
    override val home: String,
    private val sitemaps: List<String>,
    private val childFilter: Regex? = null,
    private val productUrl: Regex? = null,
    private val sliceOf: Int = 1,
    private val parse: PageParser = ::parseJsonLdProduct,
) : RetailerAdapter {
    private val host: String get() = URI(home).host

    override suspend fun refresh(http: Http, url: String): Listing? =
        (http.get(url) as? Fetch.Ok)?.let { runCatching { parse(it.body, url, id) }.getOrNull() }

    override suspend fun crawl(http: Http, cache: CrawlCache, options: CrawlOptions, emit: suspend (Listing) -> Unit): CrawlResult {
        val robots = Robots((http.get("$home/robots.txt") as? Fetch.Ok)?.body ?: "")
        val urls = sitemaps.flatMap { sitemapUrls(http, it, childFilter, productUrl) }.distinct()
            .filter { robots.allows(runCatching { URI(it).rawPath }.getOrNull() ?: "/") }.take(options.limit)
        if (urls.isEmpty()) {
            // The shop could not be listed at all: keep everything learned before.
            cache.all().forEach { emit(it) }
            return CrawlResult(0, 0, http.isBlocked(host))
        }
        val fetched = AtomicInteger()
        for ((n, batch) in urls.chunked(64).withIndex()) {
            if (n > 0 && n % 20 == 0) runCatching { cache.checkpoint() }
            val found = coroutineScope {
                batch.map { u ->
                    async {
                        val cached = cache.get(u)
                        val due = cached == null || Math.floorMod(u.hashCode() + options.day, sliceOf.toLong()) == 0L
                        if (!due || http.isBlocked(host) || fetched.get() >= options.budget) return@async cached?.listing
                        fetched.incrementAndGet()
                        when (val r = http.get(u)) {
                            is Fetch.Ok -> runCatching { parse(r.body, u, id) }.getOrNull().also { cache.put(u, it) }
                            is Fetch.Gone -> null.also { cache.put(u, null) }
                            else -> cached?.listing
                        }
                    }
                }.awaitAll()
            }
            found.filterNotNull().forEach { emit(it) }
        }
        return CrawlResult(urls.size, fetched.get(), http.isBlocked(host))
    }
}
