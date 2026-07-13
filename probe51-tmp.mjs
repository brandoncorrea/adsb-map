import { chromium } from 'playwright';
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
await page.goto('http://localhost:8280/index.html', { waitUntil: 'load' });
await page.waitForTimeout(7000);
const state = () => page.evaluate(() => {
  const r = document.querySelector('.adsb-stack-ruler');
  const v = document.querySelector('.adsb-stack-ruler-view');
  return { zoom: +(+r.dataset.zoom).toFixed(2),
           snapshot: `${Math.round(+r.dataset.minFt)}-${Math.round(+r.dataset.maxFt)}ft`,
           scrollLeft: Math.round(v.scrollLeft), track: v.scrollWidth,
           markers: [...document.querySelectorAll('.adsb-stack-overflow')]
             .map(e => `${e.className.includes('above') ? '▲' : '▼'}${e.dataset.count}`).join(' ') || 'none' };
});
const rb = await page.locator('.adsb-stack-ruler-view').boundingBox();
const wheel = async (dir, n, at) => { for (let i = 0; i < n; i++) {
  await page.mouse.move(rb.x + rb.width * at, rb.y + rb.height / 2);
  await page.mouse.wheel(0, dir); await page.waitForTimeout(45); } };

for (const [name, at] of [['the surface end (scroll never moves)', 0.15], ['the ceiling end', 0.9], ['the middle', 0.5]]) {
  await wheel(-100, 12, at); await page.waitForTimeout(350);
  const zin = await state();
  await wheel(100, 14, at);  await page.waitForTimeout(600);
  const zout = await state();
  const clean = zout.zoom === 1 && zout.snapshot === '0-45000ft';
  console.log(`zoom in/out at ${name}:`);
  console.log(`   in  -> ${zin.snapshot} (${zin.markers})`);
  console.log(`   out -> ${zout.snapshot} (${zout.markers})  ${clean ? '✓ whole sky, no stale marker' : '✗ STALE'}`);
}
await browser.close();
