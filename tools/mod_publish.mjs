// Build and upload the selected player archives inside one approved GitHub Actions job.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { annotate } from './maven_publication.mjs';
import { checksumLines, recordedCurseforgeFiles, releaseChangelog, releaseMetadata, verifyArtifacts } from './mod_release.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** Resolve plan entries against the registry instead of using plan data as commands or paths. */
export function selectedEntries(json, entries, key) {
    const selection = JSON.parse(json ?? '').include;
    if (!Array.isArray(selection)) throw new Error('Expected a release plan with an include array');
    const seen = new Set();
    return selection.map(entry => {
        const selected = entries.find(candidate => candidate[key] === entry[key]);
        if (!selected || seen.has(selected[key])) throw new Error(`Unknown or duplicate release entry: ${entry[key]}`);
        seen.add(selected[key]);
        return selected;
    });
}

export function buildArguments(targets) {
    if (!targets.length) throw new Error('No adapters selected for building');
    return ['gradlew', `-PcompixelTargets=${targets.map(target => target.target).join(',')}`,
        ...targets.map(target => `:minecraft:${target.adapter}:build`), '--continue', '--max-workers=2', '--console=plain'];
}

/** Each CurseForge upload verifies its adapter directory; GitHub uses the combined directory. */
export function stageArtifacts(metadata, targets, directory = root) {
    const combined = path.join(directory, 'dist/github');
    fs.mkdirSync(combined, { recursive: true });
    for (const target of targets) {
        const staged = path.join(directory, 'dist', target.adapter);
        fs.mkdirSync(staged, { recursive: true });
        for (const { file } of metadata.uploads.filter(upload => upload.adapter === target.adapter)) {
            fs.copyFileSync(path.join(directory, target.project, 'build/release', file), path.join(staged, file));
        }
        const sums = `${target.adapter}.sha256`;
        fs.writeFileSync(path.join(staged, sums), checksumLines(metadata, target.adapter, staged));
        verifyArtifacts(metadata, target.adapter, staged);
        for (const file of fs.readdirSync(staged)) fs.copyFileSync(path.join(staged, file), path.join(combined, file));
    }
}

export function curseforgeInputs(metadata, upload, changelog, { project, token, repository, sha }) {
    return {
        'curseforge-id': project, 'curseforge-token': token,
        files: `dist/${upload.adapter}/${upload.file}`,
        name: `CompixelUI ${metadata.version} / ${upload.adapter} / ${upload.variant}`,
        version: metadata.version, 'version-type': metadata.type,
        changelog: `${changelog}\n\n**Installation:** ${upload.installation}\n` +
            `[Runtime compatibility](https://github.com/${repository}/blob/${sha}/docs/en/compatibility.md)`,
        loaders: upload.loader, 'game-versions': upload.target, 'game-version-filter': 'none',
        java: upload.java, environment: 'client | server', dependencies: upload.dependencies,
        // An uncertain upload response must not trigger an automatic duplicate upload.
        'retry-attempts': '1', 'retry-delay': '10000', 'fail-mode': 'fail',
    };
}

/** mc-publish reads its own action.yml defaults; only this file's explicit inputs enter the child. */
export function runMcPublish(actionDirectory, inputs, directory = root, environment = process.env) {
    const env = Object.fromEntries(Object.entries(environment).filter(([key]) => !key.toUpperCase().startsWith('INPUT_')));
    for (const [key, value] of Object.entries(inputs)) env[`INPUT_${key.toUpperCase()}`] = value;
    execFileSync(process.execPath, [path.resolve(actionDirectory, 'dist/index.js')], { cwd: directory, env, stdio: 'inherit' });
}

/** Keep uploads independent, record each success immediately, and report every failed file at the end. */
export async function publishCurseforgeFiles(uploads, { recorded, publish, record, notice = message => console.log(message) }) {
    const failures = [];
    for (const upload of uploads) {
        console.log(`::group::CurseForge ${upload.adapter} / ${upload.variant}`);
        try {
            if (await recorded(upload)) {
                notice(`${upload.file} has a successful upload receipt; skipped it.`);
                continue;
            }
            await publish(upload);
            await record(upload);
            notice(`${upload.file} was uploaded and its receipt was recorded.`);
        } catch (error) {
            failures.push(upload.file);
            annotate('error', 'CurseForge file failed', `${upload.file}: ${error.message}`);
        } finally {
            console.log('::endgroup::');
        }
    }
    if (failures.length) throw new Error(`${failures.length} CurseForge files failed: ${failures.join(', ')}`);
}

async function recordUpload(ref, file) {
    for (let attempt = 1; attempt <= 3; attempt++) {
        try {
            execFileSync('git', ['push', 'origin', `${process.env.GITHUB_SHA}:${ref}`], { cwd: root, stdio: 'inherit' });
            return;
        } catch {
            if (attempt < 3) await delay(10_000);
        }
    }
    throw new Error(`${file} is on CurseForge, but ${ref} could not be created. Create it before retrying this job.`);
}

async function main() {
    const [command, actionDirectory] = process.argv.slice(2);
    const metadata = releaseMetadata();
    if (command === 'build') {
        const targets = selectedEntries(process.env.BUILDS, metadata.targets, 'adapter');
        execFileSync('bash', buildArguments(targets), { cwd: root, stdio: 'inherit' });
        stageArtifacts(metadata, targets);
    } else if (command === 'curseforge') {
        if (!actionDirectory) throw new Error('The pinned mc-publish checkout is required');
        const uploads = selectedEntries(process.env.UPLOADS, metadata.uploads, 'file');
        const context = {
            project: process.env.CURSEFORGE_ID, token: process.env.CURSEFORGE_TOKEN,
            repository: process.env.GITHUB_REPOSITORY, sha: process.env.GITHUB_SHA,
        };
        if (!/^[0-9a-f]{40}$/.test(context.sha ?? '') || !/^[\w.-]+\/[\w.-]+$/.test(context.repository ?? '')) {
            throw new Error('GITHUB_SHA and GITHUB_REPOSITORY are required');
        }
        const changelog = releaseChangelog(metadata.version);
        await publishCurseforgeFiles(uploads, {
            recorded: upload => recordedCurseforgeFiles(context.project, metadata.tag).has(upload.file),
            publish: upload => {
                if (!context.token) throw new Error('Set CURSEFORGE_TOKEN in the mod-publish environment');
                verifyArtifacts(metadata, upload.adapter, path.join(root, 'dist', upload.adapter));
                runMcPublish(actionDirectory, curseforgeInputs(metadata, upload, changelog, context));
            },
            record: upload => recordUpload(`refs/curseforge/${context.project}/${metadata.tag}/${upload.file}`, upload.file),
            notice: message => annotate('notice', 'CurseForge upload receipt', message),
        });
    } else {
        throw new Error('Usage: mod_publish.mjs build | curseforge <mc-publish-checkout>');
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
