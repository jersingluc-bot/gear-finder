package gearfinder.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gearfinder.catalogue.Catalogue
import gearfinder.catalogue.Summary
import gearfinder.recommend.Advice
import gearfinder.recommend.recommend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val EXAMPLES = listOf(
    "Waterproof jacket for Scottish winter under £200, men's medium",
    "Lightweight 2 person tent for backpacking under £400",
    "Women's trail running shoes size 6, cushioned",
    "Warm sleeping bag for winter camping",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecommendScreen(app: AppState, catalogue: Catalogue) {
    var advice by remember { mutableStateOf<Advice?>(null) }
    var asked by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun ask(text: String) {
        app.recommendText = text; asked = text
        scope.launch { advice = withContext(Dispatchers.Default) { runCatching { recommend(text, catalogue) }.getOrElse { Advice.Nothing("", "Something went wrong reading that request. Try wording it differently.") } } }
    }
    val a = advice
    val picks = (a as? Advice.Picks)?.picks ?: emptyList()
    ProductGrid(
        picks.map { it.summary }, Modifier.fillMaxSize(), notes = picks.associate { it.summary.id to it.reason },
        header = {
            fullWidth("ask") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tell me what you're after", style = MaterialTheme.typography.titleLarge)
                    Text("Say the item, what it's for, your size and budget. I'll pick from what's in stock.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(app.recommendText, { app.recommendText = it.take(300) }, Modifier.weight(1f), placeholder = { Text(EXAMPLES[0]) }, maxLines = 3)
                        Button(onClick = { ask(app.recommendText) }) { Text("Suggest") }
                    }
                    if (a == null) {
                        Text("Try one of these", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (e in EXAMPLES) AssistChip(onClick = { ask(e) }, label = { Text(e) }) }
                    }
                    when (a) {
                        is Advice.Ask -> {
                            Text(a.question, style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (o in a.options) AssistChip(onClick = { ask("${o.lowercase()} $asked".trim()) }, label = { Text(o) })
                            }
                        }
                        is Advice.Nothing -> {
                            if (a.understood.isNotEmpty()) Text("Looking for: ${a.understood}", style = MaterialTheme.typography.labelMedium)
                            Text(a.why, style = MaterialTheme.typography.titleMedium)
                        }
                        is Advice.Picks -> {
                            Text("Looking for: ${a.understood}", style = MaterialTheme.typography.labelMedium)
                            Text("${a.picks.size} picks, best match first. This is matching on what shops say about each product, not expert advice.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        null -> {}
                    }
                }
            }
        },
        onOpen = { app.openProducts += it },
    )
}

@Composable
fun SavedScreen(app: AppState, catalogue: Catalogue) {
    val saved = app.settings.saved
    var items by remember { mutableStateOf<List<Summary>>(emptyList()) }
    LaunchedEffect(saved, catalogue) {
        items = withContext(Dispatchers.Default) {
            val byId = catalogue.rows.associateBy { it.id }
            saved.mapNotNull { byId[it] }.map { r -> Summary(r.id, r.brand, r.name, r.category, r.gender, r.image, r.fromPence, r.wasPence, r.shopCount, r.inStock, r.tags) }
        }
    }
    if (saved.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text("Nothing saved yet. Tap ♡ Save on a product to keep it here.") }
    else ProductGrid(items, Modifier.fillMaxSize(), header = {
        fullWidth("h") { Text("Saved (${items.size})" + if (items.size < saved.size) "  ·  ${saved.size - items.size} no longer in the catalogue" else "", style = MaterialTheme.typography.titleMedium) }
    }, onOpen = { app.openProducts += it })
}

@Composable
fun SourceEditor(app: AppState) {
    var text by remember { mutableStateOf(app.settings.source) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Catalogue source (web address or folder)") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { app.update { it.copy(source = text.trim()) } }, enabled = text.trim() != app.settings.source && text.isNotBlank()) { Text("Use this source") }
            if (app.settings.source != DEFAULT_SOURCE) OutlinedButton(onClick = { text = DEFAULT_SOURCE; app.update { it.copy(source = DEFAULT_SOURCE) } }) { Text("Reset") }
        }
    }
}

@Composable
fun SettingsScreen(app: AppState, catalogue: Catalogue, problem: String?) {
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }
    fun days(t: Long) = if (t <= 0) "never" else ((now - t) / 86_400_000).let { if (it <= 0L) "today" else if (it == 1L) "yesterday" else "$it days ago" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Catalogue", style = MaterialTheme.typography.titleLarge)
        Text("${catalogue.meta.products} products  ·  updated ${days(catalogue.meta.builtAt)}")
        if (problem != null) Text(problem, color = MaterialTheme.colorScheme.tertiary)
        Button(onClick = { scope.launch { app.store.refresh() } }) { Text("Check for a newer catalogue") }
        Text("Shops", style = MaterialTheme.typography.titleMedium)
        for (r in catalogue.meta.retailers.sortedBy { it.name }) {
            val stale = r.lastSuccess <= 0 || now - r.lastSuccess > 4 * 86_400_000L
            Text("${r.name}: ${r.items} products, last read ${days(r.lastSuccess)}", color = if (stale) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
        }
        Text("Prices on a product page are re-checked live when you open it. Nearby in-store stock is not available: no shop publishes it in a form the app may read.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Advanced", style = MaterialTheme.typography.titleMedium)
        SourceEditor(app)
    }
}
