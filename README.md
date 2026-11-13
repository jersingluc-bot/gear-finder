# Gear Finder

Finds the cheapest online price for outdoor gear in your size and colour, across UK outdoor shops.
One codebase builds a Mac app and an Android app. There is no server.

## How it works

- **Collector** (`collector/`) reads each shop's product pages politely, matches the same item across shops and writes a
  catalogue: `meta.json`, `index.jsonl.gz`, `products.jsonl.gz`.
- **Apps** (`ui/` shared screens, `androidApp/` Android shell) download the catalogue, search it on the device, and
  re-read a product's shop pages live when you open it.
- **Core** (`core/`) holds everything both use: shop readers, matching, categories, catalogue, live check, Recommend.

## Shops

Read by the app: Cotswold Outdoor, Snow+Rock, Runners Need, Alpine Trek, Ellis Brigham, Absolute Snow,
Ultralight Outdoor Gear, Trekitt, Joe Brown / The Climbers Shop, Taunton Leisure.

Size-level stock is published by Cotswold, Snow+Rock, Runners Need and Alpine Trek. The others give price and overall
stock only; the app says so on the product page and a size filter does not count them.

Not read (they refuse automated access or throttle it): SportPursuit, Go Outdoors, Tiso, Blacks, Millets, SportsShoes,
Decathlon, Rock+Run, Facewest, Dick's Climbing, WildBounds. The product page has a button that searches each of the
first seven in your browser. See `proof/RESULTS.md`.

Nearby in-store stock is not built: no shop publishes per-branch stock in a form the app may read.

## Everyday use

| Do this | Command |
|---|---|
| Run the Mac app from source | `./gradlew :ui:run` |
| Package the Mac app | `./gradlew :ui:createDistributable` (output in `ui/build/compose/binaries/main/app/`) |
| Build the Android app | `./gradlew :androidApp:assembleDebug` |
| Read every shop and rebuild the catalogue | `./crawl.sh` (hours the first time; add `--only cotswold,trekitt` for some) |
| Rebuild the catalogue from saved pages | `./rebuild-catalogue.sh` |
| Run the tests | `./gradlew :core:test :collector:test` |

The catalogue is written to `~/Library/Application Support/Gear Finder/published`, which is where the Mac app looks.
Saved pages live in `data/cache`.

## Automatic refresh

`.github/workflows/collect.yml` runs the collector daily on GitHub and publishes the catalogue as a release named
`catalogue`. The apps' default catalogue source (`DEFAULT_SOURCE` in `ui/src/shared/kotlin/gearfinder/app/AppState.kt`) points at
that release. In the app, Settings > Advanced accepts any other web address or folder that holds the three catalogue files.

## Adding a shop

Saved shop pages are test data kept on this Mac only (not published), so the page tests run here and not on GitHub.

1. Save one of its product pages as `core/src/test/resources/pages/<id>.html` and its address as `<id>.url`.
2. If the page carries standard product markup, add one `SitemapAdapter(...)` line to
   `core/src/main/kotlin/gearfinder/retail/Retailers.kt`; otherwise write a reader like `Trekitt.kt` and pass it as `parse`.
3. Add the id to `realPagesParse` in `RetailTest.kt` and run the tests.

## Categories

Shelf order is `TAXONOMY` in `core/src/main/kotlin/gearfinder/merge/Classify.kt`. Title keywords are in
`core/src/main/resources/gearfinder/rules.tsv` (first match wins); what each shop's own category wording means is in
`hints.tsv`. `collector --audit FILE --cache data/cache` lists where every saved product lands.
