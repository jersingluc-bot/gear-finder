package gearfinder.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import gearfinder.catalogue.Catalogue
import gearfinder.catalogue.CatalogueState
import kotlinx.coroutines.launch
import java.io.File

/** What the shared UI needs from the device it runs on. */
interface Platform {
    val dataDir: File
    fun openUrl(url: String)
}

private val Light = lightColorScheme(primary = Color(0xFF2E6B4F), secondary = Color(0xFF8A5A2B), tertiary = Color(0xFFB3261E))
private val Dark = darkColorScheme(primary = Color(0xFF8FD6B0), secondary = Color(0xFFE0B88A), tertiary = Color(0xFFFFB4AB))

@Composable
fun App(platform: Platform) {
    val app = remember { AppState(platform) }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val store = app.store
            val state by store.state.collectAsState()
            val scope = rememberCoroutineScope()
            LaunchedEffect(store) { store.refresh() }
            when (val s = state) {
                is CatalogueState.Loading -> Centered { CircularProgressIndicator(); Text("Opening the catalogue…") }
                is CatalogueState.Downloading -> Centered {
                    Text("Downloading the catalogue", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.widthIn(max = 320.dp))
                    Text("${s.percent}%  ·  this happens once, then only when prices are refreshed")
                }
                is CatalogueState.Unavailable -> Centered {
                    Text("No catalogue yet", style = MaterialTheme.typography.titleLarge)
                    Text(s.reason, textAlign = TextAlign.Center)
                    Text("Check your connection, or the catalogue source in Settings.", textAlign = TextAlign.Center)
                    Button(onClick = { scope.launch { store.refresh() } }) { Text("Try again") }
                    SourceEditor(app)
                }
                is CatalogueState.Ready -> Main(app, s.catalogue, s.problem)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

@Composable
private fun Main(app: AppState, catalogue: Catalogue, problem: String?) {
    val open = app.openProducts.lastOrNull()
    if (open != null) {
        ProductScreen(app, catalogue, open, onBack = { app.openProducts.removeAt(app.openProducts.lastIndex) })
        return
    }
    Scaffold(bottomBar = {
        NavigationBar {
            for (t in Tab.entries) NavigationBarItem(
                selected = app.tab == t, onClick = { app.tab = t },
                icon = { Text(t.icon, style = MaterialTheme.typography.titleLarge) }, label = { Text(t.label) },
            )
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (app.tab) {
                Tab.Browse -> BrowseScreen(app, catalogue)
                Tab.Recommend -> RecommendScreen(app, catalogue)
                Tab.Saved -> SavedScreen(app, catalogue)
                Tab.Settings -> SettingsScreen(app, catalogue, problem)
            }
        }
    }
}
