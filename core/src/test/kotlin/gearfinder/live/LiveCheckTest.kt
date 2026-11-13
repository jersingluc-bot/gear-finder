package gearfinder.live

import gearfinder.merge.listing
import gearfinder.merge.merge
import gearfinder.merge.v
import gearfinder.model.Listing
import gearfinder.net.Http
import gearfinder.retail.CrawlCache
import gearfinder.retail.CrawlOptions
import gearfinder.retail.CrawlResult
import gearfinder.retail.RetailerAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Shop(override val id: String, val answer: suspend (String) -> Listing?) : RetailerAdapter {
    override val name = id.uppercase(); override val home = "https://$id"
    override suspend fun crawl(http: Http, cache: CrawlCache, options: CrawlOptions, emit: suspend (Listing) -> Unit) = CrawlResult(0, 0, false)
    override suspend fun refresh(http: Http, url: String) = answer(url)
}

class LiveCheckTest {
    private val title = "Men's Latok Jacket"
    private val product = merge(listOf(
        listing("a", title, "Rab", v("Black", 30000, "M" to true, "L" to true, url = "https://a/1")),
        listing("b", title, "Rab", v("Black", 28000, "M" to true, url = "https://b/1")),
        listing("c", title, "Rab", v(null, 25000, url = "https://c/1")),
        listing("d", title, "Rab", v("Black", 20000, "L" to true, url = "https://d/1")),
    )).single()

    @Test fun rowsFromTheCatalogue() {
        val rows = sortRows(product.offers.map { rowFor(it, it.retailerId, "m", null, Freshness.Checking) })
        assertEquals(listOf("b", "a", "c", "d"), rows.map { it.retailerId }, "confirmed medium first by price, then size-unknown, then no medium")
        assertEquals(false, rows.single { it.retailerId == "c" }.sizeConfirmed)
        assertEquals(false, rows.single { it.retailerId == "d" }.inStock, "d has no medium")
        assertEquals(null, rowFor(product.offers.single { it.retailerId == "a" }, "a", null, "blue", Freshness.Live).pricePence, "colour not sold")
        assertEquals(20000, sortRows(product.offers.map { rowFor(it, it.retailerId, null, null, Freshness.Live) })[0].pricePence)
    }

    @Test fun liveCheckUpdatesFailsSoftAndNeverWaitsOnASlowShop() = runBlocking {
        val shops = listOf(
            Shop("a") { url -> listing("a", title, "Rab", v("Black", 26000, "M" to true, "L" to false, url = url)) },   // price dropped
            Shop("b") { error("site down") },
            Shop("c") { delay(60_000); null },                                                                           // hangs
            Shop("d") { null },
        )
        val started = System.currentTimeMillis()
        val steps = liveRows(product, "m", null, shops, Http(), timeoutMs = 300).toList()
        assertTrue(System.currentTimeMillis() - started < 5_000, "a hanging shop is cut off")
        assertTrue(steps.first().all { it.freshness == Freshness.Checking }, "catalogue figures show at once")
        val last = steps.last().associateBy { it.retailerId }
        assertEquals(Freshness.Live, last["a"]!!.freshness); assertEquals(26000, last["a"]!!.pricePence)
        assertEquals("a", steps.last()[0].retailerId, "the new lower price moves to the top")
        assertEquals("A", last["a"]!!.retailerName)
        for (id in listOf("b", "c", "d")) assertEquals(Freshness.NotRechecked, last[id]!!.freshness, id)
        assertEquals(28000, last["b"]!!.pricePence, "the catalogue price stays when the check fails")
    }

    @Test fun eachShopPageIsReadOnceHoweverManySizesAreTried() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val shops = listOf("a", "b", "c", "d").map { id -> Shop(id) { url -> calls.incrementAndGet(); listing(id, title, "Rab", v("Black", 19000, "M" to true, "L" to true, url = url)) } }
        val live = liveOffers(product, shops, Http()).toList().last()
        assertEquals(4, calls.get())
        // Trying other sizes and colours afterwards is pure arithmetic on what was fetched.
        assertEquals(4, rows(live, "l", null).count { it.inStock }); assertEquals(0, rows(live, "xl", null).count { it.inStock })
        assertEquals(0, rows(live, null, "blue").count { it.pricePence != null }); assertEquals(4, calls.get())
    }

    @Test fun aPageWithoutColourKeepsTheColourFromTheCatalogue() {
        val offer = merge(listOf(listing("trekitt", "Rab Men's Latok Jacket Black", "", v(null, 30000, url = "https://t/1")))).single().offers.single()
        val out = refreshed(offer, "https://t/1", listOf(v(null, 27000, url = "https://t/1")))
        assertEquals("black", out.variants.single().colourKey); assertEquals(27000, out.variants.single().pricePence)
    }
}
