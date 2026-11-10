const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {prepare, check} = require('./release.cjs');
const project = path.resolve(__dirname, '..');
function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'keyra-release-test-'));
    t.after(() => fs.rmSync(root, {recursive: true, force: true}));
    for (const file of ['app-info.properties', 'fdroid/metadata/xnet13.keyra.yml']) {
        fs.mkdirSync(path.dirname(path.join(root, file)), {recursive: true});
        fs.copyFileSync(path.join(project, file), path.join(root, file));
    }
    // Keep tests independent of the actual application's future release version.
    fs.writeFileSync(path.join(root, 'app-info.properties'),
        'application.id=xnet13.keyra\napplication.version.name=0.1.0\napplication.version.code=1\n');
    fs.writeFileSync(path.join(root, 'fdroid/metadata/xnet13.keyra.yml'),
        'Builds:\n  - versionName: 0.1.0\n    versionCode: 1\n    commit: v0.1.0\n    subdir: app\n    gradle:\n      - yes\n\n' +
        'AllowedAPKSigningKeys:\n  - ' + 'a'.repeat(64) + '\n\nCurrentVersion: 0.1.0\nCurrentVersionCode: 1\n');
    for (const locale of ['en-US', 'ru-RU']) notes(root, locale, 1, 'Initial release.');
    return root;
}
function notes(root, locale, code, text) {
    const file = path.join(root, `fastlane/metadata/android/${locale}/changelogs/${code}.txt`);
    fs.mkdirSync(path.dirname(file), {recursive: true});
    fs.writeFileSync(file, text + '\n');
}
function git(root, ...args) { return execFileSync('git', args, {cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']}).trim(); }
function commit(root) {
    git(root, 'add', '.');
    git(root, '-c', 'user.name=Keyra release test', '-c', 'user.email=release-test@example.test', 'commit', '-qm', 'Release fixture');
}
test('new version increments Android code, appends recipe, and requires completed changelogs', t => {
    const root = fixture(t);
    const result = prepare(root, '0.2.0');
    assert.equal(result.code, 2);
    const recipe = fs.readFileSync(path.join(root, 'fdroid/metadata/xnet13.keyra.yml'), 'utf8');
    assert.match(recipe, /versionName: 0.1.0/);
    assert.match(recipe, /versionName: 0.2.0\n    versionCode: 2\n    commit: v0.2.0/);
    assert.match(recipe, /CurrentVersion: 0.2.0\nCurrentVersionCode: 2/);
    assert.throws(() => check(root), /finish the changelog/);
    notes(root, 'en-US', 2, 'New features.');
    notes(root, 'ru-RU', 2, 'Новые возможности.');
    assert.equal(check(root).apk, 'keyra-0.2.0.apk');
    prepare(root, '0.2.0');
    assert.match(fs.readFileSync(path.join(root, result.changelogs[1]), 'utf8'), /Новые возможности/);
});
test('rejects rollback, invalid version, reused Android code, and changelog collision before writes', t => {
    const root = fixture(t);
    const original = fs.readFileSync(path.join(root, 'app-info.properties'), 'utf8');
    for (const version of ['0.0.9', '01.2.3', '0.2.0-beta.1', '1.2']) assert.throws(() => prepare(root, version));
    assert.throws(() => prepare(root, '0.2.0', 1), /increase versionCode/);
    assert.throws(() => prepare(root, '0.2.0', 2100000001), /Invalid versionCode/);
    notes(root, 'en-US', 2, 'Existing release.');
    assert.throws(() => prepare(root, '0.2.0'), /already exists/);
    assert.equal(fs.readFileSync(path.join(root, 'app-info.properties'), 'utf8'), original);
});
test('changelog checks reject empty, missing, placeholder, and oversized notes', t => {
    const root = fixture(t);
    for (const text of ['', 'TODO: release', 'a'.repeat(501)]) {
        notes(root, 'en-US', 1, text);
        assert.throws(() => check(root));
    }
    fs.unlinkSync(path.join(root, 'fastlane/metadata/android/en-US/changelogs/1.txt'));
    assert.throws(() => check(root), /ENOENT/);
});
test('tag must match both source version and exact checked out commit', t => {
    const root = fixture(t);
    git(root, 'init', '-q'); commit(root); git(root, 'tag', 'v0.1.0');
    assert.equal(check(root, {tag: 'v0.1.0', history: true}).code, 1);
    assert.throws(() => check(root, {tag: 'v0.2.0'}), /differs from source/);
    notes(root, 'en-US', 1, 'Changed source.'); commit(root);
    assert.throws(() => check(root, {tag: 'v0.1.0'}), /exact tagged commit/);
});
test('history prevents versionCode reuse and notes are generated from matching bilingual changelogs', t => {
    const root = fixture(t);
    git(root, 'init', '-q'); commit(root); git(root, 'tag', 'v0.1.0');
    prepare(root, '0.2.0');
    notes(root, 'en-US', 2, 'Second release.'); notes(root, 'ru-RU', 2, 'Второй выпуск.');
    commit(root); git(root, 'tag', 'v0.2.0');
    const result = check(root, {tag: 'v0.2.0', history: true, outputDir: 'dist/release'});
    assert.equal(result.code, 2);
    const markdown = fs.readFileSync(path.join(root, 'dist/release/release-notes.md'), 'utf8');
    assert.match(markdown, /## Русский\n\nВторой выпуск/); assert.match(markdown, /## English\n\nSecond release/);
    const appPath = path.join(root, 'app-info.properties');
    fs.writeFileSync(appPath, fs.readFileSync(appPath, 'utf8').replace('version.code=2', 'version.code=1'));
    commit(root); git(root, 'tag', '-f', 'v0.2.0');
    assert.throws(() => check(root, {tag: 'v0.2.0', history: true}), /versionCode must be greater/);
});
