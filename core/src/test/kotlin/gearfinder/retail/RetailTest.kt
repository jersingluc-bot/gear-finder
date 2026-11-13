package gearfinder.retail

import gearfinder.model.Listing
import gearfinder.net.Http
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryCache : CrawlCache {
    val map = LinkedHashMap<String, Listing?>()
    override fun get(url: String) = if (url in map) Cached(map[url]) else null
    override fun put(url: String, listing: Listing?) { map[url] = listing }
    override fun all() = map.values.filterNotNull()
}

class RetailTest {
    private fun page(ld: String) = """<html><head><script type="application/ld+json">$ld</script></head><body></body></html>"""
    private fun fixture(id: String) = javaClass.getResource("/pages/$id.html")!!.readText() to javaClass.getResource("/pages/$id.url")!!.readText().trim()

    @Test fun jsonLdSingleProduct() {
        val l = parseJsonLdProduct(page("""{"@type":"Product","name":"Rab Men&#39;s Jacket","brand":{"name":"Rab"},
            "offers":{"@type":"Offer","price":"105.00","availability":"https://schema.org/InStock"}}"""), "https://x/p", "eb")!!
        assertEquals("Rab Men's Jacket", l.title); assertEquals("Rab", l.brand)
        assertEquals(1, l.variants.size); assertEquals(10500, l.variants[0].pricePence); assertTrue(l.variants[0].inStock)
        assertTrue(l.variants[0].sizes.isEmpty(), "sizes unknown, not invented")
    }

    @Test fun jsonLdProductGroupSplitsColoursAndSizes() {
        val v = { c: String, s: String, p: String, a: String -> """{"@type":"Product","color":"$c","size":"$s","offers":{"price":"$p","availability":"https://schema.org/$a"}}""" }
        val l = parseJsonLdProduct(page("""{"@type":"ProductGroup","name":"Latok Jacket","brand":"Rab","hasVariant":[
            ${v("Black", "M", "300.00", "InStock")},${v("Black", "L", "280.00", "OutOfStock")},${v("Blue", "M", "250.50", "InStock")}]}"""), "https://x/p", "alpine")!!
        assertEquals(listOf("Black", "Blue"), l.variants.map { it.colour })
        val black = l.variants[0]
        assertEquals(30000, black.pricePence, "headline price is the cheapest size in stock")
        assertEquals(listOf("m" to true, "l" to false), black.sizes.map { it.key to it.inStock })
        assertEquals(25050, l.variants[1].pricePence)
    }

    @Test fun pagesWithoutAProductGiveNothing() {
        assertNull(parseJsonLdProduct("<html></html>", "u", "x")); assertNull(parseJsonLdProduct(page("{not json"), "u", "x"))
        assertNull(parseAsAdventure("<html></html>", "u", "x")); assertNull(parseTrekitt("<html></html>", "u", "x"))
    }

    @Test fun realPagesParse() {
        val parsers: Map<String, PageParser> = mapOf(
            "eb" to ::parseJsonLdProduct, "alpine" to ::parseJsonLdProduct, "absolutesnow" to ::parseJsonLdProduct, "ultralight" to ::parseJsonLdProduct,
            "cotswold" to ::parseAsAdventure, "snowrock" to ::parseAsAdventure, "runnersneed" to ::parseAsAdventure,
            "trekitt" to ::parseTrekitt, "joebrown" to ::parseTrekitt, "taunton" to ::parseTrekitt,
        )
        for ((id, parse) in parsers) {
            val (html, url) = fixture(id)
            val l = assertNotNull(parse(html, url, id), "$id did not parse")
            assertTrue(l.title.length > 5, "$id title"); assertTrue(l.variants.isNotEmpty(), "$id variants")
            assertTrue(l.variants.all { it.pricePence in 100..300000 }, "$id price ${l.variants.map { it.pricePence }}")
            assertTrue(l.variants.all { it.image?.startsWith("https://") == true }, "$id image")
            if (id !in setOf("trekitt", "joebrown", "taunton")) assertTrue(l.brand.isNotEmpty(), "$id brand")
            if (id in setOf("alpine", "cotswold", "snowrock", "runnersneed")) {
                val sizes = l.variants.flatMap { it.sizes }
                assertTrue(sizes.map { it.key }.distinct().size >= 2, "$id sizes ${sizes.map { it.label }}")
                assertTrue(sizes.all { it.key.isNotEmpty() }, "$id size keys")
            }
        }
    }

    @Test fun realPageValues() {
        val (html, url) = fixture("cotswold")
        val l = parseAsAdventure(html, url, "cotswold")!!
        assertEquals("Rab", l.brand); assertTrue("Microlight" in l.title, l.title)
        assertTrue(l.variants.any { it.pricePence == 12600 }, "page showed £126: ${l.variants.map { it.pricePence }}")
        val t = fixture("trekitt").let { parseTrekitt(it.first, it.second, "trekitt")!! }
        assertEquals(14000, t.variants[0].pricePence); assertEquals(17500, t.variants[0].wasPence)
        assertEquals("Rab Women's Phantom Jacket Hibiscus", t.title)
    }

    @Test fun crawlKeepsCachedDataWhenTheShopStartsRefusing() = runBlocking {
        var refuse = false
        lateinit var base: String
        val ld = { name: String -> page("""{"@type":"Product","name":"$name","brand":"B","offers":{"price":"10.00","availability":"InStock"}}""") }
        val s = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    refuse -> MockResponse().setResponseCode(403).setHeader("cf-mitigated", "challenge")
                    request.path == "/robots.txt" -> MockResponse().setBody("User-agent: *\nDisallow: /p/secret")
                    request.path == "/sitemap.xml" -> MockResponse().setBody("<urlset><url><loc>$base/p/one</loc></url><url><loc>$base/p/two</loc></url><url><loc>$base/p/gone</loc></url><url><loc>$base/p/secret</loc></url></urlset>")
                    request.path == "/p/gone" -> MockResponse().setResponseCode(404)
                    else -> MockResponse().setBody(ld("Item ${request.path!!.substringAfterLast('/')}"))
                }
            }
            start()
        }
        base = s.url("/").toString().trimEnd('/')
        val shop = SitemapAdapter("t", "Test", base, listOf("$base/sitemap.xml"))
        val cache = MemoryCache()

        val first = mutableListOf<Listing>()
        val r1 = shop.crawl(Http(gapMs = 0, backoffMs = 5), cache, CrawlOptions(day = 1)) { first += it }
        assertEquals(setOf("Item one", "Item two"), first.map { it.title }.toSet()); assertEquals(3, r1.listed); assertEquals(false, r1.blocked)

        refuse = true
        val second = mutableListOf<Listing>()
        val r2 = shop.crawl(Http(gapMs = 0, backoffMs = 5), cache, CrawlOptions(day = 2)) { second += it }
        assertEquals(true, r2.blocked); assertEquals(setOf("Item one", "Item two"), second.map { it.title }.toSet())
        s.shutdown()
    }
}
