// npm install --prefix .tools/icon-converter --no-audit --no-fund @resvg/resvg-js@2.6.2
// node images/vault-icons/generate.cjs
const fs = require('node:fs');
const path = require('node:path');
const { Resvg } = require('../.tools/icon-converter/node_modules/@resvg/resvg-js');
const root = path.resolve(__dirname, '..');
const source = path.join(root, 'images/vault-icons/icons');
const target = path.join(root, 'app/src/main/res/drawable-xxxhdpi');
fs.mkdirSync(target, { recursive: true });
const files = fs.readdirSync(source).filter(name => /^C\d\d_.*\.svg$/.test(name)).sort();
if (files.length !== 69 || files.some((name, id) => Number(name.slice(1, 3)) !== id)) {
    throw new Error('Expected exactly one KDBX icon for each KDBX ID 0–68');
}
for (const file of files) {
    const svg = fs.readFileSync(path.join(source, file));
    const png = new Resvg(svg, { fitTo: { mode: 'width', value: 112 } }).render().asPng();
    fs.writeFileSync(path.join(target, `vault_icon_${Number(file.slice(1, 3))}.png`), png);
}
console.log(`Generated ${files.length} icons (28dp at xxxhdpi)`);
