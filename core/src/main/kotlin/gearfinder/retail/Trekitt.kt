package gearfinder.retail

import gearfinder.model.Listing

private fun priceIn(html: String, id: String): Int? =
    Regex("""id="$id"[^>]*>\s*(?:<[^>]+>\s*)*(?:£|&#163;|&pound;)\s?([\d,.]+)""").find(html)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull().let(::pence)

/**
 * Trekitt, The Climbers Shop / Joe Brown and Taunton Leisure share one platform. It has no product markup and loads
 * sizes separately, so this reads the price, title and image only; size-level stock is unknown.
 */
fun parseTrekitt(html: String, url: String, retailerId: String): Listing? {
    val price = priceIn(html, "productPrice") ?: return null
    val title = meta(html, "og:title") ?: decode(Regex("""<h1[^>]*>([\s\S]*?)</h1>""").find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), " "))
    if (title.isEmpty()) return null
    val at = html.indexOf("id=\"productPrice\"")
    val zone = html.substring(maxOf(0, at - 2000), minOf(html.length, at + 3000))
    val inStock = !Regex("out of stock|sold out|currently unavailable", RegexOption.IGNORE_CASE).containsMatchIn(zone)
    val v = variant(null, meta(html, "og:image"), url, emptyList(), price, priceIn(html, "productPriceWas"), inStock) ?: return null
    // The address path carries the shop's own category, e.g. /outdoor-jackets/waterproof-jackets/...
    val hint = url.substringAfter("://").substringAfter('/').substringBeforeLast('/').replace(Regex("[-/]+"), " ")
    return Listing(retailerId, title, brand = "", categoryHint = hint.ifBlank { null }, url = url, description = plainText(meta(html, "og:description")), variants = listOf(v))
}
