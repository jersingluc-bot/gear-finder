package gearfinder.recommend

/** Features the app can recognise in a product's name and description, each with the wording shops use for it. */
val FEATURES: Map<String, Regex> = linkedMapOf(
    "waterproof" to "waterproof|gore-?tex|\\bgtx\\b|h2no|pertex shield|hydroshell|dryvent|futurelight|taped seams|drilite|proflex|hydrostatic head",
    "windproof" to "windproof|windstopper|wind[- ]resistant|infinium|pertex quantum",
    "insulated" to "insulated|insulation|primaloft|synthetic fill|thermoball|coreloft|polartec alpha|stratus",
    "down" to "\\bdown\\b|fill power|\\b[6-9]00fp\\b",
    "hood" to "\\bhood(ed|y|ie)?\\b",
    "lightweight" to "lightweight|ultralight|ultra-light|featherlight|minimalist|fast and light",
    "packable" to "packable|packs (down |away )?into|stuff sack|stows into",
    "breathable" to "breathable|pit zips?|venting|ventilation",
    "stretch" to "stretch",
    "hardshell" to "hardshell|hard shell|3[- ]layer|3l\\b",
    "softshell" to "softshell|soft shell",
    "fleece" to "fleece|polartec",
    "merino" to "merino",
    "recycled" to "recycled",
    "winter" to "\\bwinter\\b|4[- ]season|four[- ]season|expedition|mountaineering|alpine|sub-?zero",
    "3-season" to "3[- ]season|three[- ]season",
    "summer" to "\\bsummer\\b|hot weather|warm weather|quick[- ]dry|wicking",
    "freestanding" to "freestanding|free-standing",
    "cushioned" to "cushion(ed|ing)",
    "grippy" to "vibram|megagrip|contagrip|\\blugs?\\b|graphene[- ]grip|sticky rubber",
    "wide fit" to "wide fit|\\bwide\\b",
    "ankle support" to "ankle support|\\bmid\\b|high[- ]cut|\\bboots?\\b",
    "durable" to "durable|hard[- ]wearing|abrasion[- ]resistant|cordura|ripstop",
).mapValues { Regex(it.value) }

/** The recognised features present in a piece of text. */
fun featureTags(text: String): List<String> {
    val t = text.lowercase()
    return FEATURES.filter { it.value.containsMatchIn(t) }.keys.toList()
}
