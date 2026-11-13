package gearfinder.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import gearfinder.catalogue.Catalogue
import gearfinder.catalogue.Facets
import gearfinder.catalogue.Query
import gearfinder.catalogue.Results
import gearfinder.catalogue.Sort
import gearfinder.catalogue.facets
import gearfinder.catalogue.search
import gearfinder.model.colourKey
import gearfinder.model.sizeKey
import gearfinder.retail.RETAILERS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(app: AppState, catalogue: Catalogue) {
    val q = app.query
    var results by remember { mutableStateOf<Results?>(null) }
    var facets by remember { mutableStateOf<Facets?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    LaunchedEffect(catalogue, q) {
        delay(150) // let typing settle
        results = withContext(Dispatchers.Default) { catalogue.search(q) }
    }
    LaunchedEffect(catalogue, q.group, q.category) {
        facets = withContext(Dispatchers.Default) { catalogue.facets(q.group, q.category) }
    }
    val groups = facets?.groups ?: emptyMap()
    val active = listOfNotNull(q.sizeKey, q.colourKey, q.gender, q.maxPence, q.inStockOnly.takeIf { it }, q.onSaleOnly.takeIf { it }).size + q.retailers.size + q.brands.size

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(q.text, { app.query = q.copy(text = it, limit = 60) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search ${catalogue.meta.products} products") })
            OutlinedButton(onClick = { showFilters = true }) { Text(if (active > 0) "Filters ($active)" else "Filters") }
            Box {
                TextButton(onClick = { sortOpen = true }) { Text(sortName(q.sort)) }
                DropdownMenu(sortOpen, { sortOpen = false }) {
                    for (s in Sort.entries) DropdownMenuItem(text = { Text(sortName(s)) }, onClick = { app.query = q.copy(sort = s); sortOpen = false })
                }
            }
        }
        ChipRow {
            FilterChip(q.group == null, { app.query = q.copy(group = null, category = null, limit = 60) }, label = { Text("All") })
            for (g in groups.keys) FilterChip(q.group == g, { app.query = q.copy(group = if (q.group == g) null else g, category = null, limit = 60) }, label = { Text(g) })
        }
        if (q.group != null) ChipRow {
            for (c in groups[q.group] ?: emptyList()) FilterChip(q.category == c, { app.query = q.copy(category = if (q.category == c) null else c, limit = 60) }, label = { Text(c) })
        }
        val r = results
        when {
            r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Searching…") }
            r.total == 0 -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nothing matches", style = MaterialTheme.typography.titleMedium)
                    if (q.sizeKey != null) Text("A size filter only counts shops that publish stock per size.")
                    if (active > 0) TextButton(onClick = { app.query = Query(text = q.text, group = q.group, category = q.category, sort = q.sort) }) { Text("Clear filters") }
                }
            }
            else -> ProductGrid(
                r.items, Modifier.fillMaxSize(),
                header = { fullWidth("count") { Text("${r.total} products", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                footer = { if (r.items.size < r.total) fullWidth("more") { OutlinedButton(onClick = { app.query = q.copy(limit = q.limit + 120) }, modifier = Modifier.fillMaxWidth()) { Text("Show more (${r.total - r.items.size} left)") } } },
                onOpen = { app.openProducts += it },
            )
        }
    }
    if (showFilters) ModalBottomSheet(onDismissRequest = { showFilters = false }) { FilterSheet(app, facets) { showFilters = false } }
}

private fun sortName(s: Sort) = when (s) { Sort.Relevance -> "Best match"; Sort.PriceLow -> "Cheapest"; Sort.PriceHigh -> "Dearest"; Sort.Discount -> "Biggest saving" }

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(app: AppState, facets: Facets?, close: () -> Unit) {
    val q = app.query
    fun set(n: Query) { app.query = n.copy(limit = 60) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Filters", style = MaterialTheme.typography.titleLarge)
            Row { TextButton(onClick = { set(Query(text = q.text, group = q.group, category = q.category, sort = q.sort)) }) { Text("Clear") }; TextButton(onClick = close) { Text("Done") } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(q.inStockOnly, { set(q.copy(inStockOnly = it)) }); Text("In stock only")
            Switch(q.onSaleOnly, { set(q.copy(onSaleOnly = it)) }); Text("On sale")
        }
        Text("For", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (g in listOf("Men", "Women", "Kids")) FilterChip(q.gender == g, { set(q.copy(gender = if (q.gender == g) null else g)) }, label = { Text(g) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                q.maxPence?.let { (it / 100).toString() } ?: "", { v -> set(q.copy(maxPence = v.filter(Char::isDigit).take(5).toIntOrNull()?.times(100))) },
                Modifier.width(150.dp), singleLine = true, label = { Text("Max price £") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            var typed by remember { mutableStateOf(q.sizeKey ?: "") }
            OutlinedTextField(typed, { typed = it; set(q.copy(sizeKey = sizeKey(it).ifEmpty { null })) }, Modifier.width(150.dp), singleLine = true, label = { Text("Size (e.g. M, 9.5)") })
        }
        if (facets != null && facets.sizes.isNotEmpty()) {
            Text("Common sizes here", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in facets.sizes.take(24)) FilterChip(q.sizeKey == s, { set(q.copy(sizeKey = if (q.sizeKey == s) null else s)) }, label = { Text(s.uppercase()) })
            }
            Text("A size filter only counts shops that publish stock per size.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (facets != null && facets.colours.isNotEmpty()) {
            Text("Colour", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in facets.colours.take(20)) FilterChip(q.colourKey == colourKey(c), { set(q.copy(colourKey = if (q.colourKey == colourKey(c)) null else colourKey(c))) }, label = { Text(c.replaceFirstChar(Char::uppercase)) })
            }
        }
        Text("Shops", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in RETAILERS) FilterChip(r.id in q.retailers, { set(q.copy(retailers = if (r.id in q.retailers) q.retailers - r.id else q.retailers + r.id)) }, label = { Text(r.name) })
        }
        if (facets != null) {
            var find by remember { mutableStateOf("") }
            Text("Brand", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(find, { find = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Type to find a brand") })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val shown = (q.brands.toList() + facets.brands.filter { find.isNotBlank() && it.contains(find.trim(), ignoreCase = true) }).distinct().take(24)
                for (b in shown) FilterChip(b in q.brands, { set(q.copy(brands = if (b in q.brands) q.brands - b else q.brands + b)) }, label = { Text(b) })
            }
        }
    }
}
