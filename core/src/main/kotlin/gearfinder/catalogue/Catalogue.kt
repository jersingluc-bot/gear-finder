package gearfinder.catalogue

import gearfinder.model.Offer
import gearfinder.model.Product
import gearfinder.recommend.featureTags
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable data class RetailerHealth(val id: String, val name: String, val items: Int, val lastSuccess: Long)

@Serializable data class Meta(val builtAt: Long, val products: Int, val retailers: List<RetailerHealth>, val format: Int = FORMAT)

const val FORMAT = 1

/** What the app keeps in memory for every product: enough to search, filter and show a card. */
@Serializable
data class IndexRow(
    val id: String, val brand: String, val name: String, val group: String, val category: String, val gender: String?,
    val image: String?, val fromPence: Int, val wasPence: Int?, val shopCount: Int, val inStock: Boolean,
    val tags: List<String>,
    /** Each shop's colours and in-stock sizes, packed as text; see [packOffers]. */
    val offers: String,
    val at: Long, val len: Int,
)

/** One colour at one shop, as far as filtering needs it. [sizes] is null when the shop does not reveal stock per size. */
data class Slot(val retailerId: String, val colourKey: String, val pence: Int, val inStock: Boolean, val sizes: Set<String>?)

// retailer~colour~pence~stock~sizes  joined by '^';  sizes are in-stock size keys joined by ',' ; '?' means unknown.
internal fun packOffers(offers: List<Offer>): String = offers.flatMap { o ->
    o.variants.map { v ->
        val sizes = if (v.sizes.isEmpty()) "?" else v.sizes.filter { it.inStock }.joinToString(",") { it.key }
        listOf(o.retailerId, v.colourKey, v.pricePence, if (v.inStock) 1 else 0, sizes).joinToString("~")
    }
}.joinToString("^")

internal fun unpackOffers(packed: String): List<Slot> = if (packed.isEmpty()) emptyList() else packed.split('^').map {
    val p = it.split('~')
    Slot(p[0], p[1], p[2].toInt(), p[3] == "1", if (p[4] == "?") null else p[4].split(',').filter(String::isNotEmpty).toSet())
}

private val JSON = Json { ignoreUnknownKeys = true }

object CatalogueWriter {
    /** Writes the catalogue into [dir]: products.jsonl.gz, index.jsonl.gz and meta.json, ready to publish. */
    fun write(dir: File, products: List<Product>, retailers: List<RetailerHealth>, builtAt: Long): Meta {
        dir.mkdirs()
        var at = 0L
        GZIPOutputStream(File(dir, "products.jsonl.gz").outputStream()).bufferedWriter().use { body ->
            GZIPOutputStream(File(dir, "index.jsonl.gz").outputStream()).bufferedWriter().use { index ->
                for (p in products) {
                    val line = JSON.encodeToString(Product.serializer(), p)
                    val len = line.toByteArray().size
                    body.write(line); body.write("\n")
                    index.write(JSON.encodeToString(IndexRow.serializer(), row(p, at, len))); index.write("\n")
                    at += len + 1
                }
            }
        }
        val meta = Meta(builtAt, products.size, retailers)
        File(dir, "meta.json").writeText(JSON.encodeToString(Meta.serializer(), meta))
        return meta
    }

    private fun row(p: Product, at: Long, len: Int): IndexRow {
        val all = p.offers.flatMap { it.variants }
        val live = all.filter { it.inStock }
        val best = live.ifEmpty { all }.minBy { it.pricePence }
        return IndexRow(
            p.id, p.brand, p.name, p.group, p.category, p.gender, p.image, best.pricePence, best.wasPence, p.offers.size, live.isNotEmpty(),
            featureTags("${p.name} ${p.category} ${p.description ?: ""}"), packOffers(p.offers), at, len,
        )
    }
}

/** An opened catalogue: the index in memory, product details read from disk on demand. */
class Catalogue private constructor(val meta: Meta, val rows: List<IndexRow>, private val body: File) {
    private val byId: Map<String, IndexRow> by lazy { rows.associateBy { it.id } }
    internal val hay: List<String> by lazy { rows.map { "${it.brand} ${it.name} ${it.category} ${it.group}".lowercase() } }

    fun product(id: String): Product? {
        val r = byId[id] ?: return null
        return RandomAccessFile(body, "r").use { f ->
            val buf = ByteArray(r.len); f.seek(r.at); f.readFully(buf)
            JSON.decodeFromString(Product.serializer(), buf.decodeToString())
        }
    }

    companion object {
        /** Unpacks downloaded .gz files in [dir] if needed, then loads. Throws if the files are missing or damaged. */
        fun open(dir: File): Catalogue {
            val body = File(dir, "products.jsonl")
            val gz = File(dir, "products.jsonl.gz")
            if (!body.exists() || (gz.exists() && gz.lastModified() > body.lastModified())) {
                val tmp = File(dir, "products.jsonl.tmp")
                GZIPInputStream(gz.inputStream()).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(body)) { body.delete(); check(tmp.renameTo(body)) { "could not unpack catalogue" } }
            }
            val meta = JSON.decodeFromString(Meta.serializer(), File(dir, "meta.json").readText())
            val rows = GZIPInputStream(File(dir, "index.jsonl.gz").inputStream()).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.map { JSON.decodeFromString(IndexRow.serializer(), it) }.toList()
            }
            check(rows.size == meta.products) { "catalogue is incomplete: ${rows.size} of ${meta.products}" }
            check(rows.isEmpty() || body.length() >= rows.last().at + rows.last().len) { "catalogue body is incomplete" }
            return Catalogue(meta, rows, body)
        }
    }
}
