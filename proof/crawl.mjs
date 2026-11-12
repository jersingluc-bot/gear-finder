// Proof: can a GitHub runner read sitemaps and product pages politely?
import zlib from 'node:zlib';
const UA = 'GearFinder/1.0 (personal price comparison)';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function get(url) {
  try {
    const res = await fetch(url, { headers: { 'user-agent': UA }, signal: AbortSignal.timeout(40000) });
    const buf = Buffer.from(await res.arrayBuffer());
    const body = (buf[0] === 0x1f && buf[1] === 0x8b ? zlib.gunzipSync(buf) : buf).toString('utf8');
    return { status: res.status, challenge: res.headers.get('cf-mitigated') || '', body };
  } catch (e) { return { status: 0, challenge: '', body: '', err: String(e) }; }
}
const locs = (xml) => [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1].replace(/&amp;/g, '&').trim());
const SHOPS = [
  { name: 'Ellis Brigham', root: 'https://www.ellis-brigham.com/sitemap.xml', product: /-\d{7,}$/ },
  { name: 'Trekitt', root: 'https://www.trekitt.co.uk/sitemap.xml', product: /__\d+$/ },
  { name: 'Cotswold Outdoor', root: 'https://www.cotswoldoutdoor.com/sitemap/index.xml', child: /product-\d+\.xml/, product: /\/p\// },
  { name: 'Alpine Trek', root: 'https://www.alpinetrek.co.uk/sitemap/sitemap.xml.gz', child: /sitemap_products/, product: /./ },
  { name: 'Needle Sports', page: 'https://www.needlesports.com/' },
  { name: 'Go Outdoors UCP', page: 'https://www.gooutdoors.co.uk/.well-known/ucp' },
];
for (const s of SHOPS) {
  if (s.page) { const r = await get(s.page); console.log(`${s.name}: ${r.status} ${r.challenge} ${r.body.length}b ${r.err || ''}`); continue; }
  let r = await get(s.root);
  console.log(`${s.name}: sitemap ${r.status} ${r.challenge} ${r.body.length}b ${r.err || ''}`);
  let urls = locs(r.body);
  if (s.child) { const c = urls.find((u) => s.child.test(u)); r = c ? await get(c) : { body: '', status: 'no child' }; console.log(`  child ${r.status} ${c}`); urls = locs(r.body); }
  const products = urls.filter((u) => s.product.test(u));
  console.log(`  ${products.length} product urls`);
  for (const u of [products[5], products[50], products[500]].filter(Boolean)) {
    await sleep(500);
    const p = await get(u);
    console.log(`  page ${p.status} ${p.challenge} ${p.body.length}b jsonld=${/application\/ld\+json/.test(p.body)} ${u}`);
  }
}
