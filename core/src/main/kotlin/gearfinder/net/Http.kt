package gearfinder.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

sealed interface Fetch {
    data class Ok(val body: String) : Fetch
    /** The page no longer exists (404 or 410). */
    data object Gone : Fetch
    /** The site refused automated access. Never retried and never worked around. */
    data object Blocked : Fetch
    data class Failed(val reason: String) : Fetch
}

const val USER_AGENT = "GearFinder/1.0 (personal price comparison)"

/**
 * A polite client: honest user agent, a few requests per host at a time with a gap between them,
 * backs off when told to, and stops talking to a host that shows a bot challenge.
 */
class Http(
    private val client: OkHttpClient = defaultClient(),
    val userAgent: String = USER_AGENT,
    private val perHost: Int = 4,
    private val gapMs: Long = 150,
    private val backoffMs: Long = 2000,
    private val tries: Int = 3,
) {
    private val gates = ConcurrentHashMap<String, Semaphore>()
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    private val refusals = ConcurrentHashMap<String, Int>()

    fun isBlocked(host: String): Boolean = host in blocked

    suspend fun get(url: String): Fetch = send(url) { Request.Builder().url(url) }

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): Fetch = send(url) {
        Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }
    }

    private suspend fun send(url: String, build: () -> Request.Builder): Fetch {
        val host = try { URI(url).host ?: return Fetch.Failed("bad url") } catch (e: Exception) { return Fetch.Failed("bad url") }
        if (host in blocked) return Fetch.Blocked
        return gates.getOrPut(host) { Semaphore(perHost) }.withPermit {
            var last = "no response"
            for (i in 0 until tries) {
                if (host in blocked) return@withPermit Fetch.Blocked
                val r = try { once(build().header("User-Agent", userAgent).build()) } catch (e: IOException) { last = e.message ?: "network error"; null }
                if (gapMs > 0) delay(gapMs)
                when {
                    r == null -> delay(backoffMs * (i + 1) / 2)
                    r.challenge -> { blocked += host; return@withPermit Fetch.Blocked }
                    r.code == 403 || r.code == 401 -> {
                        if (refusals.merge(host, 1, Int::plus)!! >= 5) blocked += host
                        return@withPermit Fetch.Blocked
                    }
                    r.code == 404 || r.code == 410 -> return@withPermit Fetch.Gone
                    r.code == 429 || r.code >= 500 -> {
                        last = "HTTP ${r.code}"
                        if (i < tries - 1) delay(r.retryAfterMs ?: (backoffMs * (i + 1)))
                    }
                    r.code in 200..299 -> return@withPermit Fetch.Ok(r.body)
                    else -> return@withPermit Fetch.Failed("HTTP ${r.code}")
                }
            }
            Fetch.Failed(last)
        }
    }

    private class Raw(val code: Int, val body: String, val challenge: Boolean, val retryAfterMs: Long?)

    private suspend fun once(request: Request): Raw = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { res ->
            val bytes = res.body.bytes()
            val text = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
                GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().decodeToString() else bytes.decodeToString()
            val challenge = res.header("cf-mitigated").equals("challenge", ignoreCase = true) ||
                (res.code == 403 && (text.contains("Verifying your connection") || text.contains("Just a moment...")))
            val retry = res.header("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(1, 60)?.times(1000)
            Raw(res.code, text, challenge, retry)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true).build()
    }
}
