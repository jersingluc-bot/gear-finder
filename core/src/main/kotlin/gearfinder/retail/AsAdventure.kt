package gearfinder.retail

import gearfinder.model.Listing

private val SCENE = Regex("""data-(?:hono|hypernova)-key="AEMScenes_ProductDetail_v2"[^>]*><!--([\s\S]*?)--></script>""")
private const val IMAGES = "https://productimage001.snowandrock.com/productimages/370x370/"

/** Cotswold Outdoor, Snow+Rock and Runners Need share one platform: colours and sizes are embedded in the page as JSON. */
fun parseAsAdventure(html: String, url: String, retailerId: String): Listing? {
    val data = parseJson(SCENE.find(html)?.groupValues?.get(1) ?: return null) ?: return null
    val p = data.at("store").at("product")
    val variants = p.at("productColorVariations").list().mapNotNull { c ->
        if (c.at("visible").text() == "false" || c.at("active").text() == "false") return@mapNotNull null
        val sell = pence(c.at("prices").at("SELL").at("original").number()) ?: return@mapNotNull null
        val rrp = pence(c.at("prices").at("RRP").at("original").number())
        val sizes = c.at("sizes").list().filter { it.at("active").text() != "false" }.mapNotNull { s ->
            val label = (s.at("fullCommercialSize") ?: s.at("originalSize") ?: s.at("code")).text() ?: return@mapNotNull null
            size(label, (s.at("availability").number() ?: 0.0) > 0, pence(s.at("prices").at("SELL").at("original").number()) ?: sell)
        }
        val image = c.at("images").list().minByOrNull { it.at("sortOrder").number() ?: 0.0 }?.at("picture").text()
        val colour = c.at("translations").at("description").text() ?: c.at("color").text()
        variant(colour, image?.let { IMAGES + it }, url, sizes, sell, rrp, (c.at("availability").number() ?: 0.0) > 0 || sizes.any { it.inStock })
    }
    if (variants.isEmpty()) return null
    val ld = data.at("props").at("productLD")
    val props = p.at("productTranslatedProperties")
    val levels = data.at("props").at("categoryLevels").list().mapNotNull { (it.at("name") ?: it.at("title") ?: it.at("description")).text() }.joinToString(" ")
    return Listing(
        retailerId = retailerId, title = decode(props.at("title").text() ?: ld.at("name").text()), brand = decode(ld.at("brand").at("name").text() ?: p.at("brand").at("description").text()),
        categoryHint = levels.ifBlank { null }, url = url,
        description = plainText(props.at("information").text() ?: props.at("technicalDescription").text()), variants = variants,
    ).takeIf { it.title.isNotEmpty() }
}
