// Read-only release preflight. Never overwrite or repair a partially published version.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export function properties(file) {
    return Object.fromEntries(fs.readFileSync(file, 'utf8').split(/\r?\n/)
        .filter(line => line.trim() && !line.trimStart().startsWith('#'))
        .map(line => {
            const separator = line.indexOf('=');
            if (separator < 1) throw new Error(`Invalid property in ${file}: ${line}`);
            return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
        }));
}

export function runtimePublication(bundle, version) {
    if (!['standard', 'vulkan', 'kotlin'].includes(bundle)) throw new Error(`Unknown bundle: ${bundle}`);
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(version ?? '')) throw new Error(`Invalid runtime version: ${version}`);
    const artifact = bundle === 'kotlin' ? 'composemc-kotlin' : `composemc-runtime-${bundle}`;
    const stem = `${artifact}-${version}`;
    return {
        artifact, version,
        directory: `dev/composemc/${artifact}/${version}`,
        files: [`${stem}.jar`, `${stem}.pom`, `${stem}-sources.jar`, `${stem}-javadoc.jar`],
    };
}

export async function inspectPublication(repository, publication) {
    const base = `${repository.replace(/\/$/, '')}/${publication.directory}/`;
    const files = publication.files.flatMap(name => [{ name, checksum: false }, { name: `${name}.sha256`, checksum: true }]);
    const states = await Promise.all(files.map(async ({ name, checksum }) => {
        const response = await fetch(base + name, {
            method: checksum ? 'GET' : 'HEAD', signal: AbortSignal.timeout(30_000), redirect: 'error',
        });
        if (response.status === 404) return { name, present: false };
        if (response.status !== 200) throw new Error(`Cannot check ${name}: HTTP ${response.status}`);
        if (checksum && !/^[a-f0-9]{64}$/i.test((await response.text()).trim())) {
            throw new Error(`Invalid SHA-256 sidecar: ${base}${name}. Inspect this publication before retrying.`);
        }
        return { name, present: true };
    }));
    if (states.every(file => !file.present)) return 'missing';
    if (states.every(file => file.present)) return 'complete';
    const missing = states.filter(file => !file.present).map(file => file.name).join(', ');
    throw new Error(`Incomplete publication ${publication.artifact} ${publication.version}; missing: ${missing}. ` +
        'Inspect and remove only this incomplete version in Reposilite before retrying. Complete versions must stay unchanged.');
}

async function main() {
    const args = process.argv.slice(2);
    const requireComplete = args.includes('--require-complete');
    const bundles = args.filter(arg => arg !== '--require-complete');
    if (!bundles.length) bundles.push('standard', 'vulkan', 'kotlin');
    const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
    const config = properties(path.join(root, 'gradle.properties'));
    const missing = [];
    for (const bundle of bundles) {
        const publication = runtimePublication(bundle, config[`runtime_${bundle}_version`]);
        const state = await inspectPublication(process.env.MAVEN_RELEASES ?? 'https://maven.wintercogs.com/releases', publication);
        console.error(`${publication.artifact} ${publication.version}: ${state}`);
        if (state === 'missing') missing.push(bundle);
    }
    if (requireComplete && missing.length) throw new Error(`Publication is still missing: ${missing.join(', ')}`);
    if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT, `bundles=${missing.join(' ')}\n`);
    // The runtime job captures this afresh immediately before each upload, including on job retries.
    console.log(missing.join(' '));
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
