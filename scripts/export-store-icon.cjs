// npm install --prefix .tools/icon-converter --no-audit --no-fund @resvg/resvg-js@2.6.2
// Rasterizes the existing adaptive launcher artwork; no new artwork is generated.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const { Resvg } = require(path.join(root, '.tools/icon-converter/node_modules/@resvg/resvg-js'));
const colors = Object.fromEntries([...fs.readFileSync(path.join(root, 'app/src/main/res/values/launcher.xml'), 'utf8')
    .matchAll(/<color name="([^"]+)">([^<]+)<\/color>/g)].map(match => [match[1], match[2]]));
let svg = fs.readFileSync(path.join(root, 'app/src/main/res/drawable/ic_keyra_foreground.xml'), 'utf8');
svg = svg.replace(/<vector\b[^>]*>/, '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108"><rect width="108" height="108" fill="white"/>')
    .replace('</vector>', '</svg>')
    .replace(/<group\b([^>]*)>/g, (_, attributes) => {
        const values = Object.fromEntries([...attributes.matchAll(/android:(\w+)="([^"]+)"/g)].map(match => [match[1], match[2]]));
        const px = values.pivotX || 0, py = values.pivotY || 0;
        return `<g transform="translate(${values.translateX || 0} ${values.translateY || 0}) translate(${px} ${py}) rotate(${values.rotation || 0}) scale(${values.scaleX || 1} ${values.scaleY || 1}) translate(${-px} ${-py})">`;
    }).replaceAll('</group>', '</g>')
    .replaceAll('android:fillColor', 'fill').replaceAll('android:pathData', 'd')
    .replaceAll('android:fillType="evenOdd"', 'fill-rule="evenodd"')
    .replace(/@color\/(\w+)/g, (_, name) => {
        if (!colors[name]) throw new Error(`Unknown launcher color: ${name}`);
        return colors[name];
    });
const png = new Resvg(svg).render().asPng();
for (const locale of ['en-US', 'ru-RU']) {
    const directory = path.join(root, 'fastlane/metadata/android', locale, 'images');
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(path.join(directory, 'icon.png'), png);
}
console.log('Exported existing launcher artwork as 512x512 store icons.');
