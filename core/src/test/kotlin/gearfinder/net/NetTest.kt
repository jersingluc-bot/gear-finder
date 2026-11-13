package gearfinder.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NetTest {
    private fun server(handler: (String) -> MockResponse): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request.path ?: "/") }
        start()
    }
    private fun http() = Http(gapMs = 0, backoffMs = 10)

    @Test fun challengeBlocksHostForGood() = runBlocking {
        val s = server { MockResponse().setResponseCode(403).setHeader("cf-mitigated", "challenge").setBody("Verifying your connection...") }
        val h = http()
        assertIs<Fetch.Blocked>(h.get(s.url("/a").toString()))
        assertTrue(h.isBlocked(s.hostName))
        assertIs<Fetch.Blocked>(h.get(s.url("/b").toString()))
        assertEquals(1, s.requestCount, "no request is sent to a blocked host")
        s.shutdown()
    }

    @Test fun retriesAfter429ThenOk() = runBlocking {
        var n = 0
        val s = server { if (n++ == 0) MockResponse().setResponseCode(429).setHeader("Retry-After", "1") else MockResponse().setBody("hello") }
        val r = http().get(s.url("/").toString())
        assertEquals(Fetch.Ok("hello"), r); assertEquals(2, s.requestCount)
        s.shutdown()
    }

    @Test fun notFoundIsGoneAndServerErrorsFail() = runBlocking {
        val s = server { if (it == "/gone") MockResponse().setResponseCode(404) else MockResponse().setResponseCode(500) }
        val h = http()
        assertIs<Fetch.Gone>(h.get(s.url("/gone").toString()))
        assertIs<Fetch.Failed>(h.get(s.url("/boom").toString()))
        assertEquals(4, s.requestCount, "one for 404, three tries for 500")
        assertFalse(h.isBlocked(s.hostName))
        s.shutdown()
    }

    @Test fun sendsHonestUserAgent() = runBlocking {
        val s = server { MockResponse().setBody("x") }
        http().get(s.url("/").toString())
        assertEquals("GearFinder/1.0 (personal price comparison)", s.takeRequest().getHeader("User-Agent"))
        s.shutdown()
    }

    @Test fun sitemapIndexFollowsOnlyMatchingChildren() = runBlocking {
        lateinit var base: String
        val s = server { path ->
            when (path) {
                "/sitemap.xml" -> MockResponse().setBody("<sitemapindex><sitemap><loc>$base/product-1.xml</loc></sitemap><sitemap><loc>$base/pages.xml</loc></sitemap></sitemapindex>")
                "/product-1.xml" -> MockResponse().setBody("<urlset><url><loc>$base/p/a?x=1&amp;y=2</loc></url><url><loc>$base/c/cat</loc></url></urlset>")
                else -> MockResponse().setBody("<urlset><url><loc>$base/p/never</loc></url></urlset>")
            }
        }
        base = s.url("/").toString().trimEnd('/')
        val urls = sitemapUrls(http(), "$base/sitemap.xml", childFilter = Regex("product-\\d+"), urlFilter = Regex("/p/"))
        assertEquals(listOf("$base/p/a?x=1&y=2"), urls)
        s.shutdown()
    }

    @Test fun robotsRules() {
        val r = Robots("User-agent: *\nDisallow: /api*\nAllow: /api/aem/search*\nDisallow: /checkout\n\nUser-agent: Other\nDisallow: /")
        assertTrue(r.allows("/p/x.html")); assertTrue(r.allows("/api/aem/search?q=1"))
        assertFalse(r.allows("/api/stock")); assertFalse(r.allows("/checkout/now"))
        assertTrue(Robots("").allows("/anything"))
    }
}
