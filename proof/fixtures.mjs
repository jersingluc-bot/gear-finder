// Saves one real product page per retailer as a test fixture and prints what its JSON-LD offers.
import zlib from 'node:zlib'; import fs from 'node:fs';
const UA = 'GearFinder/1.0 (personal price comparison)';
async function get(url) { try { const res = await fetch(url, { headers: { 'user-agent': UA }, signal: AbortSignal.timeout(40000) }); const buf = Buffer.from(await res.arrayBuffer()); return { s: res.status, b: (buf[0] === 0x1f && buf[1] === 0x8b ? zlib.gunzipSync(buf) : buf).toString('utf8') }; } catch (e) { return { s: 0, b: '' }; } }
const locs = (x) => [...x.matchAll(/<loc>\s*([^<\s]+)\s*<\/loc>/g)].map((m) => m[1].replace(/&amp;/g, '&'));
async function urls(root, child, depth = 0) { const r = await get(root); const l = locs(r.b); if (/<sitemapindex/.test(r.b) && depth < 2) { const out = []; for (const c of l.filter((u) => !child || child.test(u)).slice(0, 3)) out.push(...await urls(c, child, depth + 1)); return out; } return l; }
const S = [
  ['eb', 'https://www.ellis-brigham.com/sitemap.xml', null, /jacket.*-\d{7,}$/],
  ['alpine', 'https://www.alpinetrek.co.uk/sitemap/sitemap.xml.gz', /sitemap_products/, /rab-.*microlight.*jacket/],
  ['elite', 'https://www.elitemountainsupplies.co.uk/sitemap-index.xml', /sitemap-products_/, /-p\d+$/],
  ['joebrown', 'https://www.joe-brown.com/sitemap.xml', null, /jacket.*__\d+$/],
  ['trekitt', 'https://www.trekitt.co.uk/sitemap.xml', null, /jacket.*__\d+$/],
  ['absolutesnow', 'https://www.absolute-snow.co.uk/sitemap.xml', /sitemap-products/, /jacket/],
  ['upandunder', 'https://www.upandunder.co.uk/sitemap.xml', null, /rab-.*jacket/],
  ['taunton', 'https://www.tauntonleisure.com/sitemap.xml', null, /jacket/],
  ['ultralight', 'https://ultralightoutdoorgear.co.uk/xmlsitemap.php', /type=products/, /jacket/],
  ['cotswold', 'https://www.cotswoldoutdoor.com/sitemap/index.xml', /product-\d+\.xml/, /rab-.*jacket/],
  ['snowrock', 'https://www.snowandrock.com/sitemap/index.xml', /product-\d+\.xml/, /jacket/],
  ['runnersneed', 'https://www.runnersneed.com/sitemap/index.xml', /product-\d+\.xml/, /shoes/],
];
const only = process.argv.slice(2);
for (const [id, root, child, pat] of S) {
  if (only.length && !only.includes(id)) continue;
  const all = await urls(root, child); const cand = all.filter((u) => pat.test(u)); const u = cand[Math.floor(cand.length / 3)];
  if (!u) { console.log(`${id}: no url (of ${all.length})`); continue; }
  const p = await get(u); fs.writeFileSync(`core/src/test/resources/pages/${id}.html`, p.b); fs.writeFileSync(`core/src/test/resources/pages/${id}.url`, u);
  const lds = [...p.b.matchAll(/<script[^>]*application\/ld\+json[^>]*>([\s\S]*?)<\/script>/g)].map((m) => { try { return JSON.parse(m[1]); } catch { return null; } }).filter(Boolean);
  const flat = lds.flatMap((x) => Array.isArray(x) ? x : x['@graph'] || [x]);
  const prods = flat.filter((x) => /Product/.test([].concat(x['@type']).join()));
  const d = prods.map((x) => { const o = [].concat(x.offers || []); const oo = o.flatMap((q) => q.offers || [q]); return `${[].concat(x['@type']).join()} brand=${x.brand?.name || x.brand} offers=${oo.length} hasVariant=${(x.hasVariant || []).length} offerKeys=${Object.keys(oo[0] || {}).join(',')} o0=${JSON.stringify(oo[0] || {}).slice(0, 160)} v0=${JSON.stringify((x.hasVariant || [])[0] || '').slice(0, 260)}`; });
  console.log(`\n${id}: ${p.s} ${p.b.length}b ${u}\n  ` + (d.join('\n  ') || 'NO JSON-LD PRODUCT'));
}
