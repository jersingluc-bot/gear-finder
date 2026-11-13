package gearfinder.retail

import gearfinder.model.Listing
import kotlinx.serialization.json.JsonElement

private val LD_BLOCK = Regex("""<script[^>]*application/ld\+json[^>]*>([\s\S]*?)</script>""")

private fun typesOf(d: JsonElement?): List<String> = d.at("@type").list().mapNotNull { it.text()?.replace(Regex("^https?://schema\\.org/"), "") }

internal fun jsonLdProducts(html: String): List<JsonElement> = LD_BLOCK.findAll(html).flatMap { m ->
    val data = parseJson(m.groupValues[1].trim()) ?: return@flatMap emptyList()
    (data.at("@graph")?.list() ?: data.list()).filter { d -> typesOf(d).any { it == "Product" || it == "ProductGroup" } }
}.toList()

private class Row(val node: JsonElement, val pence: Int?, val inStock: Boolean)

private fun firstOffer(node: JsonElement): Row? {
    val o = (node.at("offers") ?: node.at("Offers")).list().firstOrNull() ?: return null
    val price = pence((o.at("price") ?: o.at("Price") ?: o.at("lowPrice")).number())
    val avail = (o.at("availability") ?: o.at("Availability")).text() ?: ""
    return Row(node, price, Regex("InStock|LimitedAvailability|PreOrder", RegexOption.IGNORE_CASE).containsMatchIn(avail))
}

private fun imageOf(node: JsonElement?): String? = (node.at("image") ?: node.at("Image")).list().firstNotNullOfOrNull { it.text() ?: it.at("url").text() }

internal fun breadcrumbs(html: String): List<String> =
    Regex(""""@type"\s*:\s*"ListItem"[^}]*?"name"\s*:\s*"([^"]+)"""").findAll(html).map { decode(it.groupValues[1]) }.toList()

/** Reads a product page that carries standard schema.org Product (or ProductGroup) data. */
fun parseJsonLdProduct(html: String, url: String, retailerId: String): Listing? {
    val prods = jsonLdProducts(html)
    val p = prods.firstOrNull { "ProductGroup" in typesOf(it) } ?: prods.firstOrNull() ?: return null
    val nodes = p.at("hasVariant").list().ifEmpty { listOf(p) }
    val rows = nodes.mapNotNull(::firstOffer)
    if (rows.isEmpty()) return null
    val pageImage = imageOf(p) ?: meta(html, "og:image")

    val variants = rows.groupBy { (it.node.at("color") ?: p.at("color")).text() }.mapNotNull { (colour, rs) ->
        val priced = rs.filter { it.pence != null }
        if (priced.isEmpty()) return@mapNotNull null
        val live = priced.filter { it.inStock }
        val best = live.ifEmpty { priced }.minBy { it.pence!! }
        val sizes = rs.mapNotNull { r -> r.node.at("size").text()?.takeIf { it.isNotBlank() }?.let { size(it, r.inStock && r.pence != null, r.pence) } }
        variant(colour, rs.firstNotNullOfOrNull { imageOf(it.node) } ?: pageImage, url, sizes, best.pence, null, live.isNotEmpty())
    }
    if (variants.isEmpty()) return null

    val brand = (p.at("brand") ?: p.at("Brand")).let { it.at("name").text() ?: it.text() }
    val gender = when (p.at("audience").at("suggestedGender").text()?.lowercase()) {
        "men", "male" -> "Men"; "women", "female" -> "Women"; "unisex" -> "Unisex"; else -> null
    }
    val category = (p.at("category") ?: p.at("Category")).list().mapNotNull { it.text() }.ifEmpty { breadcrumbs(html).drop(1).dropLast(1) }.joinToString(" ")
    return Listing(
        retailerId = retailerId, title = decode((p.at("name") ?: p.at("Name")).text()), brand = decode(brand),
        categoryHint = category.ifBlank { null }, gender = gender, url = url,
        description = plainText((p.at("description") ?: p.at("Description")).text()), variants = variants,
    ).takeIf { it.title.isNotEmpty() }
}
