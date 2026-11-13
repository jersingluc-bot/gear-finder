package gearfinder.retail

import gearfinder.model.SizeStock
import gearfinder.model.Variant
import gearfinder.model.colourKey
import gearfinder.model.sizeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import kotlin.math.roundToInt

internal val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseJson(s: String): JsonElement? = try { LENIENT.parseToJsonElement(s) } catch (e: Exception) { null }
internal fun JsonElement?.at(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }
internal fun JsonElement?.list(): List<JsonElement> = when (this) { null, is JsonNull -> emptyList(); is JsonArray -> this.toList(); else -> listOf(this) }
internal fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
internal fun JsonElement?.number(): Double? = text()?.replace(",", "")?.trim()?.toDoubleOrNull()

internal fun pence(pounds: Double?): Int? = pounds?.takeIf { it > 0 }?.let { (it * 100).roundToInt() }
internal fun https(u: String?): String? = u?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("//")) "https:$it" else it.replace("http://", "https://") }
internal fun decode(s: String?): String = Parser.unescapeEntities(s ?: "", false).replace(Regex("\\s+"), " ").trim()

internal fun plainText(html: String?, max: Int = 1200): String? {
    val t = Jsoup.parse(html ?: "").text().trim()
    if (t.isEmpty()) return null
    return if (t.length > max) t.substring(0, t.lastIndexOf(' ', max).takeIf { it > 0 } ?: max) + "…" else t
}

internal fun size(label: String, inStock: Boolean, pricePence: Int?): SizeStock = SizeStock(label.trim(), sizeKey(label), inStock, pricePence)

/** Builds a colour's offer. The headline price is the cheapest size that is in stock, or the cheapest size at all. */
internal fun variant(
    colour: String?, image: String?, url: String, sizes: List<SizeStock>,
    pricePence: Int? = null, wasPence: Int? = null, inStock: Boolean? = null,
): Variant? {
    val live = sizes.filter { it.inStock && it.pricePence != null }
    val price = pricePence ?: live.ifEmpty { sizes.filter { it.pricePence != null } }.minOfOrNull { it.pricePence!! } ?: return null
    val key = colourKey(colour)
    return Variant(
        colour = colour?.trim()?.takeIf { key.isNotEmpty() }, colourKey = key, image = https(image), pricePence = price,
        wasPence = wasPence?.takeIf { it > price }, url = url, sizes = sizes, inStock = inStock ?: sizes.any { it.inStock },
    )
}

internal fun meta(html: String, property: String): String? =
    Regex("""property="${Regex.escape(property)}" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.let(::decode)?.takeIf { it.isNotEmpty() }
