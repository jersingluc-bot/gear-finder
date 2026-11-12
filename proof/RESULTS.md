# Proof step results — 2026-11-12

## Crawl access
- From this Mac, with the honest user agent: Ellis Brigham, Trekitt, Cotswold Outdoor and Alpine Trek sitemaps and product pages all return 200 with JSON-LD product data; Needle Sports home page 200. (`node proof/crawl.mjs`)
- From GitHub: NOT RUN. Creating the public repo was blocked by this session's permission settings. `.github/workflows/proof.yml` is ready.

## Go Outdoors / Tiso / Blacks / Millets
- Web pages and `products.json`: Cloudflare challenge. Not to be worked around.
- Published interface `/api/ucp/mcp`: `tools/list` works (search_catalog, get_product, lookup_catalog). A real `search_catalog` call failed with `profile_unreachable`: it needs a public agent-profile URL, which needs the public repo. Unproven.
- Its fulfilment config lists shipping only; no per-branch data seen.

## Per-branch stock
| Chain | Finding | Usable |
|---|---|---|
| Cotswold Outdoor, Snow+Rock, Runners Need | "Show store stock" panel exists and lists branches by distance with a stock toggle. Its data comes from `/api/basket/stock/reserve` using a basket session token; `robots.txt` disallows `/api*`. | No |
| Go Outdoors, Tiso, Blacks, Millets | Pages bot-walled; published interface is delivery only. | No |
| Ellis Brigham, Decathlon | Not checked. | Unknown |
| Single-shop independents | Not checked whether web stock is stated to be shop stock. | Unknown |

No confirmed per-branch source so far.

## Unresolved retailers
- SportsShoes: `robots.txt` lists no sitemap and `/sitemap.xml` is 404. No product listing found yet.
- Decathlon and the rest: not checked further.
