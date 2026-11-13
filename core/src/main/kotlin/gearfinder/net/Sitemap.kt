package gearfinder.net

private val LOC = Regex("""<loc>\s*([^<\s]+)\s*</loc>""")

private fun locs(xml: String): List<String> = LOC.findAll(xml).map { it.groupValues[1].replace("&amp;", "&") }.toList()

/**
 * Every page address under a sitemap. Follows sitemap indexes (three levels at most), reading only children
 * that match [childFilter], and keeps only addresses that match [urlFilter].
 */
suspend fun sitemapUrls(http: Http, root: String, childFilter: Regex? = null, urlFilter: Regex? = null): List<String> {
    val out = LinkedHashSet<String>()
    suspend fun walk(url: String, depth: Int) {
        val xml = (http.get(url) as? Fetch.Ok)?.body ?: return
        val items = locs(xml)
        if (xml.contains("<sitemapindex") && depth < 3) {
            for (child in items) if (childFilter == null || childFilter.containsMatchIn(child)) walk(child, depth + 1)
        } else {
            for (u in items) if (urlFilter == null || urlFilter.containsMatchIn(u)) out += u
        }
    }
    walk(root, 0)
    return out.toList()
}
