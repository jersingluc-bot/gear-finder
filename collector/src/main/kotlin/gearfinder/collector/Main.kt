package gearfinder.collector

import gearfinder.retail.CrawlOptions
import gearfinder.retail.RETAILERS
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Usage: collector [--out DIR] [--cache DIR] [--only id,id] [--limit N] [--budget N] [--merge-only]
 *   --limit   read at most N product addresses per shop (trial runs)
 *   --budget  fetch at most N pages per shop this run; the rest come from the cache
 *   --merge-only  rebuild the catalogue from the saved pages without contacting any shop
 */
fun main(args: Array<String>) {
    fun arg(name: String): String? = args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    arg("--audit")?.let { audit(File(arg("--cache") ?: "cache"), File(it)); return }
    val only = arg("--only")?.split(",")?.toSet()
    val unknown = only?.filter { id -> RETAILERS.none { it.id == id } }.orEmpty()
    if (unknown.isNotEmpty()) { System.err.println("Unknown shop id(s): $unknown. Known: ${RETAILERS.map { it.id }}"); exitProcess(2) }
    // Shops left out by --only still appear in the catalogue, from the pages saved earlier.
    val adapters = RETAILERS.map { if ("--merge-only" in args || (only != null && it.id !in only)) SavedOnly(it) else it }
    val options = CrawlOptions(
        day = System.currentTimeMillis() / 86_400_000,
        limit = arg("--limit")?.toIntOrNull() ?: Int.MAX_VALUE,
        budget = arg("--budget")?.toIntOrNull() ?: Int.MAX_VALUE,
    )
    val report = runBlocking { collect(adapters, File(arg("--out") ?: "catalogue"), File(arg("--cache") ?: "cache"), options) }
    exitProcess(if (report.verdict is Verdict.Publish) 0 else 1)
}

/** Stands in for a shop during --merge-only: offers what earlier runs saved and contacts nobody. */
private class SavedOnly(private val shop: gearfinder.retail.RetailerAdapter) : gearfinder.retail.RetailerAdapter by shop {
    override suspend fun crawl(
        http: gearfinder.net.Http, cache: gearfinder.retail.CrawlCache, options: CrawlOptions, emit: suspend (gearfinder.model.Listing) -> Unit,
    ): gearfinder.retail.CrawlResult {
        val all = cache.all(); all.forEach { emit(it) }
        // Nothing was fetched, so the shop's "last read" date stays as it was.
        return gearfinder.retail.CrawlResult(all.size, 0, false)
    }
}

/** Developer aid (--audit FILE): writes "group / category | brand | title | shop's own category" for every saved listing. */
internal fun audit(cacheDir: File, to: File) {
    to.bufferedWriter().use { w ->
        for (shop in RETAILERS) for (l in FileCrawlCache(File(cacheDir, "${shop.id}.jsonl.gz")).all()) {
            val p = gearfinder.merge.classify(l.title, l.categoryHint, l.brand)
            w.write("${p?.let { "${it.group} / ${it.category}" } ?: "DROPPED"} | ${shop.id} | ${l.brand} | ${l.title} | ${l.categoryHint ?: ""}\n")
        }
    }
}
