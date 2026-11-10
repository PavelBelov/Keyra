const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'images/vault-icons/PROVENANCE.json'), 'utf8'));
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const gitBlob = bytes => crypto.createHash('sha1').update(Buffer.from(`blob ${bytes.length}\0`)).update(bytes).digest('hex');
const normalized = file => Buffer.from(fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n'));
const render = process.argv.includes('--render');
const Resvg = render ? require(path.join(root, '.tools/icon-converter/node_modules/@resvg/resvg-js')).Resvg : null;
if (manifest.icons.length !== 69) throw new Error('Expected 69 database icons');
if (gitBlob(normalized(path.join(root, 'images/vault-icons/UPSTREAM-COPYING'))) !== manifest.copyingGitBlob) {
    throw new Error('Upstream COPYING changed');
}
for (const icon of manifest.icons) {
    const svg = normalized(path.join(root, 'images/vault-icons/icons', icon.file));
    const png = fs.readFileSync(path.join(root, icon.png));
    if (gitBlob(svg) !== icon.upstreamGitBlob || hash(svg) !== icon.svgLfSha256 || hash(png) !== icon.pngSha256) {
        throw new Error(`Provenance hash mismatch for ${icon.file}`);
    }
    if (render && hash(new Resvg(svg, {fitTo: {mode: 'width', value: 112}}).render().asPng()) !== hash(png)) {
        throw new Error(`PNG differs from its SVG source: ${icon.file}`);
    }
}
console.log(`Verified 69 source/PNG provenance records${render ? ' and 69 byte-identical renders' : ''}.`);
