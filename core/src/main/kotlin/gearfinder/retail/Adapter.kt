package gearfinder.retail

import gearfinder.model.Listing
import gearfinder.net.Http

data class CrawlOptions(
    /** Days since 1970; decides which slice of a large shop is refreshed today. */
    val day: Long,
    /** Stop after this many product addresses (for trial runs). */
    val limit: Int = Int.MAX_VALUE,
    /** Fetch at most this many pages in one run; the rest come from the cache. */
    val budget: Int = Int.MAX_VALUE,
)

data class CrawlResult(val listed: Int, val fetched: Int, val blocked: Boolean)

class Cached(val listing: Listing?)

/** What earlier runs learned, so a run that is cut short or refused still has every product. */
interface CrawlCache {
    fun get(url: String): Cached?
    fun put(url: String, listing: Listing?)
    fun all(): List<Listing>
    /** Called now and then during a long crawl so progress survives an interruption. */
    fun checkpoint() {}
}

interface RetailerAdapter {
    val id: String
    val name: String
    val home: String
    /** Reads the shop's whole range, calling [emit] once per product page. */
    suspend fun crawl(http: Http, cache: CrawlCache, options: CrawlOptions, emit: suspend (Listing) -> Unit): CrawlResult
    /** Reads one product page now, for the live price check. Null when it cannot be read. */
    suspend fun refresh(http: Http, url: String): Listing?
}
