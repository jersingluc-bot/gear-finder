package gearfinder.model

import kotlinx.serialization.Serializable

/** One size of one colour at one shop. Prices are pence. */
@Serializable
data class SizeStock(val label: String, val key: String, val inStock: Boolean, val pricePence: Int? = null)

@Serializable
data class Variant(
    val colour: String?,
    val colourKey: String,
    val image: String?,
    val pricePence: Int,
    val wasPence: Int? = null,
    val url: String,
    /** Empty when the shop's page does not reveal stock per size. */
    val sizes: List<SizeStock>,
    val inStock: Boolean,
)

/** One retailer's product page, as scraped. */
@Serializable
data class Listing(
    val retailerId: String,
    val title: String,
    val brand: String,
    val categoryHint: String? = null,
    val gender: String? = null,
    val url: String,
    val description: String? = null,
    val variants: List<Variant>,
)

@Serializable
data class Offer(val retailerId: String, val url: String, val variants: List<Variant>)

/** The same item across shops. */
@Serializable
data class Product(
    val id: String,
    val brand: String,
    val name: String,
    val group: String,
    val category: String,
    val gender: String? = null,
    val description: String? = null,
    val image: String? = null,
    val offers: List<Offer>,
)
