# Gear Finder — design

Date: 2026-11-12. Status: awaiting approval.

## Intent

One app, on Mac and Android, that finds the cheapest online price for any piece of
outdoor gear in a chosen size and colour, and lists shops within about an hour's drive
that have that exact size and colour on the shelf.

It replaces Outdoor Compare (`~/Downloads/Claude/outdoor-compare`), which depended on a
server running on the Mac and was unreliable. The new one must just run: no server to
start, nothing to babysit.

## Goals

- Browse the full catalogue of every included retailer by category, and search for a known item.
- Filter by size, colour, brand, shop, in stock, on sale.
- Product page: every shop's price for the selected size and colour, cheapest first, checked live when the page opens. A button opens the retailer's page to buy.
- Nearby tab on the product page: branches within ~40 miles (straight line, standing in for an hour's drive) that hold the selected size and colour, nearest first.
- A Recommend section: the user describes what they are after in plain words and gets a short ranked list of suggestions (see below).
- Same behaviour on Mac and Android.
- Works with no Mac server and no paid hosting.

## Non-goals (first version)

- Buying inside the app, accounts, or checkout.
- Price alerts and price history (the old app had them; not asked for this time).
- Delivery-cost comparison. Item price only.
- Shops outside the UK.
- Fishing, cycling, caravanning, home and leisure (same scope as before: hiking, mountaineering, climbing, camping, running and related gear).

## Anti-goals

- Anything that needs the Mac switched on or a process kept alive.
- Showing a branch as having stock when that is a guess. Only confirmed per-branch stock is shown; there are no "ring to confirm" entries.
- Getting past bot walls or CAPTCHAs. A retailer that refuses automated access is left out, or reached only through an interface it publishes for apps.
- One retailer's breakage taking the rest down.

## Approaches considered

- **A. Cloud-collected catalogue, apps do live checks (chosen by the user).** A scheduled GitHub job crawls retailers and publishes one catalogue file. Apps download it and browse on-device; opening a product re-checks price and stock live from the app. No server, no cost.
- **B. Rented always-on server.** More reliable crawling, but about £4–5 a month and still a server that can fail, which is what the user wants rid of.
- **C. Apps crawl everything themselves.** Ruled out: 100,000+ listings on a phone costs battery and data, and Android stops long background jobs.

## How it is built (builder's calls unless marked)

**One Kotlin codebase**, folder `~/Downloads/gear-finder`:

- `core` — shared logic: the product model, size and colour normalising, product matching across shops, and one adapter per retailer. Each adapter can (1) list all products and (2) fetch one product's current price and per-size/colour stock. The matching and normalising rules in the old `merge.js` are the reference.
- `collector` — a command-line program using `core`, run on a schedule by GitHub Actions. It crawls, merges, and publishes a compressed SQLite catalogue as a GitHub release asset, plus a small health file (per retailer: item count, last success).
- `app` — Compose Multiplatform, one UI for Android and Mac (packaged as a `.app`). It downloads the catalogue when a newer one exists, searches it locally, and uses the same adapters for the live check.

**Catalogue refresh.** Crawls are polite (low concurrency, honest user agent, back off on 429) and resumable. The largest shop took ten hours last time, longer than one GitHub job allows, so each run refreshes a slice and the catalogue rolls over every few days. The live check on the product page is what keeps prices current. The repo is public so the scheduled runs are free; it holds code and public product data only, nothing personal.

**Location.** Android uses the phone's location. The Mac uses a postcode typed once in settings, resolved with the free postcodes.io service. Branch addresses and coordinates come from each chain's store-finder page and ship inside the catalogue.

**Health.** If a retailer's crawl fails or its item count collapses, the previous data for that shop is kept and the app marks it "last updated N days ago". The app shows the catalogue's age.

**Tests.** Each adapter is tested against saved copies of real pages. Matching and size/colour normalising get unit tests. The collector's publish step refuses to ship a catalogue that lost more than a set share of its items.

## Recommend section (requested by the user)

The user types what they are looking for, for example "waterproof jacket for Scottish
winter under £200, men's medium". The app answers with about five to ten picks, each
with a one-line reason and its cheapest current price.

Builder's call on how: it runs entirely inside the app, with no AI service, no key and
no running cost, in keeping with "just runs".

- The request is read for: kind of item, gender, size, colour, budget, brand, and use words (winter, summer, ultralight, wet weather, 3-season, climbing, trail running and so on).
- Use words map to features found in product names and descriptions (for example winter + jacket favours insulated or hardshell, hood, higher waterproof rating).
- Candidates must be in stock in the stated size and within budget. They are ranked by how many requested features they match, then by sold-by-several-shops (a sign of a mainstream, well-regarded item), then by discount and price.
- Each pick states why it was chosen ("matches: waterproof, insulated, hood; 4 shops; 30% off"), so the ranking is never a black box.
- If the request is too vague to place in a category, the app asks one follow-up (for example "jacket, trousers or boots?") instead of guessing.

Limit to state plainly: this is matching and ranking, not expert advice. It cannot
judge fit or quality beyond what shops write about a product. An AI-backed version
would understand looser requests and explain trade-offs, but needs a paid key and an
internet service to stay up; it is left as a possible later addition.

## Retailers

Checked on 2026-11-12 from a home connection.

**Crawlable openly (listings reachable):** Ellis Brigham, Alpine Trek, Snow+Rock, Cotswold Outdoor, Runners Need, Trekitt, Needle Sports, Ultralight Outdoor Gear, Elite Mountain Supplies, Joe Brown / The Climbers Shop, Taunton Leisure, Outside, Facewest, Absolute Snow, Dick's Climbing, Up & Under, WildBounds, All Outdoor, Rock+Run.

**Through the retailer's published app interface (Shopify's shopping protocol):** Go Outdoors, Tiso, Blacks, Millets. Their web pages now sit behind a bot wall, which is not to be worked around. They publish an official interface with catalogue search, exact prices and live availability. It listed its tools when queried, but a real search needs the app to publish a small public profile first, so this is unproven until that is done.

**Not yet resolved (no listing found at the usual address, or timed out):** SportsShoes, Decathlon, LD Mountain Centre, Outdoor Action, Backpacking Light, Open Air, Peter Tyson, Winfields. SportsShoes was asked for by name, so it gets a proper look first.

**Left out (refuse automated access):** Banana Fingers, Webtogs, Craigdon Mountain Sports, Valley and Peak.

The user may strike or add names. "Reputable" means an established UK retailer with its own shop or long trading history; marketplaces and resellers are excluded.

## In-store stock: what is and is not known

No chain's per-branch stock has been confirmed readable yet. This is the biggest risk in the project.

- Cotswold Outdoor, Snow+Rock and Runners Need pages carry a store-stock feature, but the address it loads from was not found, and their site rules ask crawlers to stay out of that area.
- Go Outdoors, Tiso, Blacks and Millets: the published interface offers delivery only, with no sign of per-branch data.
- Single-shop independents: web stock may be the shop's stock or a warehouse's. It counts as confirmed only where the retailer says so.

So the Nearby tab may launch covering few shops, possibly none. It lists a branch only when the source is confirmed, and says which chains it covers.

## Build order

1. **Proof step, before anything else:** (a) run a test crawl of three open retailers from GitHub to confirm cloud access works; (b) make one real search through the Go Outdoors interface; (c) establish, chain by chain, whether per-branch stock can be read. Report the results to the user. If (c) finds nothing, the user decides whether the Nearby tab ships at all.
2. `core` with adapters for the open retailers, and the collector publishing a catalogue.
3. The app: browse, search, filters, product page with live check. Android and Mac.
4. Nearby tab for whichever chains passed step 1.
5. Remaining retailers, one adapter at a time.

## Needed from the user

- A free GitHub account, logged in on this Mac (`gh auth login`), before step 1.
- Phone testing is on the Android emulator unless they say the phone may be used.

## Left to the builder

Catalogue schema, screen layout, the slice schedule, adapter internals, and how many items a retailer may lose before publishing is refused.
