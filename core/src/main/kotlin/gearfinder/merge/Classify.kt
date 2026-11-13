package gearfinder.merge

data class Placement(val group: String, val category: String)

private class Rule(val place: Placement, val regex: Regex)

private fun resource(name: String): List<String> =
    Placement::class.java.getResourceAsStream("/gearfinder/$name")!!.bufferedReader().readLines().filter { it.isNotBlank() }

/** Title keywords to a place in the catalogue; the first matching rule wins. Kept as data in resources/gearfinder/rules.tsv. */
private val RULES: List<Rule> = resource("rules.tsv").map { line ->
    val (group, category, _, source) = line.split("\t", limit = 4)
    Rule(Placement(group, category), Regex(source))
}

val KNOWN_BRANDS: List<String> = resource("brands.txt")
private val BRAND_DEFAULTS: Map<String, Placement> = resource("brand-defaults.tsv").associate { val (b, g, c) = it.split("\t"); b to Placement(g, c) }

/** Out of scope: the app covers hiking, mountaineering, climbing, camping, running and related gear only. */
val EXCLUDED_GROUPS = setOf("Fishing", "Cycling", "Caravan & Motorhome", "Home & Leisure", "Swimwear", "Pet Supplies")
private val SWIM_BRANDS = setOf("arena", "maaji", "watercult", "funkita", "speedo", "zoggs", "tyr")
private val PET_BRANDS = setOf("petface")
private val PUBLISHERS = setOf("ordnance survey", "cicerone", "rockfax", "harvey maps", "harvey", "vertebrate publishing", "trailblazer", "collins", "freytag & berndt", "knife edge outdoor", "books")
private val OTHER = Placement("Other", "Other")

/** What a shop's own category wording means in this catalogue. Kept as data in resources/gearfinder/hints.tsv. */
private val HINTS: List<Rule> = resource("hints.tsv").map { line ->
    val (group, category, source) = line.split("\t", limit = 3)
    Rule(Placement(group, category), Regex(source))
}

/** Catch-all categories: a keyword put the product here, but the shop's own category can say something more exact. */
private val GENERIC = setOf("Jackets & Gilets", "Shoes & Trainers", "Tops & T-Shirts", "Bags & Luggage", "Ski Poles & Accessories", "Rucksacks")

/** The catalogue's shelves, in the order menus show them. */
val TAXONOMY: Map<String, List<String>> = linkedMapOf(
    "Clothing" to listOf("Waterproof Jackets", "Insulated & Down Jackets", "Softshell & Windproof", "Fleece & Midlayers", "Jackets & Gilets", "Base Layers",
        "Tops & T-Shirts", "Trousers & Leggings", "Shorts & Skirts", "Gloves & Mitts", "Hats & Neckwear", "Belts & Accessories"),
    "Footwear" to listOf("Walking & Hiking Boots", "Trail Running Shoes", "Shoes & Trainers", "Sandals & Slippers", "Socks", "Insoles, Laces & Gaiters"),
    "Packs & Bags" to listOf("Rucksacks", "Dry Bags & Stuff Sacks", "Bags & Luggage"),
    "Camping" to listOf("Tents", "Sleeping Bags", "Sleeping Mats & Pillows", "Stoves & Cookware", "Food & Nutrition", "Bottles & Water Filters", "Lighting",
        "Furniture & Hammocks", "Trekking Poles"),
    "Climbing" to listOf("Climbing Shoes & Chalk", "Harnesses", "Helmets", "Ropes & Slings", "Protection & Hardware", "Ice & Mountaineering"),
    "Snow Sports" to listOf("Skis & Snowboards", "Ski & Snowboard Boots", "Ski & Snow Clothing", "Goggles & Helmets", "Avalanche Safety", "Ski Poles & Accessories"),
    "Navigation & Tech" to listOf("Watches & GPS", "Maps & Books", "Power & Solar", "Electronics & Optics"),
    "Accessories" to listOf("Sunglasses & Eyewear", "Knives & Tools", "First Aid & Safety", "Care & Repair", "Travel & Towels"),
    "Other" to listOf("Other"),
)

/** Every placement the rules can produce, for checking that [TAXONOMY] lists them all. */
internal val RULE_PLACEMENTS: Set<Placement> get() = (RULES + HINTS).map { it.place }.toSet()

/** Where a product belongs, or null when it is outside the app's scope. */
fun classify(title: String, categoryHint: String? = null, brand: String = ""): Placement? {
    val place = place(title, categoryHint, brand.lowercase())
    return place.takeUnless { it.group in EXCLUDED_GROUPS }
}

private fun place(title: String, hint: String?, brand: String): Placement {
    if (brand in SWIM_BRANDS) return Placement("Swimwear", "Swimwear")
    if (brand in PET_BRANDS) return Placement("Pet Supplies", "Pet Supplies")
    if (brand in PUBLISHERS) return Placement("Navigation & Tech", "Maps & Books")
    val byBrand = BRAND_DEFAULTS[brand]
    if (byBrand != null && byBrand.group in EXCLUDED_GROUPS) return byBrand

    val t = " $title ".lowercase()
    val h = hint?.takeIf { it.isNotBlank() }?.let { " $it ".lowercase() }
    val inScope = { r: Rule -> r.place.group !in EXCLUDED_GROUPS }
    // What the shop itself files it under: its exact wording first, then our keywords applied to that wording.
    val fromHint = h?.let { text -> HINTS.firstOrNull { it.regex.containsMatchIn(text) }?.place }
    if (fromHint != null && fromHint.group in EXCLUDED_GROUPS) return fromHint
    val hintByKeyword = h?.let { text -> RULES.firstOrNull { inScope(it) && it.regex.containsMatchIn(text) }?.place }

    var byTitle = RULES.firstOrNull { it.regex.containsMatchIn(t) }?.place
    // An out-of-scope keyword ("weight", "shot", "cat") in a product the shop files under outdoor gear is a coincidence.
    if (byTitle != null && byTitle.group in EXCLUDED_GROUPS && (fromHint ?: hintByKeyword) != null) {
        byTitle = RULES.firstOrNull { inScope(it) && it.regex.containsMatchIn(t) }?.place
    }
    if (fromHint != null) {
        // The title wins only when it is more exact within the same part of the shop (shorts filed under "legwear").
        return if (byTitle != null && byTitle.group == fromHint.group && byTitle.category !in GENERIC) byTitle else fromHint
    }
    return byTitle ?: hintByKeyword ?: byBrand ?: OTHER
}
