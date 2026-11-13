package gearfinder.net

/** The rules a site's robots.txt sets for every crawler ("User-agent: *"). Longest matching rule wins; ties allow. */
class Robots(text: String) {
    private class Rule(val allow: Boolean, val pattern: Regex, val length: Int)
    private val rules = mutableListOf<Rule>()

    init {
        var applies = false
        var lastWasAgent = false
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            val key = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "user-agent" -> { applies = if (lastWasAgent) applies || value == "*" else value == "*"; lastWasAgent = true }
                "allow", "disallow" -> {
                    lastWasAgent = false
                    if (applies && value.isNotEmpty()) rules += Rule(key == "allow", toRegex(value), value.length)
                }
                else -> if (line.isNotEmpty()) lastWasAgent = false
            }
        }
    }

    private fun toRegex(p: String): Regex {
        val anchored = p.endsWith("$")
        val body = p.removeSuffix("$").split("*").joinToString(".*") { Regex.escape(it) }
        return Regex("^$body" + if (anchored) "$" else "")
    }

    fun allows(path: String): Boolean {
        val hit = rules.filter { it.pattern.containsMatchIn(path) }.maxWithOrNull(compareBy<Rule> { it.length }.thenBy { it.allow })
        return hit?.allow ?: true
    }
}
