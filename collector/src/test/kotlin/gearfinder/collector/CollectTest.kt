package gearfinder.collector

import gearfinder.catalogue.Catalogue
import gearfinder.catalogue.Meta
import gearfinder.model.Listing
import gearfinder.model.Variant
import gearfinder.net.Http
import gearfinder.retail.CrawlCache
import gearfinder.retail.CrawlOptions
import gearfinder.retail.CrawlResult
import gearfinder.retail.RetailerAdapter
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun item(shop: String, title: String) =
    Listing(shop, title, "Rab", null, null, "https://$shop/$title", null, listOf(Variant(null, "", null, 1000, null, "u", emptyList(), true)))

/** A pretend shop: lists [titles]; when [blocked] it is refused and can only offer what the cache holds. */
private class FakeShop(override val id: String, var titles: List<String>, var blocked: Boolean = false, var crash: Boolean = false) : RetailerAdapter {
    override val name = id; override val home = "https://$id"
    override suspend fun crawl(http: Http, cache: CrawlCache, options: CrawlOptions, emit: suspend (Listing) -> Unit): CrawlResult {
        if (crash) error("boom")
        if (blocked) { cache.all().forEach { emit(it) }; return CrawlResult(0, 0, true) }
        titles.forEach { val l = item(id, it); cache.put(l.url, l); emit(l) }
        return CrawlResult(titles.size, titles.size, false)
    }
    override suspend fun refresh(http: Http, url: String): Listing? = null
}

class CollectTest {
    private fun meta(n: Int) = Meta(1, n, emptyList())

    @Test fun guard() {
        assertIs<Verdict.Refuse>(shouldPublish(meta(100_000), meta(60_000)))
        assertIs<Verdict.Publish>(shouldPublish(meta(100_000), meta(80_000)))
        assertIs<Verdict.Publish>(shouldPublish(null, meta(10)))
        assertIs<Verdict.Refuse>(shouldPublish(null, meta(0)))
    }

    @Test fun aRefusedOrCrashingShopKeepsItsOldDataAndTheRunStillPublishes() = runBlocking {
        val out = Files.createTempDirectory("out").toFile(); val cache = Files.createTempDirectory("cache").toFile()
        val a = FakeShop("a", listOf("Latok Jacket", "Kinetic Trousers")); val b = FakeShop("b", listOf("Copper Spur Tent")); val c = FakeShop("c", listOf("Neutrino Sleeping Bag"))
        val quiet: (String) -> Unit = {}
        collect(listOf(a, b, c), out, cache, CrawlOptions(1), now = 1000, log = quiet)
        assertEquals(4, Catalogue.open(out).meta.products)

        b.blocked = true; c.crash = true; a.titles = listOf("Latok Jacket", "Kinetic Trousers", "Phantom Jacket")
        val report = collect(listOf(a, b, c), out, cache, CrawlOptions(2), now = 2000, log = quiet)
        assertIs<Verdict.Publish>(report.verdict)
        val cat = Catalogue.open(out)
        assertEquals(5, cat.meta.products, "the refused and the crashed shop keep their products")
        val health = cat.meta.retailers.associateBy { it.id }
        assertEquals(2000, health["a"]!!.lastSuccess); assertEquals(1000, health["b"]!!.lastSuccess); assertEquals(1000, health["c"]!!.lastSuccess)
        assertTrue(report.lines.any { "REFUSED" in it } && report.lines.any { "FAILED" in it })
    }

    @Test fun aCollapsedRunIsNotPublished() = runBlocking {
        val out = Files.createTempDirectory("out").toFile(); val cache = Files.createTempDirectory("cache").toFile()
        val a = FakeShop("a", (1..10).map { "Jacket Model $it" })
        collect(listOf(a), out, cache, CrawlOptions(1), now = 1, log = {})
        a.titles = listOf("Jacket Model 1")
        val report = collect(listOf(a), out, cache, CrawlOptions(2), now = 2, log = {})
        assertIs<Verdict.Refuse>(report.verdict)
        assertEquals(10, Catalogue.open(out).meta.products, "the previous catalogue is left in place")
    }
}
