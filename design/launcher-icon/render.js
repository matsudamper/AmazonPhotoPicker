const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const OUT = '/home/user/AmazonPhotoPicker/design/launcher-icon';
const meta = JSON.parse(fs.readFileSync('meta.json', 'utf8'));
const dens = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
const art = m => `<svg x="2" y="2" width="44" height="44" viewBox="18 18 72 72">${m.bg}${m.fg}</svg>`;
const legacy = (m, round) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">
 <defs><clipPath id="c">${round ? '<circle cx="24" cy="24" r="22"/>' : '<rect x="2" y="2" width="44" height="44" rx="5"/>'}</clipPath></defs>
 <g clip-path="url(#c)">${art(m)}</g></svg>`;
const store = m => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72">${m.bg}${m.fg}</svg>`;
(async () => {
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
  const p = await b.newPage();
  async function shot(svg, size, file) {
    await p.setContent(`<html><body style="margin:0;background:transparent">${svg.replace('<svg ', `<svg width="${size}" height="${size}" `)}</body></html>`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await p.locator('svg').first().screenshot({ path: file, omitBackground: true });
  }
  for (const m of meta) {
    for (const [d, s] of Object.entries(dens)) {
      await shot(legacy(m, false), s, `${OUT}/${m.id}/res/mipmap-${d}/ic_launcher.png`);
      await shot(legacy(m, true), s, `${OUT}/${m.id}/res/mipmap-${d}/ic_launcher_round.png`);
    }
  }
  // 確認用シート
  const cell = (inner) => `<div style="width:160px;height:160px">${inner}</div>`;
  let html = '<html><body style="margin:0;padding:10px;background:#ddd;display:grid;grid-template-columns:repeat(4,160px);gap:10px">';
  for (const m of meta) {
    html += cell(`<svg width="160" height="160" viewBox="0 0 108 108"><defs><clipPath id="k${m.id}"><circle cx="54" cy="54" r="36"/></clipPath></defs><g clip-path="url(#k${m.id})">${m.bg}${m.fg}</g><circle cx="54" cy="54" r="33" fill="none" stroke="red" stroke-width=".3"/></svg>`);
    html += cell(`<svg width="160" height="160" viewBox="0 0 108 108" style="color:#1B3A6B;background:#D7E3FF;border-radius:50%">${m.mono}</svg>`);
    html += cell(`<svg width="160" height="160" viewBox="0 0 108 108" style="color:#D7E3FF;background:#1B2B45">${m.mono}</svg>`);
    html += cell(`<img width="160" src="file://${OUT}/${m.id}/res/mipmap-xxxhdpi/ic_launcher_round.png">`);
  }
  await p.setContent(html + '</body></html>');
  await p.screenshot({ path: 'sheet.png', fullPage: true });
  await b.close();
})();
