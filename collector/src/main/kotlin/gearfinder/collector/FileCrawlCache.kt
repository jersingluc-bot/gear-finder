package gearfinder.collector

import gearfinder.model.Listing
import gearfinder.retail.Cached
import gearfinder.retail.CrawlCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable private data class Entry(val url: String, val listing: Listing? = null)

private val JSON = Json { ignoreUnknownKeys = true }

/** One shop's pages from earlier runs, kept as a gzipped file of JSON lines. A damaged file is treated as empty. */
class FileCrawlCache(private val file: File) : CrawlCache {
    private val map = LinkedHashMap<String, Listing?>()

    init {
        if (file.exists()) runCatching {
            GZIPInputStream(file.inputStream()).bufferedReader().useLines { lines ->
                for (line in lines) if (line.isNotBlank()) JSON.decodeFromString(Entry.serializer(), line).let { map[it.url] = it.listing }
            }
        }.onFailure { map.clear() }
    }

    @Synchronized override fun get(url: String): Cached? = if (url in map) Cached(map[url]) else null
    @Synchronized override fun put(url: String, listing: Listing?) { map[url] = listing }
    @Synchronized override fun all(): List<Listing> = map.values.filterNotNull()

    override fun checkpoint() = save()

    /** Keeps only the addresses in [live] (pages the shop still lists), then writes the file. */
    @Synchronized fun save(live: Set<String>? = null) {
        if (live != null && live.isNotEmpty()) map.keys.retainAll(live)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { w -> for ((u, l) in map) { w.write(JSON.encodeToString(Entry.serializer(), Entry(u, l))); w.write("\n") } }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
