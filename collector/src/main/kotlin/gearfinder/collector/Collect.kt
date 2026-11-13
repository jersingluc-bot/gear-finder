package gearfinder.collector

import gearfinder.catalogue.CatalogueWriter
import gearfinder.catalogue.Meta
import gearfinder.catalogue.RetailerHealth
import gearfinder.merge.merge
import gearfinder.model.Listing
import gearfinder.net.Http
import gearfinder.retail.CrawlOptions
import gearfinder.retail.RetailerAdapter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import java.io.File

sealed interface Verdict { data object Publish : Verdict; data class Refuse(val why: String) : Verdict }

/** Refuses a catalogue that lost more than 30% of its products since the last one: that means a crawl went wrong, not that shops shrank. */
fun shouldPublish(previous: Meta?, next: Meta): Verdict = when {
    next.products == 0 -> Verdict.Refuse("the new catalogue is empty")
    previous != null && next.products < previous.products * 0.7 -> Verdict.Refuse("products fell from ${previous.products} to ${next.products}")
    else -> Verdict.Publish
}

class RunReport(val meta: Meta?, val verdict: Verdict, val lines: List<String>)

/**
 * Crawls every shop in [adapters] (each independently: one failing or refusing does not stop the others),
 * merges the results and writes the catalogue into [out]. Shop caches live in [cacheDir].
 */
suspend fun collect(
    adapters: List<RetailerAdapter>, out: File, cacheDir: File, options: CrawlOptions,
    now: Long = System.currentTimeMillis(), http: () -> Http = { Http() }, log: (String) -> Unit = ::println,
): RunReport {
    val json = Json { ignoreUnknownKeys = true }
    val previous = File(out, "meta.json").takeIf { it.exists() }?.let { runCatching { json.decodeFromString(Meta.serializer(), it.readText()) }.getOrNull() }
    val lines = mutableListOf<String>()
    val results = coroutineScope {
        adapters.map { a ->
            async {
                val cache = FileCrawlCache(File(cacheDir, "${a.id}.jsonl.gz"))
                val found = ArrayList<Listing>()
                val started = System.currentTimeMillis()
                val result = runCatching { a.crawl(http(), cache, options) { found += it } }
                val ok = result.getOrNull()?.let { !it.blocked && it.fetched > 0 } ?: false
                // A crawl that threw or was refused part-way still has the cache.
                val listings = if (result.isSuccess) found else cache.all()
                runCatching { cache.save() }
                val line = "${a.name}: ${listings.size} products, ${result.getOrNull()?.fetched ?: 0} pages read, " +
                    (result.exceptionOrNull()?.let { "FAILED (${it.message})" } ?: if (result.getOrNull()!!.blocked) "REFUSED by the shop, using saved data" else "ok") +
                    " in ${(System.currentTimeMillis() - started) / 1000}s"
                log(line); synchronized(lines) { lines += line }
                val last = if (ok) now else previous?.retailers?.firstOrNull { it.id == a.id }?.lastSuccess ?: 0
                listings to RetailerHealth(a.id, a.name, listings.size, last)
            }
        }.awaitAll()
    }
    // Shops in the previous catalogue that were not part of this run keep their health line.
    val health = results.map { it.second } + (previous?.retailers?.filter { p -> adapters.none { it.id == p.id } } ?: emptyList())
    val products = merge(results.flatMap { it.first })
    val next = Meta(now, products.size, health)
    val verdict = shouldPublish(previous, next)
    if (verdict is Verdict.Publish) CatalogueWriter.write(out, products, health, now)
    log("catalogue: ${products.size} products from ${results.sumOf { it.first.size }} listings - " + if (verdict is Verdict.Refuse) "NOT published: ${verdict.why}" else "written to $out")
    return RunReport(next.takeIf { verdict is Verdict.Publish }, verdict, lines)
}
