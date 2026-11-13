package gearfinder.live

import gearfinder.model.Offer
import gearfinder.model.Product
import gearfinder.model.Variant
import gearfinder.model.colourKey
import gearfinder.model.sizeKey
import gearfinder.net.Http
import gearfinder.retail.RetailerAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class Freshness { Checking, Live, NotRechecked }

/** One shop's answer for the chosen size and colour. */
data class OfferRow(
    val retailerId: String, val retailerName: String, val url: String,
    /** Null when the shop does not sell the chosen colour at all. */
    val pricePence: Int?, val wasPence: Int?, val inStock: Boolean,
    /** False when a size was chosen but this shop does not reveal stock per size. */
    val sizeConfirmed: Boolean, val freshness: Freshness,
)

/** What [offer] has for the chosen size and colour (either may be null for "any"). */
fun rowFor(offer: Offer, retailerName: String, sizeKey: String?, colourKey: String?, freshness: Freshness): OfferRow {
    val words = colourKey?.split(' ')
    val variants = offer.variants.filter { words == null || it.colourKey.split(' ').containsAll(words) }
    data class Pick(val v: Variant, val pence: Int, val inStock: Boolean, val confirmed: Boolean)
    val picks = variants.map { v ->
        when {
            sizeKey == null -> Pick(v, v.pricePence, v.inStock, true)
            v.sizes.isEmpty() -> Pick(v, v.pricePence, v.inStock, false)
            else -> v.sizes.firstOrNull { it.key == sizeKey }.let { s -> Pick(v, s?.pricePence ?: v.pricePence, s?.inStock == true, true) }
        }
    }
    val best = picks.sortedWith(compareByDescending<Pick> { it.inStock }.thenByDescending { it.confirmed }.thenBy { it.pence }).firstOrNull()
        ?: return OfferRow(offer.retailerId, retailerName, offer.url, null, null, false, true, freshness)
    return OfferRow(offer.retailerId, retailerName, best.v.url, best.pence, best.v.wasPence?.takeIf { it > best.pence }, best.inStock, best.confirmed, freshness)
}

/** Confirmed in stock first, then in stock but size unconfirmed, then the rest; cheapest first within each. */
fun sortRows(rows: List<OfferRow>): List<OfferRow> =
    rows.sortedWith(compareByDescending<OfferRow> { it.inStock && it.sizeConfirmed }.thenByDescending { it.inStock }.thenBy { it.pricePence ?: Int.MAX_VALUE })

/** Folds a freshly read page into a shop's offer: colours found on the page replace the saved ones from that page. */
internal fun refreshed(offer: Offer, pageUrl: String, fresh: List<Variant>): Offer {
    val old = offer.variants.filter { it.url == pageUrl }
    val renewed = fresh.map { v ->
        val keyed = v.copy(colourKey = colourKey(v.colour), sizes = v.sizes.map { it.copy(key = sizeKey(it.label)) })
        // Shops whose pages do not name the colour keep the colour worked out when the catalogue was built.
        if (keyed.colourKey.isEmpty() && old.size == 1) keyed.copy(colour = old[0].colour, colourKey = old[0].colourKey) else keyed
    }
    return offer.copy(variants = offer.variants.filter { it.url != pageUrl } + renewed)
}

/** One shop's offer as currently known, and whether it has been re-read from the shop yet. */
data class LiveOffer(val offer: Offer, val retailerName: String, val freshness: Freshness)

/** The rows to show for a chosen size and colour. Choosing a different size or colour needs no new requests. */
fun rows(live: List<LiveOffer>, sizeKey: String?, colourKey: String?): List<OfferRow> =
    sortRows(live.map { rowFor(it.offer, it.retailerName, sizeKey, colourKey, it.freshness) })

/**
 * The product's offers: first straight from the catalogue (marked Checking), then updated as each shop's pages are
 * re-read, once per product. A shop counts as Live only when every one of its pages (at most [maxPages]) was read;
 * one that fails or takes longer than [timeoutMs] keeps its catalogue figures, marked NotRechecked, and never
 * holds up the others.
 */
fun liveOffers(product: Product, adapters: List<RetailerAdapter>, http: Http, timeoutMs: Long = 15_000, maxPages: Int = 6): Flow<List<LiveOffer>> = channelFlow {
    val names = adapters.associate { it.id to it.name }
    val state = product.offers.associate { it.retailerId to LiveOffer(it, names[it.retailerId] ?: it.retailerId, Freshness.Checking) }.toMutableMap()
    val lock = Mutex()
    send(state.values.toList())
    for (offer in product.offers) launch {
        val adapter = adapters.firstOrNull { it.id == offer.retailerId }
        val pages = offer.variants.map { it.url }.distinct()
        val renewed = if (adapter == null || pages.size > maxPages) null else withTimeoutOrNull(timeoutMs) {
            var current: Offer? = offer
            for (page in pages) {
                val listing = runCatching { adapter.refresh(http, page) }.getOrNull()
                current = if (listing == null || current == null) null else refreshed(current, page, listing.variants)
                if (current == null) break
            }
            current
        }
        val name = names[offer.retailerId] ?: offer.retailerId
        lock.withLock {
            state[offer.retailerId] = if (renewed == null) LiveOffer(offer, name, Freshness.NotRechecked) else LiveOffer(renewed, name, Freshness.Live)
            send(state.values.toList())
        }
    }
}

/** [liveOffers] reduced to rows for one size and colour. */
fun liveRows(
    product: Product, sizeKey: String?, colourKey: String?, adapters: List<RetailerAdapter>, http: Http, timeoutMs: Long = 15_000,
): Flow<List<OfferRow>> = liveOffers(product, adapters, http, timeoutMs).map { rows(it, sizeKey, colourKey) }
