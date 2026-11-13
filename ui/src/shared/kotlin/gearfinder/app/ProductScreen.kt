package gearfinder.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gearfinder.catalogue.Catalogue
import gearfinder.live.Freshness
import gearfinder.live.OfferRow
import gearfinder.live.LiveOffer
import gearfinder.live.liveOffers
import gearfinder.live.rows
import gearfinder.model.Product
import gearfinder.retail.RETAILERS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/** Shops the app cannot read prices from. Each gets a button that searches the shop's own site in the browser. */
private val LINK_OUTS = listOf(
    "SportPursuit" to "https://www.sportpursuit.com/catalogsearch/result/?q=",
    "Go Outdoors" to "https://www.gooutdoors.co.uk/search?q=",
    "Tiso" to "https://www.tiso.com/search?q=",
    "Blacks" to "https://www.blacks.co.uk/search?q=",
    "Millets" to "https://www.millets.co.uk/search?q=",
    "SportsShoes" to "https://www.sportsshoes.com/products?search=",
    "Decathlon" to "https://www.decathlon.co.uk/search?Ntt=",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProductScreen(app: AppState, catalogue: Catalogue, id: String, onBack: () -> Unit) {
    var product by remember(id) { mutableStateOf<Product?>(null) }
    var missing by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) { withContext(Dispatchers.IO) { runCatching { catalogue.product(id) }.getOrNull() }.let { product = it; missing = it == null } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(product?.let { "${it.brand} ${it.name}" } ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            actions = { TextButton(onClick = { app.toggleSaved(id) }) { Text(if (id in app.settings.saved) "♥ Saved" else "♡ Save") } },
        )
    }) { pad ->
        val p = product
        if (p == null) { Column(Modifier.padding(pad).padding(24.dp)) { Text(if (missing) "This product is no longer in the catalogue." else "Loading…") }; return@Scaffold }

        val colours = remember(p) { p.offers.flatMap { it.variants }.filter { it.colourKey.isNotEmpty() }.groupBy { it.colourKey }.map { (k, vs) -> Triple(k, vs.first().colour ?: k, vs.firstNotNullOfOrNull { it.image }) } }
        val sizes = remember(p) { p.offers.flatMap { o -> o.variants.flatMap { it.sizes } }.groupBy { it.key }.map { (k, ss) -> k to ss.first().label }.filter { it.first.isNotEmpty() } }
        var colour by remember(p) { mutableStateOf<String?>(null) }
        var size by remember(p) { mutableStateOf<String?>(null) }
        // Shops are re-read once per product; picking a size or colour only re-sorts what is already here.
        var live by remember(p) { mutableStateOf<List<LiveOffer>>(emptyList()) }
        LaunchedEffect(p) { liveOffers(p, RETAILERS, app.http).collect { live = it } }
        val rows = remember(live, size, colour) { rows(live, size, colour) }
        val image = colours.firstOrNull { it.first == colour }?.third ?: p.image

        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProductImage(image, Modifier.widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f))
                Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.brand, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleSmall)
                    Text(p.name, style = MaterialTheme.typography.headlineSmall)
                    Text("${p.category}  ·  ${p.gender ?: ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (colours.isNotEmpty()) {
                        Text("Colour", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(colour == null, { colour = null }, label = { Text("Any") })
                            for ((k, name, _) in colours.take(30)) FilterChip(colour == k, { colour = if (colour == k) null else k }, label = { Text(name) })
                        }
                    }
                    if (sizes.isNotEmpty()) {
                        Text("Size", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(size == null, { size = null }, label = { Text("Any") })
                            for ((k, label) in sizes.take(40)) FilterChip(size == k, { size = if (size == k) null else k }, label = { Text(label) })
                        }
                    }
                }
            }

            Text("Where to buy", style = MaterialTheme.typography.titleMedium)
            val checking = rows.count { it.freshness == Freshness.Checking }
            if (checking > 0) Text("Checking $checking shop${if (checking == 1) "" else "s"} for today's price…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((i, r) in rows.withIndex()) OfferCard(r, best = i == 0 && r.inStock && r.sizeConfirmed && r.pricePence != null, sizeChosen = size != null) { app.platform.openUrl(r.url) }

            Text("Not covered by the app", style = MaterialTheme.typography.titleMedium)
            Text("These shops don't allow apps to read their prices. Each button searches the shop's own site in your browser.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val term = URLEncoder.encode("${p.brand} ${p.name.replace(Regex("(?i)\\b(men'?s|women'?s)\\b"), "").trim()}", "UTF-8")
                for ((name, url) in LINK_OUTS) OutlinedButton(onClick = { app.platform.openUrl(url + term) }) { Text("Search $name") }
            }

            if (!p.description.isNullOrBlank()) { Text("About", style = MaterialTheme.typography.titleMedium); Text(p.description!!, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun OfferCard(r: OfferRow, best: Boolean, sizeChosen: Boolean, open: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.retailerName, fontWeight = FontWeight.SemiBold)
                    if (best) Text("CHEAPEST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                val status = when {
                    r.pricePence == null -> "Doesn't sell this colour"
                    !r.inStock -> if (sizeChosen && r.sizeConfirmed) "Out of stock in this size" else "Out of stock"
                    !r.sizeConfirmed -> "In stock, but this shop doesn't publish sizes: check on its site"
                    else -> "In stock"
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (r.inStock && r.sizeConfirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when (r.freshness) { Freshness.Checking -> "checking…"; Freshness.Live -> "price checked just now"; Freshness.NotRechecked -> "not re-checked: price is from the catalogue" },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (r.pricePence != null) Price(r.pricePence!!, r.wasPence)
            if (r.inStock) Button(onClick = open) { Text("Buy") } else OutlinedButton(onClick = open) { Text("View") }
        }
    }
}
