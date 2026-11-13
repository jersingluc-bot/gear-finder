package gearfinder.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import gearfinder.catalogue.Summary

@Composable
fun ProductImage(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize().padding(6.dp))
        else Text("No picture", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun Price(pence: Int, was: Int?, prefix: String = "") {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(prefix + pounds(pence), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
        if (was != null && was > pence) {
            Text("-${(was - pence) * 100 / was}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, softWrap = false)
            Text(pounds(was), textDecoration = TextDecoration.LineThrough, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
fun ProductCard(s: Summary, note: String? = null, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ProductImage(s.image, Modifier.fillMaxWidth().aspectRatio(1f))
            Text(s.brand, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Price(s.fromPence, s.wasPence, prefix = if (s.shopCount > 1) "from " else "")
            Text(
                (if (s.shopCount == 1) "1 shop" else "${s.shopCount} shops") + if (s.inStock) "" else "  ·  out of stock",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A grid of product cards that fits as many columns as the window allows. [header] spans the full width. */
@Composable
fun ProductGrid(items: List<Summary>, modifier: Modifier = Modifier, notes: Map<String, String> = emptyMap(), header: LazyGridScope.() -> Unit = {}, footer: LazyGridScope.() -> Unit = {}, onOpen: (String) -> Unit) {
    LazyVerticalGrid(
        GridCells.Adaptive(165.dp), modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        header()
        items(items, key = { it.id }) { s -> ProductCard(s, notes[s.id]) { onOpen(s.id) } }
        footer()
    }
}

fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) = item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
