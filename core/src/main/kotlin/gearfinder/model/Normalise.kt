package gearfinder.model

private val SIZE_WORDS = mapOf(
    "small" to "s", "medium" to "m", "large" to "l",
    "x-large" to "xl", "xlarge" to "xl", "extra large" to "xl", "x large" to "xl",
    "xx-large" to "xxl", "xxlarge" to "xxl", "2xl" to "xxl", "3xl" to "xxxl", "4xl" to "xxxxl",
    "x-small" to "xs", "xsmall" to "xs", "extra small" to "xs", "x small" to "xs", "2xs" to "xxs",
    "one size" to "os", "onesize" to "os", "one size fits all" to "os", "o/s" to "os", "osfa" to "os",
)
private val SIZE_NOISE = Regex("""\b(uk|size|mens|womens|men's|women's|men|women|unisex|adults?)\b""")
private val UK_IN_PAIR = Regex("""(?=.*\b(?:eu|eur|us)\b).*\buk\s*(\d+(?:\.\d)?)""")
private val EU = Regex("""\b(eu|eur|euro)\b""")

/**
 * A comparable key for a size label, so "Medium", "Mens M" and "M" match across shops.
 * UK is the default system and is dropped; EU sizes keep an "eu" prefix so EU 42 never equals UK 42.
 */
fun sizeKey(label: String): String {
    // "UK 7 | EU 40" or "EU 40 (UK 7)": the UK size is the one to compare on.
    UK_IN_PAIR.find(label.lowercase())?.let { return it.groupValues[1].replace(" ", "") }
    var t = label.lowercase().replace("½", ".5").replace(Regex("""\s+1/2\b"""), ".5")
    t = t.replace(Regex("[()]"), " ").trim().replace(Regex("""\s+"""), " ")
    SIZE_WORDS[t]?.let { return it }
    val eu = EU.containsMatchIn(t)
    t = EU.replace(t, " ")
    t = SIZE_NOISE.replace(t, " ").trim().replace(Regex("""\s+"""), " ")
    val core = SIZE_WORDS[t] ?: t.replace(" ", "")
    return if (eu && core.isNotEmpty()) "eu$core" else core
}

private val NO_COLOUR = Regex("""^(no colou?rs?|n/a|none|default|default title|one colou?r|not applicable|assorted|-+)$""")
private val COLOUR_NOISE = setOf("the", "tnf", "npf", "colour", "color", "and")

/** A comparable key for a colour name: word order and separators do not matter. Blank when there is no real colour. */
fun colourKey(label: String?): String {
    val t = (label ?: "").trim().lowercase()
    if (t.isEmpty() || NO_COLOUR.matches(t)) return ""
    return t.replace("&", " and ").split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() && it !in COLOUR_NOISE }.sorted().joinToString(" ")
}
