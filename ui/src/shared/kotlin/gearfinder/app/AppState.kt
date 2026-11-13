package gearfinder.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gearfinder.catalogue.CatalogueStore
import gearfinder.catalogue.Query
import gearfinder.net.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where the catalogue is fetched from. Set to the GitHub release address once the public repo exists. */
const val DEFAULT_SOURCE = "https://github.com/jersingluc-bot/gear-finder/releases/download/catalogue/"

@Serializable
data class Settings(val source: String = DEFAULT_SOURCE, val saved: List<String> = emptyList(), val mySize: String = "", val myShoeSize: String = "")

enum class Tab(val label: String, val icon: String) { Browse("Browse", "🔍"), Recommend("Recommend", "✨"), Saved("Saved", "♥"), Settings("Settings", "⚙") }

/** Everything the screens share. Lives for as long as the app is open. */
class AppState(val platform: Platform) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(platform.dataDir, "settings.json")

    var settings by mutableStateOf(runCatching { json.decodeFromString(Settings.serializer(), file.readText()) }.getOrDefault(Settings()))
        private set
    var store by mutableStateOf(CatalogueStore(File(platform.dataDir, "catalogue"), settings.source))
        private set
    val http = Http()

    var tab by mutableStateOf(Tab.Browse)
    /** Product pages opened on top of the current tab; the last one is showing. */
    val openProducts = mutableStateListOf<String>()
    var query by mutableStateOf(Query())
    var recommendText by mutableStateOf("")

    fun update(change: (Settings) -> Settings) {
        val old = settings
        settings = change(settings)
        runCatching { file.parentFile?.mkdirs(); file.writeText(json.encodeToString(Settings.serializer(), settings)) }
        if (settings.source != old.source) store = CatalogueStore(File(platform.dataDir, "catalogue"), settings.source)
    }

    fun toggleSaved(id: String) = update { it.copy(saved = if (id in it.saved) it.saved - id else it.saved + id) }
}

fun pounds(pence: Int): String = if (pence % 100 == 0) "£${pence / 100}" else "£%.2f".format(pence / 100.0)
