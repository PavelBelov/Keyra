// Dependency-free release preparation and validation. Version data stays in Git
// so F-Droid can build exactly the same tagged sources without CI overrides.
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const LOCALES = ['en-US', 'ru-RU'];
function assert(value, message) { if (!value) throw new Error(message); }
function read(root, file) { return fs.readFileSync(path.join(root, file), 'utf8').replace(/\r\n/g, '\n'); }
function properties(text) {
    const result = {};
    for (const line of text.split('\n').filter(line => line.trim() && !line.startsWith('#'))) {
        const at = line.indexOf('=');
        assert(at > 0, 'Invalid app-info.properties line');
        const key = line.slice(0, at).trim();
        assert(!Object.hasOwn(result, key), `Duplicate property: ${key}`);
        result[key] = line.slice(at + 1).trim();
    }
    return result;
}
function info(root) {
    const data = properties(read(root, 'app-info.properties'));
    const version = data['application.version.name'];
    assert(/^[1-9]\d*$/.test(data['application.version.code']), 'versionCode must be a positive decimal integer');
    const code = Number(data['application.version.code']);
    assert(VERSION.test(version), 'Use a stable version MAJOR.MINOR.PATCH (for example 0.1.0)');
    assert(Number.isSafeInteger(code) && code > 0 && code <= 2100000000, 'Invalid Android versionCode');
    assert(/^[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+$/.test(data['application.id']), 'Invalid application ID');
    return {version, code, applicationId: data['application.id'], tag: `v${version}`, apk: `keyra-${version}.apk`};
}
function compareVersions(a, b) {
    const left = a.split('.').map(BigInt), right = b.split('.').map(BigInt);
    for (let i = 0; i < 3; i++) { if (left[i] !== right[i]) return left[i] > right[i] ? 1 : -1; }
    return 0;
}
function replaceOne(text, regex, replacement) {
    assert([...text.matchAll(new RegExp(regex.source, regex.flags.includes('g') ? regex.flags : regex.flags + 'g'))].length === 1,
        `Expected exactly one match for ${regex}`);
    return text.replace(regex, replacement);
}
function prepare(root, version, requestedCode) {
    const current = info(root);
    assert(VERSION.test(version), 'Use a stable version MAJOR.MINOR.PATCH');
    assert(compareVersions(version, current.version) >= 0, 'Cannot decrease the application version');
    const changed = version !== current.version;
    const code = requestedCode === undefined ? current.code + Number(changed) : Number(requestedCode);
    assert(Number.isSafeInteger(code) && code > 0 && code <= 2100000000, 'Invalid versionCode');
    assert(changed ? code > current.code : code === current.code, 'A new version must increase versionCode; an existing version must keep it');
    let app = read(root, 'app-info.properties');
    app = replaceOne(app, /^application\.version\.name=.+$/m, `application.version.name=${version}`);
    app = replaceOne(app, /^application\.version\.code=.+$/m, `application.version.code=${code}`);
    const recipePath = `fdroid/metadata/${current.applicationId}.yml`;
    let recipe = read(root, recipePath);
    const start = recipe.search(/^Builds:\n/m);
    const end = recipe.indexOf('\nAllowedAPKSigningKeys:', start);
    assert(start >= 0 && end > start, 'Unsupported recipe layout: review Builds/AllowedAPKSigningKeys');
    const section = recipe.slice(start, end);
    const entries = [...section.matchAll(/^  - versionName: (.+)$/gm)];
    assert(entries.length > 0, 'Missing F-Droid build entry');
    const last = section.slice(entries.at(-1).index).trimEnd();
    assert(entries.at(-1)[1] === current.version && new RegExp(`^    versionCode: ${current.code}$`, 'm').test(last),
        'Current F-Droid recipe does not match app-info.properties');
    if (changed) {
        assert(!entries.some(entry => entry[1] === version), 'Recipe already contains this version');
        let next = replaceOne(last, /^  - versionName: .+$/m, `  - versionName: ${version}`);
        next = replaceOne(next, /^    versionCode: .+$/m, `    versionCode: ${code}`);
        next = replaceOne(next, /^    commit: .+$/m, `    commit: v${version}`);
        recipe = recipe.slice(0, end).trimEnd() + '\n\n' + next + '\n' + recipe.slice(end);
    }
    recipe = replaceOne(recipe, /^CurrentVersion: .+$/m, `CurrentVersion: ${version}`);
    recipe = replaceOne(recipe, /^CurrentVersionCode: .+$/m, `CurrentVersionCode: ${code}`);
    const logs = LOCALES.map(locale => `fastlane/metadata/android/${locale}/changelogs/${code}.txt`);
    // Check all collisions before writing any version files.
    if (changed) for (const file of logs) assert(!fs.existsSync(path.join(root, file)), `Changelog already exists: ${file}`);
    fs.writeFileSync(path.join(root, 'app-info.properties'), app);
    fs.writeFileSync(path.join(root, recipePath), recipe);
    for (const file of logs) {
        if (!fs.existsSync(path.join(root, file))) {
            fs.mkdirSync(path.dirname(path.join(root, file)), {recursive: true});
            fs.writeFileSync(path.join(root, file), 'TODO: Replace with release notes (maximum 500 characters).\n');
        }
    }
    return {...info(root), changelogs: logs};
}
function check(root, {tag, history = false, outputDir} = {}) {
    const result = info(root);
    assert(!tag || tag === result.tag, `Tag ${tag} differs from source version ${result.tag}`);
    if (tag) {
        assert(/^v\d+\.\d+\.\d+$/.test(tag), 'Only stable vMAJOR.MINOR.PATCH tags are supported');
        const git = args => execFileSync('git', args, {cwd: root, encoding: 'utf8'}).trim();
        assert(git(['rev-parse', `refs/tags/${tag}^{commit}`]) === git(['rev-parse', 'HEAD']), 'Checkout must be the exact tagged commit');
        if (history) {
            for (const previous of git(['tag', '--list', 'v*']).split('\n').filter(Boolean)) {
                if (previous === tag || !VERSION.test(previous.slice(1))) continue;
                assert(compareVersions(result.version, previous.slice(1)) > 0, `Version must be newer than existing ${previous}`);
                const previousInfo = properties(git(['show', `${previous}:app-info.properties`]));
                const previousCode = Number(previousInfo['application.version.code']);
                assert(Number.isSafeInteger(previousCode) && result.code > previousCode, `versionCode must be greater than ${previousCode} from ${previous}`);
            }
        }
    }
    const recipe = read(root, `fdroid/metadata/${result.applicationId}.yml`);
    const fingerprint = recipe.match(/^  - ([a-f0-9]{64})$/m)?.[1];
    assert(fingerprint, 'Missing F-Droid signing certificate fingerprint');
    result.certificateSha256 = fingerprint;
    const notes = LOCALES.map(locale => {
        const text = read(root, `fastlane/metadata/android/${locale}/changelogs/${result.code}.txt`).trim();
        assert(text && [...text].length <= 500, `${locale}: changelog must contain 1–500 characters`);
        assert(!/\b(TODO|TBD|PLACEHOLDER)\b/i.test(text), `${locale}: finish the changelog before release`);
        return {locale, text};
    });
    if (outputDir) {
        const destination = path.resolve(root, outputDir);
        fs.mkdirSync(destination, {recursive: true});
        const markdown = `# Keyra ${result.version}\n\n## Русский\n\n${notes[1].text}\n\n## English\n\n${notes[0].text}\n`;
        fs.writeFileSync(path.join(destination, 'release-notes.md'), markdown);
        const commit = execFileSync('git', ['rev-parse', 'HEAD'], {cwd: root, encoding: 'utf8'}).trim();
        fs.writeFileSync(path.join(destination, 'release.json'), JSON.stringify({...result, commit}, null, 2) + '\n');
    }
    return result;
}
if (require.main === module) {
    try {
        const root = path.resolve(__dirname, '..');
        const [command, ...args] = process.argv.slice(2);
        assert(['prepare', 'check'].includes(command), 'Usage: node scripts/release.cjs prepare VERSION [--code N] | check [--tag vVERSION] [--history] [--output-dir DIR]');
        const version = command === 'prepare' ? args.shift() : undefined;
        const allowed = command === 'prepare' ? ['--code'] : ['--tag', '--history', '--output-dir'];
        const options = {};
        while (args.length) {
            const flag = args.shift();
            assert(allowed.includes(flag) && !Object.hasOwn(options, flag), `Unknown or repeated option: ${flag}`);
            if (flag === '--history') { options[flag] = true; continue; }
            const argument = args.shift();
            assert(argument && !argument.startsWith('--'), `Missing value for ${flag}`);
            options[flag] = argument;
        }
        const value = command === 'prepare' ? prepare(root, version, options['--code']) : check(root, {
            tag: options['--tag'], history: options['--history'], outputDir: options['--output-dir']
        });
        if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT,
            Object.entries(value).filter(([, v]) => typeof v !== 'object').map(([k, v]) => `${k}=${v}\n`).join(''));
        console.log(JSON.stringify(value, null, 2));
        if (command === 'prepare') console.log('Edit both changelogs, review the diff, commit, then create and push the tag. No commit or tag was created.');
    } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = {prepare, check, info};
