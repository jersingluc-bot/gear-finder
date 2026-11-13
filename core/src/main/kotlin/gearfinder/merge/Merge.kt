package gearfinder.merge

import gearfinder.model.Listing
import gearfinder.model.Offer
import gearfinder.model.Product
import gearfinder.model.Variant
import gearfinder.model.colourKey
import gearfinder.model.sizeKey

private val STOP = setOf("mens", "men", "womens", "women", "ladies", "unisex", "the", "and", "with", "for", "a", "of", "kids", "junior")
private val NOT_A_COLOUR = Regex("""\d|\b(pack|set|kit|bundle|pair|of|x)\b""", RegexOption.IGNORE_CASE)
private val SUFFIX = Regex("""^(.*?)\s[-–|]\s(.+)$""")
private val IN_COLOUR = Regex("""^(.*\S)\s+in\s+([A-Z][A-Za-z]*(?:[ /][A-Z][A-Za-z]*){0,3})$""")
private val BASIC_COLOURS = "black white grey gray blue red green orange yellow purple pink brown navy olive beige cream silver gold teal lime sand khaki".split(" ")
/** Shops whose titles end with the colour ("... Phantom Jacket Hibiscus"). */
private val COLOUR_IN_TITLE = setOf("trekitt", "joebrown", "taunton")

/** "Jacket - Black" gives "Jacket". A suffix with digits or words like "Pack of 28" is part of the product, not a colour. */
fun baseName(title: String): String {
    val m = SUFFIX.find(title.trim()) ?: return title.trim()
    val tail = m.groupValues[2]
    return if (!NOT_A_COLOUR.containsMatchIn(tail) && tail.split(" ").size <= 4) m.groupValues[1].trim() else title.trim()
}

private fun tailColour(title: String): String? = SUFFIX.find(title.trim())?.groupValues?.get(2)?.takeIf { baseName(title) != title.trim() }

internal fun matchKey(brand: String, title: String): String {
    val b = brand.lowercase().replace(Regex("[^a-z0-9]"), "")
    val words = baseName(title).lowercase().replace(Regex("""[’'`]s?\b"""), "").replace(Regex("[^a-z0-9 ]"), " ")
        .split(Regex("\\s+")).filter { it.isNotEmpty() && it !in STOP && it != b }
    return "$b|${words.sorted().joinToString(" ")}"
}

internal fun genderOf(title: String): String {
    val t = title.lowercase()
    return when {
        Regex("""\b(women|womens|women's|ladies|wmns)\b|women’s""").containsMatchIn(t) -> "Women"
        Regex("""\b(men|mens|men's)\b|men’s""").containsMatchIn(t) -> "Men"
        Regex("""\b(kids|junior|youth|children|boys|girls)\b""").containsMatchIn(t) -> "Kids"
        else -> "Unisex"
    }
}

private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(80)

private class Work(val src: Listing, var title: String, var brand: String, var gender: String, var variants: List<Variant>)

/** Groups listings from different shops into one product each, with one offer per shop. Out-of-scope items are dropped. */
fun merge(listings: List<Listing>): List<Product> {
    val vocab = BASIC_COLOURS.toMutableSet()
    for (l in listings) for (v in l.variants) v.colour?.lowercase()?.split(Regex("[^a-z]+"))?.filterTo(vocab) { it.length > 2 }
    val canon = KNOWN_BRANDS.associateBy { it.lowercase() }
    val brands = (listings.map { it.brand }.filter { it.isNotBlank() } + KNOWN_BRANDS).distinct().sortedByDescending { it.length }

    val work = listings.filter { it.title.isNotBlank() && it.variants.isNotEmpty() }.map { l ->
        // Keys are recomputed here so saved listings pick up any improvement to the normalising rules.
        val fresh = l.variants.map { v -> v.copy(colourKey = colourKey(v.colour), sizes = v.sizes.map { it.copy(key = sizeKey(it.label)) }) }
        val w = Work(l, l.title.trim(), l.brand.trim(), l.gender ?: "", fresh)
        if (l.retailerId in COLOUR_IN_TITLE) {
            var colour: String? = null
            IN_COLOUR.find(w.title)?.let { w.title = it.groupValues[1]; colour = it.groupValues[2] }
            if (colour == null) tailColour(w.title)?.let { colour = it; w.title = baseName(w.title) }
            if (colour == null) {
                val words = w.title.split(" ").toMutableList(); val peeled = ArrayDeque<String>()
                while (words.size > 3 && words.last().lowercase().replace(Regex("[^a-z]"), "") in vocab) peeled.addFirst(words.removeLast())
                if (peeled.isNotEmpty()) { colour = peeled.joinToString(" "); w.title = words.joinToString(" ") }
            }
            colour?.let { c -> w.variants = w.variants.map { if (it.colourKey.isEmpty()) it.copy(colour = c, colourKey = colourKey(c)) else it } }
        }
        if (w.brand.isEmpty()) w.brand = brands.firstOrNull { w.title.lowercase().startsWith(it.lowercase() + " ") } ?: ""
        canon[w.brand.lowercase()]?.let { w.brand = it }
        if (w.brand.isNotEmpty() && w.title.lowercase().startsWith(w.brand.lowercase() + " ")) w.title = w.title.substring(w.brand.length).trim()
        // "Rab - Latok Jacket - Waterproof jacket" (brand - model - type) keeps the model.
        w.title = w.title.removePrefix("- ").trim()
        if (w.gender.isEmpty()) w.gender = genderOf(w.title)
        w
    }

    // Gendered listings group first; a unisex-looking one joins the single gendered group of the same model if there is one.
    val groups = LinkedHashMap<String, MutableList<Work>>()
    for (w in work.filter { it.gender != "Unisex" }) groups.getOrPut("${matchKey(w.brand, w.title)}|${w.gender}") { mutableListOf() } += w
    for (w in work.filter { it.gender == "Unisex" }) {
        val base = matchKey(w.brand, w.title)
        val gendered = listOf("Men", "Women", "Kids").filter { "$base|$it" in groups }
        groups.getOrPut("$base|${gendered.singleOrNull() ?: "Unisex"}") { mutableListOf() } += w
    }

    val seen = HashMap<String, Int>()
    return groups.values.mapNotNull { g ->
        val first = g[0]
        val place = classify(first.title, first.src.categoryHint, first.brand) ?: return@mapNotNull null
        val offers = g.groupBy { it.src.retailerId }.map { (retailer, ws) ->
            val byColour = LinkedHashMap<String, Variant>()
            for (w in ws) for (v in w.variants) {
                val cur = byColour[v.colourKey]
                if (cur == null || (v.inStock && !cur.inStock) || (v.inStock == cur.inStock && v.pricePence < cur.pricePence)) byColour[v.colourKey] = v
            }
            val variants = byColour.values.sortedWith(compareByDescending<Variant> { it.inStock }.thenBy { it.pricePence })
            Offer(retailer, variants.first().url, variants)
        }.sortedWith(compareByDescending<Offer> { o -> o.variants.any { it.inStock } }.thenBy { o -> o.variants.minOf { it.pricePence } })
        val name = baseName(first.title)
        val id = slug("${first.brand}-$name-${first.gender}").ifEmpty { slug(first.src.title) }
        val n = seen.merge(id, 1, Int::plus)!!
        Product(
            id = if (n > 1) "$id-$n" else id, brand = first.brand.ifEmpty { "Unknown" }, name = name, group = place.group, category = place.category,
            gender = first.gender, description = g.mapNotNull { it.src.description }.maxByOrNull { it.length },
            image = offers.first().variants.firstNotNullOfOrNull { it.image }, offers = offers,
        )
    }
}
