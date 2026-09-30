// Stage parallel build artifacts and publish both destinations inside one approved job.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { randomUUID } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { annotate } from './maven_publication.mjs';
import {
    checksumLines, githubReleaseFiles, recordedCurseforgeFiles, releaseChangelog, releaseMetadata, verifyArtifacts, verifyTag,
} from './mod_release.mjs';

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

/** Each matrix job stages just its adapter; the approved publisher gathers the downloaded directories. */
export function stageArtifacts(metadata, targets, directory = root) {
    for (const target of targets) {
        const staged = path.join(directory, 'dist', target.adapter);
        fs.mkdirSync(staged, { recursive: true });
        for (const { file } of metadata.uploads.filter(upload => upload.adapter === target.adapter)) {
            fs.copyFileSync(path.join(directory, target.project, 'build/release', file), path.join(staged, file));
        }
        const sums = `${target.adapter}.sha256`;
        fs.writeFileSync(path.join(staged, sums), checksumLines(metadata, target.adapter, staged));
        verifyArtifacts(metadata, target.adapter, staged);
    }
}

export function gatherArtifacts(metadata, targets, directory = root) {
    const combined = path.join(directory, 'dist/github');
    fs.mkdirSync(combined, { recursive: true });
    for (const target of targets) {
        const staged = path.join(directory, 'dist', target.adapter);
        verifyArtifacts(metadata, target.adapter, staged);
        const files = metadata.uploads.filter(upload => upload.adapter === target.adapter).map(upload => upload.file);
        for (const file of [...files, `${target.adapter}.sha256`]) {
            fs.copyFileSync(path.join(staged, file), path.join(combined, file));
        }
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

export function githubInputs(metadata, files, changelog, { githubToken, repository, sha }) {
    return {
        'github-token': githubToken, 'github-tag': metadata.tag, 'github-commitish': sha,
        files: files.join('\n'), name: `CompixelUI ${metadata.version}`, version: metadata.version, 'version-type': metadata.type,
        changelog: `${changelog}\n\nChoose the file matching your Minecraft version and loader. Install **one** variant only:\n` +
            '- Standard: requires a compatible external Kotlin provider.\n' +
            '- `with-kotlin`: includes Kotlin; do not combine it with KFF or another Kotlin runtime.\n\n' +
            `[Runtime compatibility](https://github.com/${repository}/blob/${sha}/docs/en/compatibility.md)\n` +
            'SHA-256 checksums are provided in `SHA256SUMS`.',
        loaders: [...new Set(metadata.targets.map(target => target.loader))].join('\n'),
        'game-versions': metadata.targets.map(target => target.target).join('\n'), 'game-version-filter': 'none',
        environment: 'client | server', 'retry-attempts': '1', 'retry-delay': '10000', 'fail-mode': 'fail',
    };
}

/** mc-publish reads its own action.yml defaults; only this file's explicit inputs enter the child. */
export async function runMcPublish(actionDirectory, inputs, directory = root, environment = process.env) {
    const env = Object.fromEntries(Object.entries(environment).filter(([key]) => !key.toUpperCase().startsWith('INPUT_')));
    for (const [key, value] of Object.entries(inputs)) env[`INPUT_${key.toUpperCase()}`] = value;
    // Concurrent action processes must not interleave multiline output/state commands in one file.
    const temporary = path.join(os.tmpdir(), `compixel-publish-${randomUUID()}`);
    const output = `${temporary}.output`, state = `${temporary}.state`;
    fs.writeFileSync(output, '', { flag: 'wx' });
    fs.writeFileSync(state, '', { flag: 'wx' });
    env.GITHUB_OUTPUT = output;
    env.GITHUB_STATE = state;
    try {
        await new Promise((resolve, reject) => {
            const child = spawn(process.execPath, [path.resolve(actionDirectory, 'dist/index.js')], { cwd: directory, env, stdio: 'inherit' });
            child.once('error', reject);
            child.once('close', (code, signal) => {
                if (code === 0) resolve();
                else reject(Object.assign(new Error(`mc-publish failed (${signal ?? code})`), { status: code }));
            });
        });
    } finally {
        fs.unlinkSync(output);
        fs.unlinkSync(state);
    }
}

/** Two uploads at a time, with a fresh receipt check and an immediate receipt for every success. */
export async function publishCurseforgeFiles(uploads, { recorded, publish, record, notice = message => console.log(message) }) {
    const failures = [];
    let next = 0;
    const worker = async () => {
        while (next < uploads.length) {
            const upload = uploads[next++];
            try {
                if (await recorded(upload)) {
                    notice(`${upload.file} has a successful upload receipt; skipped it.`);
                    continue;
                }
                notice(`Publishing ${upload.file}.`);
                await publish(upload);
                await record(upload);
                notice(`${upload.file} was uploaded and its receipt was recorded.`);
            } catch (error) {
                failures.push(upload.file);
                annotate('error', 'CurseForge file failed', `${upload.file}: ${error.message}`);
            }
        }
    };
    await Promise.all([worker(), worker()]);
    if (failures.length) throw new Error(`${failures.length} CurseForge files failed: ${failures.join(', ')}`);
}

/** Both destinations finish even if the other fails. */
export async function publishDestinations(destinations) {
    const names = Object.keys(destinations);
    const results = await Promise.allSettled(names.map(name => Promise.resolve().then(destinations[name])));
    const failures = results.flatMap((result, index) => result.status === 'rejected' ? [`${names[index]}: ${result.reason.message}`] : []);
    if (failures.length) throw new Error(failures.join('\n'));
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
    const [command, argument] = process.argv.slice(2);
    const metadata = releaseMetadata();
    if (command === 'stage') {
        const targets = selectedEntries(JSON.stringify({ include: [{ adapter: argument }] }), metadata.targets, 'adapter');
        stageArtifacts(metadata, targets);
    } else if (command === 'gather') {
        const targets = selectedEntries(process.env.BUILDS, metadata.targets, 'adapter');
        gatherArtifacts(metadata, targets);
    } else if (command === 'publish') {
        if (!argument) throw new Error('The pinned mc-publish checkout is required');
        if (!['true', 'false'].includes(process.env.PUBLISH_GITHUB)) throw new Error('PUBLISH_GITHUB must be true or false');
        const uploads = selectedEntries(process.env.UPLOADS, metadata.uploads, 'file');
        const context = {
            project: process.env.CURSEFORGE_ID, token: process.env.CURSEFORGE_TOKEN,
            githubToken: process.env.GITHUB_TOKEN,
            repository: process.env.GITHUB_REPOSITORY, sha: process.env.GITHUB_SHA,
        };
        if (!/^[0-9a-f]{40}$/.test(context.sha ?? '') || !/^[\w.-]+\/[\w.-]+$/.test(context.repository ?? '')) {
            throw new Error('GITHUB_SHA and GITHUB_REPOSITORY are required');
        }
        const changelog = releaseChangelog(metadata.version);
        const destinations = { CurseForge: () => publishCurseforgeFiles(uploads, {
            recorded: upload => recordedCurseforgeFiles(context.project, metadata.tag).has(upload.file),
            publish: upload => {
                if (!context.token) throw new Error('Set CURSEFORGE_TOKEN in the mod-publish environment');
                verifyArtifacts(metadata, upload.adapter, path.join(root, 'dist', upload.adapter));
                return runMcPublish(argument, curseforgeInputs(metadata, upload, changelog, context));
            },
            record: upload => recordUpload(`refs/curseforge/${context.project}/${metadata.tag}/${upload.file}`, upload.file),
            notice: message => annotate('notice', 'CurseForge upload receipt', message),
        }) };
        if (process.env.PUBLISH_GITHUB === 'true') destinations.GitHub = async () => {
            if (!context.githubToken) throw new Error('GITHUB_TOKEN is required');
            verifyArtifacts(metadata, 'all', path.join(root, 'dist/github'));
            verifyTag(metadata);
            const files = await githubReleaseFiles(metadata, 'dist/github', context.repository, context.githubToken);
            if (files.length) await runMcPublish(argument, githubInputs(metadata, files, changelog, context));
        };
        await publishDestinations(destinations);
    } else {
        throw new Error('Usage: mod_publish.mjs stage <adapter> | gather | publish <mc-publish-checkout>');
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
