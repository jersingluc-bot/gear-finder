import zlib from 'node:zlib';
const UA = 'GearFinder/1.0 (personal price comparison)';
async function get(url) { try { const res = await fetch(url, { headers: { 'user-agent': UA }, signal: AbortSignal.timeout(40000) }); const buf = Buffer.from(await res.arrayBuffer()); return { s: res.status, b: (buf[0] === 0x1f && buf[1] === 0x8b ? zlib.gunzipSync(buf) : buf).toString('utf8') }; } catch (e) { return { s: 0, b: '' }; } }
const locs = (x) => [...x.matchAll(/<loc>\s*([^<\s]+)\s*<\/loc>/g)].map((m) => m[1].replace(/&amp;/g, '&'));
for (const root of process.argv.slice(2)) {
  const r = await get(root); const l = locs(r.b); const idx = /<sitemapindex/.test(r.b);
  console.log(`\n== ${root} ${r.s} ${idx ? 'INDEX' : 'urls'} n=${l.length}`);
  const pick = (a, n) => [...new Set([0, 1, 2, Math.floor(a.length / 2), a.length - 1].slice(0, n).map((i) => a[i]))].filter(Boolean);
  for (const u of pick(l, idx ? 5 : 4)) console.log('  ' + u);
  if (idx) { const child = l.find((u) => /product/i.test(u)) || l[0]; const c = await get(child); const cl = locs(c.b); console.log(`  child ${child} n=${cl.length}`); for (const u of pick(cl, 3)) console.log('    ' + u); }
}
