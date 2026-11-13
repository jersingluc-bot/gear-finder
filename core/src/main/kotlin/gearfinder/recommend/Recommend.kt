package gearfinder.recommend

import gearfinder.catalogue.Catalogue
import gearfinder.catalogue.Query
import gearfinder.catalogue.Summary
import gearfinder.catalogue.search
import gearfinder.merge.Placement
import gearfinder.merge.classify
import gearfinder.merge.genderOf
import gearfinder.model.colourKey
import gearfinder.model.sizeKey

/** What the app understood from a plain-words request. */
data class Request(
    val category: Placement?, val gender: String?, val sizeKey: String?, val colourKey: String?,
    val maxPence: Int?, val brand: String?, val uses: Set<String>, val features: Set<String>,
)

data class Pick(val summary: Summary, val matched: List<String>, val reason: String)

sealed interface Advice {
    data class Picks(val understood: String, val picks: List<Pick>) : Advice
    data class Ask(val question: String, val options: List<String>) : Advice
    data class Nothing(val understood: String, val why: String) : Advice
}

private val BUDGET = listOf(
    Regex("""(?:under|below|less than|max(?:imum)?(?: of)?|up to|budget(?: of)?|no more than|<)\s*£?\s*(\d{1,5})"""),
    Regex("""£\s*(\d{1,5})"""),
    Regex("""(\d{1,5})\s*(?:quid|pounds?|gbp)"""),
)
private val SIZE = listOf(
    Regex("""\bsize\s+([a-z0-9.½/]+)"""), Regex("""\buk\s*(\d{1,2}(?:\.5)?)"""),
    Regex("""\b(x{0,3}-?(?:small|large)|medium|extra (?:small|large)|[2-4]?x{1,3}[ls]|one size)\b"""),
)
private val COLOURS = "black white grey gray blue red green orange yellow purple pink brown navy olive beige cream teal khaki".split(" ")

/** Words people use for what the gear is for. */
private val USES: Map<String, Regex> = linkedMapOf(
    "winter" to "winter|cold|snow|freezing|scottish winter|alpine|mountaineering|expedition|sub-?zero",
    "wet" to "\\bwet\\b|rain|waterproof|downpour|storm|drizzle",
    "wind" to "\\bwind(y|proof)?\\b",
    "summer" to "summer|hot weather|\\bhot\\b|warm weather|heat",
    "light" to "ultralight|ultra-light|lightweight|\\blight\\b|fastpacking|backpacking|thru-?hik",
    "warm" to "\\bwarm\\b|cosy|cozy|insulat",
    "running" to "running|\\brun\\b|trail run|jogging|fell",
    "climbing" to "climbing|scrambl|bouldering",
    "tough" to "durable|tough|hard[- ]wearing|rugged",
).mapValues { Regex(it.value) }

/** Which features each use calls for; jackets and footwear get their own lists. */
private fun wanted(use: String, category: String): List<String> {
    val jacket = "Jacket" in category || "Softshell" in category || "Fleece" in category
    val footwear = "Boots" in category || "Shoes" in category
    return when (use) {
        "winter" -> when { jacket -> listOf("insulated", "down", "hood", "hardshell", "winter"); footwear -> listOf("winter", "waterproof", "ankle support"); else -> listOf("winter", "insulated", "down") }
        "wet" -> if (jacket) listOf("waterproof", "hood") else listOf("waterproof")
        "wind" -> listOf("windproof")
        "summer" -> listOf("summer", "lightweight", "breathable")
        "light" -> listOf("lightweight", "packable")
        "warm" -> listOf("insulated", "down", "fleece")
        "running" -> listOf("lightweight", "breathable", "grippy", "cushioned")
        "climbing" -> listOf("stretch", "durable")
        "tough" -> listOf("durable")
        else -> emptyList()
    }
}

/** A plain "jacket" or "shoes" should look across every kind of jacket or shoe, not one narrow shelf. */
private fun family(p: Placement): List<String> = when (p.category) {
    "Jackets & Gilets" -> listOf("Jackets & Gilets", "Waterproof Jackets", "Insulated & Down Jackets", "Softshell & Windproof")
    "Shoes & Trainers" -> listOf("Shoes & Trainers", "Trail Running Shoes")
    else -> listOf(p.category)
}

fun parseRequest(text: String, brands: Collection<String> = emptyList()): Request {
    var t = " ${text.lowercase().replace('’', '\'')} "
    val budget = BUDGET.firstNotNullOfOrNull { re -> re.find(t)?.also { t = t.replace(it.value, " ") }?.groupValues?.get(1)?.toIntOrNull() }
    val size = SIZE.firstNotNullOfOrNull { re -> re.find(t)?.also { t = t.replace(it.value, " ") }?.groupValues?.get(1) }?.let(::sizeKey)?.takeIf { it.isNotEmpty() }
    val gender = genderOf(t).takeIf { it != "Unisex" }
    val brand = brands.filter { it.length > 2 && Regex("""\b${Regex.escape(it.lowercase())}\b""").containsMatchIn(t) }.maxByOrNull { it.length }
    val colour = COLOURS.firstOrNull { Regex("""\b$it\b""").containsMatchIn(t) }?.let(::colourKey)
    val category = classify(t)?.takeIf { it.group != "Other" }
    val uses = USES.filter { it.value.containsMatchIn(t) }.keys
    return Request(category, gender, size, colour, budget?.times(100), brand, uses, featureTags(t).toSet())
}

private val STARTERS = listOf("Jacket", "Trousers", "Walking boots", "Trail running shoes", "Tent", "Sleeping bag", "Rucksack", "Base layer")

private fun pounds(pence: Int) = if (pence % 100 == 0) "£${pence / 100}" else "£%.2f".format(pence / 100.0)

private fun describe(r: Request): String = listOfNotNull(
    r.brand, r.category?.category?.takeIf { it != "Jackets & Gilets" } ?: r.category?.let { "Jackets" },
    r.gender?.let { "${it.lowercase()}'s" }, r.sizeKey?.let { "size ${it.uppercase()}" }, r.colourKey,
    r.maxPence?.let { "up to ${pounds(it)}" }, r.uses.takeIf { it.isNotEmpty() }?.joinToString(", ", "for: "),
).joinToString(" · ")

/** Suggestions for a plain-words request: about ten in-stock picks with the reason each was chosen. */
fun recommend(text: String, catalogue: Catalogue): Advice {
    if (text.isBlank()) return Advice.Ask("What are you looking for?", STARTERS)
    val brands = catalogue.rows.mapTo(HashSet()) { it.brand }
    val r = parseRequest(text, brands)
    val place = r.category ?: return Advice.Ask(
        if (r.maxPence != null || r.uses.isNotEmpty() || r.brand != null) "What kind of item should that be?" else "I couldn't tell what kind of item that is. Which of these is closest?",
        STARTERS,
    )
    val wanted = (r.uses.flatMap { wanted(it, place.category) } + r.features).distinct()
    val categories = family(place)
    fun find(size: String? = r.sizeKey, max: Int? = r.maxPence, colour: String? = r.colourKey, gender: String? = r.gender, brand: String? = r.brand) =
        categories.flatMap { c ->
            catalogue.search(Query(category = c, gender = gender, sizeKey = size, colourKey = colour, maxPence = max, brands = setOfNotNull(brand), inStockOnly = true, limit = 100_000)).items
        }
    val found = find()
    val understood = describe(r)
    if (found.isEmpty()) {
        val why = when {
            r.maxPence != null && find(max = null).isNotEmpty() -> "Nothing matches under ${pounds(r.maxPence)}. The cheapest that does is ${pounds(find(max = null).minOf { it.fromPence })}."
            r.sizeKey != null && find(size = null).isNotEmpty() -> "Nothing is confirmed in stock in size ${r.sizeKey.uppercase()}. Some shops don't publish stock per size, so try without the size."
            r.colourKey != null && find(colour = null).isNotEmpty() -> "Nothing matches in ${r.colourKey}. Try without the colour."
            r.brand != null && find(brand = null).isNotEmpty() -> "Nothing from ${r.brand} matches. Try without the brand."
            else -> "Nothing in stock matches that in the shops this app covers."
        }
        return Advice.Nothing(understood, why)
    }
    val discount = { s: Summary -> s.wasPence?.let { (it - s.fromPence) * 100 / it } ?: 0 }
    val picks = found.map { s -> s to wanted.filter { it in s.tags } }
        .sortedWith(compareByDescending<Pair<Summary, List<String>>> { it.second.size }.thenByDescending { it.first.shopCount }.thenByDescending { discount(it.first) }.thenBy { it.first.fromPence })
        .take(10).map { (s, matched) ->
            val parts = listOfNotNull(
                matched.takeIf { it.isNotEmpty() }?.joinToString(", ", "matches: "),
                if (s.shopCount > 1) "${s.shopCount} shops" else null,
                discount(s).takeIf { it >= 5 }?.let { "$it% off" },
                r.sizeKey?.let { "in stock in ${it.uppercase()}" },
            )
            Pick(s, matched, parts.joinToString("; ").ifEmpty { "in stock" })
        }
    return Advice.Picks(understood, picks)
}
