// npm install --prefix .tools/fdroid-validation --no-audit --no-fund --ignore-scripts yaml@2.8.1
// Local structural checks; the native fdroid lint/scanner/build are separate Linux checks.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const YAML = require(path.join(root, '.tools/fdroid-validation/node_modules/yaml'));
function requireCondition(condition, message) { if (!condition) throw new Error(message); }
const read = file => fs.readFileSync(path.join(root, file), 'utf8').trim();
const properties = Object.fromEntries(read('app-info.properties').split(/\r?\n/)
    .filter(line => line && !line.startsWith('#')).map(line => {
        const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)];
    }));
const id = properties['application.id'];
const recipePath = `fdroid/metadata/${id}.yml`;
const document = YAML.parseDocument(read(recipePath), {uniqueKeys: true});
requireCondition(document.errors.length === 0, 'Recipe YAML errors: ' + document.errors.join('; '));
const recipe = document.toJS();
const build = recipe.Builds.at(-1);
requireCondition(build.versionName === properties['application.version.name'], 'Recipe versionName differs from app-info.properties');
requireCondition(build.versionCode === Number(properties['application.version.code']), 'Recipe versionCode differs from app-info.properties');
requireCondition(build.commit === `v${build.versionName}`, 'Release tag and versionName differ');
requireCondition(build.subdir === 'app' && build.gradle.includes('yes'), 'Expected the unflavoured app release build');
requireCondition(recipe.License === properties['license.spdx'], 'Recipe license differs from the project');
requireCondition(recipe.AuthorName === properties['developer.name'], 'Recipe author differs from the project');
requireCondition(recipe.SourceCode === properties['project.source'], 'Recipe source URL differs from the project');
requireCondition(recipe.IssueTracker === properties['project.issues'], 'Recipe issue tracker differs from the project');
requireCondition(recipe.Binaries.includes('/releases/download/v%v/') && recipe.Binaries.endsWith('keyra-%v.apk'), 'Expected a versioned developer-signed reference APK URL');
requireCondition(recipe.AllowedAPKSigningKeys.length > 0 && recipe.AllowedAPKSigningKeys.every(key => /^[a-f0-9]{64}$/.test(key)), 'Invalid signing certificate SHA-256');
for (const locale of ['en-US', 'ru-RU']) {
    const base = `fastlane/metadata/android/${locale}`;
    for (const [file, limit] of [['title.txt', 50], ['short_description.txt', 80], ['full_description.txt', 4000], [`changelogs/${build.versionCode}.txt`, 500]]) {
        const text = read(`${base}/${file}`);
        const length = [...text].length;
        requireCondition(length > 0 && length <= limit, `${locale}/${file}: ${length} characters, limit ${limit}`);
        console.log(`${locale}/${file}: ${length}/${limit} characters`);
    }
    const icon = fs.readFileSync(path.join(root, base, 'images/icon.png'));
    requireCondition(icon.readUInt32BE(16) === 512 && icon.readUInt32BE(20) === 512, `${locale}: expected a 512x512 PNG icon`);
}
const screenshots = fs.readdirSync(path.join(root, 'fastlane/metadata/android/en-US/images/phoneScreenshots')).filter(file => file.endsWith('.png'));
requireCondition(screenshots.length >= 2, 'Expected at least two inspected synthetic-data screenshots');
const audit = JSON.parse(read('fdroid/dependency-audit.json'));
requireCondition(audit.applicationId === id && audit.artifactCount === audit.artifacts.length, 'Dependency audit identity/count mismatch');
requireCondition(new Set(audit.artifacts.map(artifact => [artifact.coordinate, artifact.file, artifact.type, artifact.sha256].join('|'))).size === audit.artifactCount, 'Duplicate audited artifacts');
for (const artifact of audit.artifacts) {
    requireCondition(/^[a-f0-9]{64}$/.test(artifact.sha256), `Missing artifact hash: ${artifact.coordinate}`);
    requireCondition(artifact.licenses.length > 0 && artifact.licenses.every(license => ['Apache-2.0', 'MIT'].includes(license)), `Review license: ${artifact.coordinate}`);
}
console.log(`Recipe, two localized listings, ${screenshots.length} screenshots, and ${audit.artifactCount} license records passed local structural checks.`);
