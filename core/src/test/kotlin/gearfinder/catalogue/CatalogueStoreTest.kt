package gearfinder.catalogue

import gearfinder.merge.listing
import gearfinder.merge.merge
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogueStoreTest {
    private fun tmp() = Files.createTempDirectory("store").toFile()
    private fun published(builtAt: Long, vararg titles: String): File = tmp().also { d ->
        CatalogueWriter.write(d, merge(titles.map { listing("eb", it) }), emptyList(), builtAt)
    }
    /** Serves whatever folder [current] points at; [cut] sends only the first bytes of the big file. */
    private class Server(var current: File, var cut: Boolean = false, var down: Boolean = false) {
        val web = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (down) return MockResponse().setResponseCode(503)
                    val f = File(current, request.path!!.trimStart('/'))
                    val bytes = f.readBytes().let { if (cut && f.name == "products.jsonl.gz") it.copyOf(it.size / 2) else it }
                    return MockResponse().setBody(Buffer().write(bytes))
                }
            }
            start()
        }
        val url get() = web.url("/").toString()
        fun count(name: String) = (0 until web.requestCount).count { web.takeRequest().path == "/$name" }
    }

    @Test fun firstRunDownloadsThenNewerReplacesAndSameIsLeftAlone() = runBlocking {
        val server = Server(published(100, "Latok Jacket"))
        val dir = tmp(); val store = CatalogueStore(dir, server.url)
        store.refresh()
        assertEquals(1, assertIs<CatalogueState.Ready>(store.state.value).catalogue.meta.products)

        store.refresh() // nothing newer
        server.current = published(200, "Latok Jacket", "Kinetic Trousers")
        store.refresh()
        val ready = assertIs<CatalogueState.Ready>(store.state.value)
        assertEquals(200, ready.catalogue.meta.builtAt); assertEquals(2, ready.catalogue.meta.products); assertNull(ready.problem)
        assertEquals(2, server.count("products.jsonl.gz"), "the unchanged catalogue was not downloaded again")
        assertEquals(listOf("cat-200"), dir.listFiles()!!.filter { it.name.startsWith("cat-") }.map { it.name }, "old copies are removed")

        // A new app start finds the saved catalogue without the network.
        server.down = true
        val restarted = CatalogueStore(dir, server.url); restarted.refresh()
        val again = assertIs<CatalogueState.Ready>(restarted.state.value)
        assertEquals(200, again.catalogue.meta.builtAt); assertNotNull(again.problem)
        server.web.shutdown()
    }

    @Test fun aCutOffDownloadKeepsThePreviousCatalogue() = runBlocking {
        val server = Server(published(100, "Latok Jacket"))
        val dir = tmp(); val store = CatalogueStore(dir, server.url)
        store.refresh()
        server.current = published(200, "Latok Jacket", "Kinetic Trousers", "Phantom Jacket"); server.cut = true
        store.refresh()
        val ready = assertIs<CatalogueState.Ready>(store.state.value)
        assertEquals(100, ready.catalogue.meta.builtAt); assertTrue(ready.problem!!.contains("did not finish"))
        assertNotNull(ready.catalogue.product(ready.catalogue.rows[0].id), "the old catalogue still reads")
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
        server.web.shutdown()
    }

    @Test fun firstRunWithNoNetworkSaysSo() = runBlocking {
        val server = Server(tmp(), down = true)
        val store = CatalogueStore(tmp(), server.url); store.refresh()
        assertIs<CatalogueState.Unavailable>(store.state.value)
        server.web.shutdown()
    }

    @Test fun aLocalFolderWorksAsASource() = runBlocking {
        val store = CatalogueStore(tmp(), published(5, "Latok Jacket").path); store.refresh()
        assertEquals(5, assertIs<CatalogueState.Ready>(store.state.value).catalogue.meta.builtAt)
    }
}
