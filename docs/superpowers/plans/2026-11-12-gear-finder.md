# Gear Finder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Mac and Android app that finds the cheapest online price for outdoor gear in a chosen size and colour, recommends items from a plain-words request, and lists nearby branches with confirmed stock, with no server to run.

**Architecture:** One Kotlin Multiplatform Gradle project. `core` holds the model, retailer adapters, matching, catalogue database and recommend engine. `collector` is a JVM command-line program run by GitHub Actions that crawls and publishes a SQLite catalogue as a release asset. `app` is a Compose Multiplatform UI (Android + Mac) that downloads the catalogue, searches it locally and re-checks a product live through the same adapters.

**Tech Stack:** Kotlin Multiplatform (targets `jvm` and `android`), Compose Multiplatform, Ktor client, kotlinx.serialization, Ksoup (HTML), androidx.sqlite bundled driver (FTS5), kotlin.test, GitHub Actions. Pin the latest stable versions at scaffold time and record them in `gradle/libs.versions.toml`; use a JDK 17 toolchain (the Mac's default Java is 25).

**Spec:** `docs/superpowers/specs/2026-11-12-gear-finder-design.md`. The old project at `~/Downloads/Claude/outdoor-compare/backend` (`merge.js`, `scrapers.js`, `stores.js`) is reference only for parsing and matching rules.

## Global Constraints

- Project root `~/Downloads/gear-finder`; base package `gearfinder`; app name "Gear Finder".
- No server and no paid service. The only hosted pieces are GitHub Actions and a public GitHub repo holding code and public product data.
- Never get past a bot wall or CAPTCHA. A response with header `cf-mitigated: challenge`, or HTTP 403 with a challenge page, marks that retailer blocked for the run. No retries with altered headers.
- Honest user agent on every request: `GearFinder/1.0 (personal price comparison)`. Per host: at most 4 concurrent requests, 150 ms gap per worker, back off on 429 honouring `Retry-After`.
- Honour each site's `robots.txt` disallow rules when crawling.
- Prices are integers in pence everywhere.
- Scope: hiking, mountaineering, climbing, camping, running and related gear. Drop Fishing, Cycling, Caravan, Home & Leisure.
- A branch is shown as having stock only from a source confirmed in Task 1. No "ring to confirm" entries.
- Nearby radius: 40 miles straight-line, nearest first.
- Android testing on the emulator only, unless the user says the phone may be used.
- One retailer failing must not fail the run or the app.

## Review Focus

1. A retailer starts returning a bot challenge mid-crawl: that retailer stops, its previous data is kept, the run still publishes. (Tasks 3, 8)
2. The app has no network, or a catalogue download is cut off: the previous catalogue stays in use and its age is shown; a first run with no network shows a clear message, not a blank screen. (Task 9)
3. Odd size labels ("UK 9.5", "9½", "M/L", "One Size", "EU 42", "32R"): normalised so equal sizes match across shops and different sizes never merge. (Task 2)
4. The live check fails or the page is gone for one shop: that row shows the catalogue price marked "not re-checked"; other rows are unaffected. (Task 10)
5. A Recommend request that is empty, nonsense, or only a budget ("under 200", "£200", "200 quid"): one follow-up question, never a crash or an empty list without explanation. (Task 11)

---

### Task 1: Proof step (gate — report to the user before Task 2)

Throwaway scripts in `proof/`; no tests. Needs `gh auth login` done by the user.

- [ ] **Step 1:** Create the public repo `gear-finder` with `gh repo create --public`, add a workflow `proof.yml` that fetches the product sitemap and three product pages each from Ellis Brigham, Trekitt and Cotswold Outdoor using the constraints above, and prints status codes and any `cf-mitigated` header. Run it with `gh workflow run` and read the log. Pass: 200s, no challenge.
- [ ] **Step 2:** Publish a UCP agent profile JSON on the repo's GitHub Pages, then call `search_catalog` on `https://www.gooutdoors.co.uk/api/ucp/mcp` with that profile URL, query "rab microlight alpine", `address_country` "GB". Record whether results carry per-variant price, size, colour and availability, and whether paging (`pagination.cursor`) can walk a whole category. Repeat `tools/list` only for Tiso, Blacks, Millets.
- [ ] **Step 3:** Per-branch stock, one chain at a time, by opening a product page in the browser and watching what the "check store stock" control loads: Cotswold Outdoor, Snow+Rock, Runners Need, Ellis Brigham, Decathlon, Go Outdoors, Tiso. For each record: is there a per-branch, per-size/colour answer; the request that returns it; whether `robots.txt` permits it. For single-shop independents (Needle Sports, Trekitt, Outside, Joe Brown, Taunton Leisure, Up & Under, Dick's Climbing), record whether the site states that web stock is shop stock.
- [ ] **Step 4:** Resolve the unresolved retailers: for SportsShoes first, then Decathlon, LD Mountain Centre, Outdoor Action, Backpacking Light, Open Air, Peter Tyson, Winfields, find the product sitemap from `robots.txt` and fetch one product page. Record crawlable / blocked / no listing.
- [ ] **Step 5:** Write `proof/RESULTS.md` (cloud crawl, UCP, branch-stock table, retailer table) and report it to the user. If no chain has confirmed branch stock, ask whether Task 12 ships. Commit.

### Task 2: Project scaffold, model, size and colour normalising

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `core/build.gradle.kts`
- Create: `core/src/commonMain/kotlin/gearfinder/model/Model.kt`, `core/src/commonMain/kotlin/gearfinder/model/Normalise.kt`
- Test: `core/src/commonTest/kotlin/gearfinder/model/NormaliseTest.kt`

**Interfaces — Produces:**
```kotlin
@Serializable data class SizeStock(val label: String, val key: String, val inStock: Boolean, val pricePence: Int? = null)
@Serializable data class Variant(val colour: String?, val colourKey: String, val image: String?, val pricePence: Int, val wasPence: Int?, val url: String, val sizes: List<SizeStock>)
@Serializable data class Listing(val retailerId: String, val title: String, val brand: String, val categoryHint: String?, val gender: String?, val url: String, val description: String?, val variants: List<Variant>)
@Serializable data class Offer(val retailerId: String, val url: String, val variants: List<Variant>)
@Serializable data class Product(val id: String, val brand: String, val name: String, val group: String, val category: String, val gender: String?, val description: String?, val image: String?, val offers: List<Offer>)
fun sizeKey(label: String): String
fun colourKey(label: String?): String
```

- [ ] **Step 1:** Scaffold Gradle with modules `core` (KMP: `jvm`, `androidTarget`), `collector` (JVM application), `app` (Compose Multiplatform: `androidTarget`, `jvm("desktop")`). `./gradlew build` passes on empty modules. `git init`.
- [ ] **Step 2:** Write failing tests:
```kotlin
@Test fun sizes() {
  assertEquals("m", sizeKey("Medium")); assertEquals("m", sizeKey("Mens M"))
  assertEquals("9.5", sizeKey("UK 9.5")); assertEquals("9.5", sizeKey("9½"))
  assertEquals("xxl", sizeKey("2XL")); assertEquals("os", sizeKey("One Size"))
  assertEquals("eu42", sizeKey("EU 42")); assertNotEquals(sizeKey("EU 42"), sizeKey("UK 42"))
  assertEquals("m/l", sizeKey("M/L")); assertNotEquals(sizeKey("M/L"), sizeKey("M"))
  assertEquals("32r", sizeKey("32 R")); assertNotEquals(sizeKey("32R"), sizeKey("32L"))
}
@Test fun colours() {
  assertEquals(colourKey("Black/Grey"), colourKey("Grey & Black"))
  assertEquals("black", colourKey("TNF Black")); assertEquals("", colourKey("No Colour")); assertEquals("", colourKey(null))
}
```
- [ ] **Step 3:** Run `./gradlew :core:jvmTest`; expect failures.
- [ ] **Step 4:** Implement both functions. Start from `sizeKey`/`colorKey` in the old `merge.js`, with two deliberate changes: keep the `eu` prefix (UK is the default and is dropped), and map `½` to `.5`.
- [ ] **Step 5:** Tests pass. Commit.

### Task 3: Polite HTTP client and sitemap walker

**Files:**
- Create: `core/src/commonMain/kotlin/gearfinder/net/Http.kt`, `.../net/Sitemap.kt`, `.../net/Robots.kt`
- Test: `core/src/commonTest/kotlin/gearfinder/net/HttpTest.kt`, `SitemapTest.kt`, `RobotsTest.kt` (Ktor `MockEngine`)

**Interfaces — Produces:**
```kotlin
sealed interface Fetch { data class Ok(val body: String): Fetch; data object Gone: Fetch; data object Blocked: Fetch; data class Failed(val reason: String): Fetch }
class Http(engine: HttpClientEngine, val userAgent: String = "GearFinder/1.0 (personal price comparison)") {
  suspend fun get(url: String): Fetch          // per-host limits, 429 backoff, gzip, challenge -> Blocked
  suspend fun postJson(url: String, body: String): Fetch
  fun isBlocked(host: String): Boolean         // sticky for the life of this Http
}
suspend fun sitemapUrls(http: Http, root: String, childFilter: Regex? = null, urlFilter: Regex? = null): List<String>   // follows indexes up to depth 3, handles .gz
class Robots(text: String) { fun allows(path: String): Boolean }
```

- [ ] **Step 1:** Failing tests: `cf-mitigated: challenge` → `Blocked` and every later `get` to that host returns `Blocked` without a request; 429 with `Retry-After: 1` is retried once then `Ok`; 404 → `Gone`; 500 three times → `Failed`; sitemap index with two children and a `childFilter` returns only matching children's URLs; `Robots("User-agent: *\nDisallow: /api*\nAllow: /api/aem/search*")` allows `/p/x.html` and `/api/aem/search?q=1`, disallows `/api/stock`.
- [ ] **Step 2:** Run; expect failures.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Tests pass. Commit.

### Task 4: Adapter contract, JSON-LD parser, and the standard-page retailers

**Files:**
- Create: `core/src/commonMain/kotlin/gearfinder/retail/Adapter.kt`, `.../retail/JsonLd.kt`, `.../retail/SitemapAdapter.kt`, `.../retail/Retailers.kt`
- Create fixtures: `core/src/commonTest/resources/pages/<retailerId>.html` (one saved real product page each)
- Test: `core/src/commonTest/kotlin/gearfinder/retail/JsonLdTest.kt`, `RetailerFixturesTest.kt`

**Interfaces — Produces:**
```kotlin
interface RetailerAdapter {
  val id: String; val name: String; val home: String
  suspend fun crawl(http: Http, seen: CrawlCache, emit: suspend (Listing) -> Unit): CrawlResult
  suspend fun refresh(http: Http, url: String): Listing?      // live check of one page
}
data class CrawlResult(val listed: Int, val fetched: Int, val blocked: Boolean)
interface CrawlCache { fun fresh(url: String, maxAgeHours: Int): Listing?; fun put(url: String, l: Listing?) }
fun parseJsonLdProduct(html: String, url: String, retailerId: String): Listing?
class SitemapAdapter(id, name, home, sitemaps: List<String>, childFilter: Regex?, productUrl: Regex?, parse: (String, String) -> Listing? = JSON-LD, sliceOf: Int = 1): RetailerAdapter
val RETAILERS: List<RetailerAdapter>
```
`sliceOf = n` means each run fetches the 1/n of URLs whose hash matches the run's day number; the rest come from `CrawlCache`.

- [ ] **Step 1:** Save one product page per retailer into fixtures for: Ellis Brigham, Alpine Trek, Elite Mountain Supplies, Joe Brown, Taunton Leisure, Outside, Facewest, Absolute Snow, Dick's Climbing, Up & Under, WildBounds, All Outdoor, plus any that passed Task 1 Step 4.
- [ ] **Step 2:** Failing tests: `parseJsonLdProduct` on a hand-written page with a `Product` holding two `Offer`s gives one variant, two sizes, prices in pence, `inStock` from `availability`; on a `ProductGroup` with `hasVariant` gives one variant per colour. For every fixture: listing is non-null, brand and title non-blank, at least one variant with `pricePence > 0`.
- [ ] **Step 3:** Run; expect failures.
- [ ] **Step 4:** Implement the parser, `SitemapAdapter`, and register each retailer with the sitemap addresses and URL patterns from the old `stores.js` or found in Task 1. Alpine Trek gets `sliceOf = 7`. A retailer whose fixture cannot yield size-level stock from JSON-LD moves to Task 5.
- [ ] **Step 5:** Tests pass. Commit.

### Task 5: Retailers needing their own parser

**Files:**
- Create: `core/src/commonMain/kotlin/gearfinder/retail/AsAdventure.kt` (Cotswold, Snow+Rock, Runners Need), `Trekitt.kt`, `NeedleSports.kt`, `Ultralight.kt`, `ShopifyJson.kt` (Rock+Run `products.json`), `ShopifyUcp.kt` (Go Outdoors, Tiso, Blacks, Millets — only if Task 1 Step 2 passed)
- Fixtures and tests alongside, same pattern as Task 4.

**Interfaces — Consumes:** `RetailerAdapter`, `Http`, `Listing`. **Produces:** entries added to `RETAILERS`.

- [ ] **Step 1:** Save fixtures; write a fixture test per parser asserting brand, title, at least two sizes with distinct `key`s, a colour where the page shows one, and pence prices matching the page.
- [ ] **Step 2:** Run; expect failures.
- [ ] **Step 3:** Implement each, porting `fromAsAdventure`, `fromTrekitt`, `fromMeta`, `scrapeNeedle`, `shopifyListing` from the old `scrapers.js`. `ShopifyUcp.crawl` pages `search_catalog` per category term; `refresh` calls `get_product`.
- [ ] **Step 4:** Tests pass. Commit.

### Task 6: Classify and merge

**Files:**
- Create: `core/src/commonMain/kotlin/gearfinder/merge/Classify.kt`, `.../merge/Merge.kt`
- Test: `core/src/commonTest/kotlin/gearfinder/merge/ClassifyTest.kt`, `MergeTest.kt`

**Interfaces — Produces:**
```kotlin
data class Placement(val group: String, val category: String)
fun classify(title: String, categoryHint: String?): Placement?   // null = out of scope
fun merge(listings: List<Listing>): List<Product>
```

- [ ] **Step 1:** Failing tests: "Rab Microlight Alpine Jacket" → `Clothing / Insulated & Down Jackets`; a fishing reel and a caravan awning → null; a trail running shoe → `Footwear / Running Shoes`. Merge: the same jacket from two retailers with titles "Rab Men's Microlight Alpine Jacket" and "Rab Microlight Alpine Jacket - Mens" becomes one `Product` with two offers; men's and women's versions stay separate; "Jacket - Black" and "Jacket - Blue" from one Shopify shop become one offer with two variants; a title ending "Pack of 28" keeps its suffix.
- [ ] **Step 2:** Run; expect failures.
- [ ] **Step 3:** Implement, porting the rule table and matching from the old `merge.js` and adding a Running group for SportsShoes and Runners Need. `Product.id` is a stable hash of brand + normalised name + gender.
- [ ] **Step 4:** Tests pass. Commit.

### Task 7: Catalogue database

**Files:**
- Create: `core/src/commonMain/kotlin/gearfinder/catalogue/Catalogue.kt`, `.../catalogue/Query.kt`
- Test: `core/src/jvmTest/kotlin/gearfinder/catalogue/CatalogueTest.kt`

**Interfaces — Produces:**
```kotlin
data class Meta(val builtAt: Long, val retailers: List<RetailerHealth>, val branchStockRetailers: List<String>)
data class RetailerHealth(val id: String, val name: String, val items: Int, val lastSuccess: Long)
data class Query(val text: String = "", val group: String? = null, val category: String? = null, val brands: Set<String> = emptySet(),
  val retailers: Set<String> = emptySet(), val sizeKey: String? = null, val colourKey: String? = null, val gender: String? = null,
  val maxPence: Int? = null, val inStockOnly: Boolean = false, val onSaleOnly: Boolean = false, val sort: Sort = Sort.Relevance, val limit: Int = 60, val offset: Int = 0)
enum class Sort { Relevance, PriceLow, PriceHigh, Discount }
data class Summary(val id: String, val brand: String, val name: String, val category: String, val image: String?, val fromPence: Int, val wasPence: Int?, val shopCount: Int)
data class Facets(val groups: Map<String, List<String>>, val brands: List<String>, val sizes: List<String>, val colours: List<String>)
object CatalogueWriter { fun write(path: String, products: List<Product>, meta: Meta) }
class Catalogue(path: String) { fun meta(): Meta; fun search(q: Query): List<Summary>; fun product(id: String): Product?; fun facets(group: String?): Facets; fun close() }
```
Schema: `product(id, brand, name, grp, category, gender, image, from_pence, was_pence, shop_count, json)`, an FTS5 table over brand, name, category, description, and `offer_size(product_id, retailer_id, size_key, colour_key, pence, in_stock)` for filters.

- [ ] **Step 1:** Failing tests on three hand-built products: text "microlight" finds the jacket; `sizeKey="m", inStockOnly=true` excludes a product whose medium is out of stock everywhere; `fromPence` with a size filter is the cheapest price for that size, not the product's overall cheapest; `maxPence` and `onSaleOnly` filter; `product(id)` round-trips equal; `meta()` round-trips.
- [ ] **Step 2:** Run `./gradlew :core:jvmTest`; expect failures.
- [ ] **Step 3:** Implement with `androidx.sqlite` bundled driver.
- [ ] **Step 4:** Tests pass. Commit.

### Task 8: Collector and scheduled workflow

**Files:**
- Create: `collector/src/main/kotlin/gearfinder/collector/Main.kt`, `.../FileCrawlCache.kt`, `.../PublishGuard.kt`
- Create: `.github/workflows/collect.yml`
- Test: `collector/src/test/kotlin/gearfinder/collector/PublishGuardTest.kt`, `CollectRunTest.kt`

**Interfaces — Produces:** release tag `catalogue` with assets `catalogue.db.gz` and `meta.json`; `fun shouldPublish(previous: Meta?, next: Meta): Verdict` (refuse when total items fall by more than 30%).

- [ ] **Step 1:** Failing tests: guard refuses 100,000 → 60,000 and accepts 100,000 → 80,000 and accepts when `previous` is null; a run with two fake adapters where one returns `blocked = true` still writes a catalogue containing the other's products plus the blocked retailer's cached listings, and its `RetailerHealth.lastSuccess` is unchanged.
- [ ] **Step 2:** Run; expect failures.
- [ ] **Step 3:** Implement `Main` (args: `--out dir`, `--only id,id`, `--limit n`), cache as gzipped JSON lines per retailer restored and saved with `actions/cache`. Workflow: cron daily 02:00 UTC plus manual trigger, one matrix job per retailer uploading listings as artifacts, a final job that merges, guards and uploads to the release with `gh release upload --clobber`. Each matrix job has `timeout-minutes: 300` and `continue-on-error: true`.
- [ ] **Step 4:** Tests pass. Run the workflow once with `--limit 200`; expect a release with both assets. Then run in full. Commit.

### Task 9: App shell, catalogue download, browse and search

**Files:**
- Create: `app/src/commonMain/kotlin/gearfinder/app/App.kt`, `.../data/CatalogueStore.kt`, `.../data/Settings.kt`, `.../ui/BrowseScreen.kt`, `.../ui/Filters.kt`, `.../ui/Theme.kt`
- Create: `app/src/androidMain/.../MainActivity.kt`, `app/src/desktopMain/.../Main.kt`, platform `expect/actual` for the data directory and opening a URL
- Test: `app/src/commonTest/kotlin/gearfinder/app/CatalogueStoreTest.kt`

**Interfaces — Produces:**
```kotlin
sealed interface CatalogueState { data object None; data class Downloading(val pct: Int); data class Ready(val catalogue: Catalogue, val ageDays: Int, val stale: List<RetailerHealth>); data class NoNetworkFirstRun(val retry: () -> Unit) }
class CatalogueStore(dir: String, http: Http, releaseUrl: String) { val state: StateFlow<CatalogueState>; suspend fun refresh() }
```
`refresh` fetches `meta.json`, downloads `catalogue.db.gz` only when `builtAt` is newer, writes to a temp file, verifies it opens, then swaps.

- [ ] **Step 1:** Failing tests (MockEngine): newer `builtAt` downloads and swaps; same `builtAt` makes no download; a download cut off mid-body leaves the previous catalogue in place and state `Ready`; no previous catalogue and no network gives `NoNetworkFirstRun`.
- [ ] **Step 2:** Run; expect failures.
- [ ] **Step 3:** Implement the store, then the UI: a navigation bar with Browse, Recommend, Saved, Settings; Browse shows a search field, group and category chips, a filter sheet (size, colour, brand, shop, gender, max price, in stock, on sale), sort menu, and a grid of `Summary` cards; a footer line "Catalogue updated N days ago" plus any stale retailers. Saved is a local list of product ids.
- [ ] **Step 4:** Tests pass. Run `./gradlew :app:run` on the Mac and `:app:installDebug` on the emulator; search "microlight", apply size M; screenshot both. Commit.

### Task 10: Product page with live check

**Files:**
- Create: `app/src/commonMain/kotlin/gearfinder/app/ui/ProductScreen.kt`, `.../data/LiveCheck.kt`
- Test: `app/src/commonTest/kotlin/gearfinder/app/LiveCheckTest.kt`

**Interfaces — Produces:**
```kotlin
enum class Freshness { Checking, Live, NotRechecked }
data class OfferRow(val retailerName: String, val url: String, val pricePence: Int?, val inStock: Boolean, val freshness: Freshness)
fun liveRows(product: Product, sizeKey: String?, colourKey: String?, adapters: List<RetailerAdapter>, http: Http): Flow<List<OfferRow>>
```
Rows are emitted at once from the catalogue as `Checking`, then updated per retailer as each `refresh` returns; sorted in-stock first, then cheapest.

- [ ] **Step 1:** Failing tests with fake adapters: a retailer whose `refresh` returns a lower price ends `Live` with the new price and moves to the top; one that throws or returns null ends `NotRechecked` with the catalogue price; a size not sold by a retailer gives `inStock = false`; one adapter hanging does not delay the others (15 s timeout each).
- [ ] **Step 2:** Run; expect failures.
- [ ] **Step 3:** Implement `liveRows` and the screen: images, name, description, colour and size pickers, the rows with a "not re-checked" tag where it applies, a Buy button opening the retailer URL, Save, and a Nearby tab placeholder that reads "Not available yet" unless Task 12 ships.
- [ ] **Step 4:** Tests pass; check on Mac and emulator. Commit.

### Task 11: Recommend

**Files:**
- Create: `core/src/commonMain/kotlin/gearfinder/recommend/Request.kt`, `.../recommend/Features.kt`, `.../recommend/Recommend.kt`, `app/src/commonMain/kotlin/gearfinder/app/ui/RecommendScreen.kt`
- Test: `core/src/commonTest/kotlin/gearfinder/recommend/RequestTest.kt`, `core/src/jvmTest/kotlin/gearfinder/recommend/RecommendTest.kt`

**Interfaces — Produces:**
```kotlin
data class Request(val category: Placement?, val gender: String?, val sizeKey: String?, val colourKey: String?, val maxPence: Int?, val brand: String?, val uses: Set<String>)
fun parseRequest(text: String, facets: Facets): Request
data class Pick(val summary: Summary, val matched: List<String>, val reason: String)
sealed interface Advice { data class Picks(val picks: List<Pick>): Advice; data class Ask(val question: String, val options: List<String>): Advice; data class Nothing(val why: String): Advice }
fun recommend(text: String, catalogue: Catalogue): Advice
```
`Features.kt` holds the use-word table: use word → category-specific feature terms searched in name and description (winter+jacket → insulated, down, hardshell, hood; wet → waterproof, gore-tex, taped seams; ultralight → lightweight, ultralight; and so on for boots, tents, sleeping bags, packs, running shoes). Ranking: features matched, then `shopCount`, then discount, then price. Reason text: `"matches: waterproof, insulated, hood; 4 shops; 30% off"`.

- [ ] **Step 1:** Failing tests: "waterproof jacket for Scottish winter under £200, men's medium" parses to a jacket category, gender men, `sizeKey` "m", `maxPence` 20000, uses containing "winter" and "wet"; "under 200", "£200" and "200 quid" all give `maxPence` 20000; `recommend("")` and `recommend("asdf qwer")` return `Ask`; `recommend("something under £50")` returns `Ask` listing categories; on a small catalogue the pick matching three features outranks one matching one, picks over budget or out of stock in the size are absent, and at most 10 picks return; no candidates gives `Nothing` with a reason naming the constraint that excluded everything.
- [ ] **Step 2:** Run; expect failures.
- [ ] **Step 3:** Implement the engine and the screen: a text box, the picks as cards with the reason line, tappable follow-up options for `Ask`.
- [ ] **Step 4:** Tests pass; try three requests on Mac and emulator. Commit.

### Task 12: Nearby (only for chains confirmed in Task 1, and only if the user said to ship it)

**Files:**
- Create: `core/src/commonMain/kotlin/gearfinder/nearby/Branch.kt`, `.../nearby/Distance.kt`, `.../nearby/Postcode.kt`, `.../nearby/BranchStock.kt`, one `<Chain>BranchStock.kt` per confirmed chain; `app/.../ui/NearbyTab.kt`; platform location `expect/actual`
- Test: `core/src/commonTest/kotlin/gearfinder/nearby/DistanceTest.kt`, `PostcodeTest.kt`, one fixture test per chain

**Interfaces — Produces:**
```kotlin
data class Branch(val retailerId: String, val name: String, val address: String, val lat: Double, val lon: Double)
fun miles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double
suspend fun lookupPostcode(http: Http, postcode: String): Pair<Double, Double>?    // postcodes.io
interface BranchStockSource { val retailerId: String; suspend fun inStockBranches(http: Http, offer: Offer, sizeKey: String, colourKey: String): List<Branch>? }   // null = could not confirm
fun nearby(here: Pair<Double, Double>, found: List<Branch>, radiusMiles: Double = 40.0): List<Pair<Branch, Double>>
```

- [ ] **Step 1:** Failing tests: Manchester to Leeds is 35–37 miles; `nearby` drops a branch at 41 miles and orders the rest nearest first; a malformed postcode gives null; each chain's fixture yields the branches the saved response shows; a source returning null contributes nothing.
- [ ] **Step 2:** Run; expect failures.
- [ ] **Step 3:** Implement. Branch lists are crawled by the collector from each chain's store-finder and stored in the catalogue. Android asks for location permission and falls back to the typed postcode when denied; Mac uses the Settings postcode. The tab lists branch, distance and a line naming which chains were checked; with no location it prompts for a postcode.
- [ ] **Step 4:** Tests pass; check on Mac and emulator with a real product. Commit.

### Task 13: Packaging and hand-over

- [ ] **Step 1:** `./gradlew :app:packageDmg` (or `createDistributable`) produces `Gear Finder.app`; copy to `/Applications` only if the user agrees. `./gradlew :app:assembleRelease` with a debug-signed APK for the emulator.
- [ ] **Step 2:** From a clean install on each platform: first launch downloads the catalogue, search, filter, product live check, recommend. Record what was and was not verified.
- [ ] **Step 3:** Write `README.md`: how to rebuild, how the nightly collection works, how to add a retailer. Commit and push.
