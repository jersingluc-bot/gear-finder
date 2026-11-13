package gearfinder.catalogue

import gearfinder.net.Http
import gearfinder.net.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

sealed interface CatalogueState {
    data object Loading : CatalogueState
    data class Downloading(val percent: Int) : CatalogueState
    /** A catalogue is in use. [problem] says why the last update attempt did not happen, if it did not. */
    data class Ready(val catalogue: Catalogue, val problem: String? = null) : CatalogueState
    /** Nothing to show yet: first run and the catalogue could not be fetched. */
    data class Unavailable(val reason: String) : CatalogueState
}

private const val FILES_INDEX = "index.jsonl.gz"
private const val FILES_BODY = "products.jsonl.gz"

/**
 * Keeps the newest catalogue on the device. [source] is a web address ending in "/" or a local folder.
 * A new catalogue is downloaded beside the old one, opened to prove it is whole, and only then switched to,
 * so a failed or interrupted download never leaves the app without data.
 */
class CatalogueStore(private val dir: File, private val source: String, private val client: OkHttpClient = Http.defaultClient()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _state = MutableStateFlow<CatalogueState>(CatalogueState.Loading)
    val state: StateFlow<CatalogueState> = _state

    suspend fun refresh() = lock.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val local = (_state.value as? CatalogueState.Ready)?.catalogue ?: openCurrent()
            if (local != null && _state.value !is CatalogueState.Ready) _state.value = CatalogueState.Ready(local)

            fun fail(why: String) { _state.value = if (local != null) CatalogueState.Ready(local, why) else CatalogueState.Unavailable(why) }

            val metaText = try { fetchText("meta.json") } catch (e: Exception) { return@withContext fail("Could not check for a newer catalogue (${e.message ?: "no connection"}).") }
            val remote = try { json.decodeFromString(Meta.serializer(), metaText) } catch (e: Exception) { return@withContext fail("The catalogue source sent something unreadable.") }
            if (local != null && remote.builtAt <= local.meta.builtAt) { _state.value = CatalogueState.Ready(local); return@withContext }

            val part = File(dir, "cat-${remote.builtAt}.part").apply { deleteRecursively(); mkdirs() }
            try {
                if (local == null) _state.value = CatalogueState.Downloading(0)
                fetchFile(FILES_INDEX, File(part, FILES_INDEX)) { if (local == null) _state.value = CatalogueState.Downloading(it / 10) }
                fetchFile(FILES_BODY, File(part, FILES_BODY)) { if (local == null) _state.value = CatalogueState.Downloading(10 + it * 9 / 10) }
                File(part, "meta.json").writeText(metaText)
                Catalogue.open(part) // proves the download is whole before anything is switched
                val home = File(dir, "cat-${remote.builtAt}").apply { deleteRecursively() }
                if (!part.renameTo(home)) throw IOException("could not move the new catalogue into place")
                val fresh = Catalogue.open(home)
                File(dir, "current").writeText(home.name)
                _state.value = CatalogueState.Ready(fresh)
                dir.listFiles { f -> f.name.startsWith("cat-") && f.name != home.name }?.forEach { it.deleteRecursively() }
            } catch (e: Exception) {
                part.deleteRecursively()
                fail("The catalogue download did not finish (${e.message ?: "unknown error"}).")
            }
        }
    }

    private fun openCurrent(): Catalogue? = runCatching {
        Catalogue.open(File(dir, File(dir, "current").readText().trim()))
    }.getOrNull()

    private val isWeb get() = source.startsWith("http://") || source.startsWith("https://")

    private fun fetchText(name: String): String =
        if (!isWeb) File(source, name).readText()
        else client.newCall(Request.Builder().url(source + name).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}"); r.body.string()
        }

    private fun fetchFile(name: String, to: File, progress: (Int) -> Unit) {
        if (!isWeb) { File(source, name).copyTo(to, overwrite = true); progress(100); return }
        client.newCall(Request.Builder().url(source + name).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val total = r.body.contentLength()
            r.body.byteStream().use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != last) { last = pct; progress(pct) } }
                    }
                    if (total > 0 && done != total) throw IOException("download cut short")
                }
            }
        }
    }
}
