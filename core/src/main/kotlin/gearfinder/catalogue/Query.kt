package gearfinder.catalogue

import gearfinder.merge.TAXONOMY

enum class Sort { Relevance, PriceLow, PriceHigh, Discount }

data class Query(
    val text: String = "", val group: String? = null, val category: String? = null, val brands: Set<String> = emptySet(),
    val retailers: Set<String> = emptySet(), val sizeKey: String? = null, val colourKey: String? = null, val gender: String? = null,
    val maxPence: Int? = null, val inStockOnly: Boolean = false, val onSaleOnly: Boolean = false,
    val sort: Sort = Sort.Relevance, val limit: Int = 60, val offset: Int = 0,
)

data class Summary(
    val id: String, val brand: String, val name: String, val category: String, val gender: String?, val image: String?,
    /** Cheapest price that satisfies the query's size, colour and shop filters. */
    val fromPence: Int, val wasPence: Int?, val shopCount: Int, val inStock: Boolean, val tags: List<String>,
)

data class Facets(val groups: Map<String, List<String>>, val brands: List<String>, val sizes: List<String>, val colours: List<String>)

data class Results(val total: Int, val items: List<Summary>)

/** The cheapest matching price for [row] under the offer-level filters, or null when nothing matches. */
internal fun bestPrice(row: IndexRow, q: Query): Int? {
    val offerFilters = q.retailers.isNotEmpty() || q.sizeKey != null || q.colourKey != null || q.inStockOnly
    if (!offerFilters) return row.fromPence.takeIf { q.maxPence == null || it <= q.maxPence }
    val colourWords = q.colourKey?.split(' ')
    val price = unpackOffers(row.offers).filter { s ->
        (q.retailers.isEmpty() || s.retailerId in q.retailers) &&
            (colourWords == null || s.colourKey.split(' ').containsAll(colourWords)) &&
            // A size filter needs the shop to confirm that size is in stock; shops that hide size stock do not count.
            (q.sizeKey == null || (s.sizes != null && q.sizeKey in s.sizes)) &&
            (!q.inStockOnly || s.inStock)
    }.minOfOrNull { it.pence }
    return price?.takeIf { q.maxPence == null || it <= q.maxPence }
}

fun Catalogue.search(q: Query): Results {
    val terms = q.text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val wantGender = q.gender
    val hits = ArrayList<Pair<Summary, Int>>()
    for ((i, r) in rows.withIndex()) {
        if (q.group != null && r.group != q.group) continue
        if (q.category != null && r.category != q.category) continue
        if (q.brands.isNotEmpty() && r.brand !in q.brands) continue
        if (wantGender != null && r.gender != wantGender && r.gender != "Unisex") continue
        if (q.onSaleOnly && (r.wasPence == null || r.wasPence <= r.fromPence)) continue
        if (terms.isNotEmpty()) { val h = hay[i]; if (!terms.all { it in h }) continue }
        val price = bestPrice(r, q) ?: continue
        val name = r.name.lowercase()
        val score = terms.count { it in name } * 4 + (if (r.inStock) 3 else 0) + minOf(r.shopCount, 5)
        hits += Summary(r.id, r.brand, r.name, r.category, r.gender, r.image, price, r.wasPence?.takeIf { it > price }, r.shopCount, r.inStock, r.tags) to score
    }
    val discount = { s: Summary -> s.wasPence?.let { (it - s.fromPence) * 100 / it } ?: 0 }
    val sorted = when (q.sort) {
        Sort.Relevance -> hits.sortedWith(compareByDescending<Pair<Summary, Int>> { it.second }.thenBy { it.first.fromPence })
        Sort.PriceLow -> hits.sortedBy { it.first.fromPence }
        Sort.PriceHigh -> hits.sortedByDescending { it.first.fromPence }
        Sort.Discount -> hits.sortedByDescending { discount(it.first) }
    }
    return Results(sorted.size, sorted.drop(q.offset).take(q.limit).map { it.first })
}

/** Choices for the filter sheet, limited to [group]/[category] when given. */
fun Catalogue.facets(group: String? = null, category: String? = null): Facets {
    val brands = HashMap<String, Int>(); val sizes = HashMap<String, Int>(); val colours = HashMap<String, Int>()
    for (r in rows) {
        if (group != null && r.group != group) continue
        if (category != null && r.category != category) continue
        brands.merge(r.brand, 1, Int::plus)
        for (s in unpackOffers(r.offers)) {
            s.sizes?.forEach { if (it.length <= 6) sizes.merge(it, 1, Int::plus) }
            s.colourKey.split(' ').filter { it.length > 2 }.forEach { colours.merge(it, 1, Int::plus) }
        }
    }
    val top = { m: Map<String, Int>, n: Int -> m.entries.sortedByDescending { it.value }.take(n).map { it.key } }
    val present = rows.map { it.group to it.category }.toSet()
    val groups = TAXONOMY.mapValues { (g, cats) -> cats.filter { (g to it) in present } }.filterValues { it.isNotEmpty() }
    return Facets(groups, top(brands, 400).sorted(), top(sizes, 40), top(colours, 30))
}
