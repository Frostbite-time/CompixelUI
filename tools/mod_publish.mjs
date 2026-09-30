// Metadata, parallel build dispatch and publication for the mod-publish workflows.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { annotate, properties } from './maven_publication.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const outputDelimiter = 'COMPIXEL_OUTPUT';

export function releaseMetadata(directory = root) {
    const version = properties(path.join(directory, 'gradle.properties')).mod_version;
    if (!/^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$/.test(version ?? '')) throw new Error(`Invalid mod_version: ${version}`);
    const registry = properties(path.join(directory, 'gradle/minecraft-targets.properties'));
    const targets = registry.targets.split(',').map(target => {
        const project = registry[`${target}.project`];
        const match = /^minecraft\/(forge|neoforge)-([0-9.]+)$/.exec(project ?? '');
        if (!match || match[2] !== target) throw new Error(`Invalid target directory: ${project}`);
        const config = properties(path.join(directory, project, 'gradle.properties'));
        if (config.minecraft_version !== target || !/^\d+$/.test(config.java_version)) throw new Error(`Invalid target settings: ${target}`);
        const provider = config.curseforge_kotlin_provider;
        if (provider === undefined || (provider && !/^[a-z0-9-]+$/.test(provider))) {
            throw new Error(`Set curseforge_kotlin_provider (or an explicit empty value) for ${target}`);
        }
        return { target, project, adapter: project.slice('minecraft/'.length), loader: match[1], java: config.java_version, provider };
    });
    if (!targets.length || new Set(targets.map(target => target.adapter)).size !== targets.length) throw new Error('Empty or duplicate targets');
    const uploads = targets.flatMap(target => ['standard', 'with-kotlin'].map(variant => ({
        ...target, variant,
        file: `compixel-${target.adapter}-${version}${variant === 'standard' ? '' : '-with-kotlin'}.jar`,
        dependencies: variant === 'standard' && target.provider ? `${target.provider}(required)` : '',
        installation: variant === 'with-kotlin'
            ? 'Includes Kotlin. Install only this variant; do not combine it with KFF or another Kotlin runtime.'
            : `Requires a compatible external Kotlin provider${target.provider ? ' (Kotlin for Forge)' : '; no compatible CurseForge provider is currently verified for this target'}. Install only this variant.`,
    })));
    return { version, tag: `v${version}`, type: version.includes('-alpha') ? 'alpha' : version.includes('-') ? 'beta' : 'release', targets, uploads };
}

/**
 * The release notes. docs/CHANGELOG.md describes only the version being released: its heading must end with that
 * version, and the text below the heading becomes the notes. Earlier versions' notes live in the file's history.
 */
export function releaseChangelog(version, directory = root) {
    const text = fs.readFileSync(path.join(directory, 'docs/CHANGELOG.md'), 'utf8').replace(/\r\n/g, '\n').trim();
    const [heading, ...body] = text.split('\n');
    const title = /^# (.+)$/.exec(heading)?.[1].trim();
    if (!title) throw new Error('docs/CHANGELOG.md must start with a "# CompixelUI <version>" heading');
    if (title.split(/\s+/).at(-1) !== version) {
        throw new Error(`docs/CHANGELOG.md describes "${title}", not ${version}. Update it before publishing.`);
    }
    const notes = body.join('\n').trim();
    if (!notes) throw new Error('docs/CHANGELOG.md has no release notes below its heading');
    if (notes.split('\n').includes(outputDelimiter)) throw new Error(`docs/CHANGELOG.md must not contain a ${outputDelimiter} line`);
    return notes;
}

export function assertTagCommit(remoteRefs, tag, sha) {
    const refs = new Map(remoteRefs.trim().split(/\r?\n/).filter(Boolean).map(line => {
        const [commit, ref] = line.split(/\s+/);
        return [ref, commit];
    }));
    const commit = refs.get(`refs/tags/${tag}^{}`) ?? refs.get(`refs/tags/${tag}`);
    if (commit && commit !== sha) throw new Error(`${tag} already points to ${commit}, not the selected commit ${sha}`);
}

export function verifyTag(metadata) {
    if (!/^[0-9a-f]{40}$/.test(process.env.GITHUB_SHA ?? '')) throw new Error('GITHUB_SHA is required to check the release tag');
    const refs = execFileSync('git', ['ls-remote', '--tags', 'origin', `refs/tags/${metadata.tag}`, `refs/tags/${metadata.tag}^{}`],
        { cwd: root, encoding: 'utf8', timeout: 60_000 });
    assertTagCommit(refs, metadata.tag, process.env.GITHUB_SHA);
}

export function checksumLines(metadata, adapter, directory) {
    const uploads = metadata.uploads.filter(upload => upload.adapter === adapter);
    if (uploads.length !== 2) throw new Error(`Unknown adapter: ${adapter}`);
    return uploads.map(({ file }) => {
        const bytes = fs.readFileSync(path.join(directory, file));
        if (!bytes.length) throw new Error(`Empty artifact: ${file}`);
        return `${createHash('sha256').update(bytes).digest('hex')}  ${file}\n`;
    }).join('');
}

export function verifyArtifacts(metadata, adapter, directory) {
    const targets = metadata.targets.filter(target => adapter === 'all' || target.adapter === adapter);
    if (!targets.length) throw new Error(`Unknown adapter: ${adapter}`);
    let manifest = '';
    for (const target of targets) {
        const expected = checksumLines(metadata, target.adapter, directory);
        const actual = fs.readFileSync(path.join(directory, `${target.adapter}.sha256`), 'utf8');
        if (expected !== actual) throw new Error(`Artifact checksum mismatch: ${target.adapter}`);
        manifest += expected;
    }
    const jars = fs.readdirSync(directory).filter(file => file.endsWith('.jar')).sort();
    const expectedJars = metadata.uploads.filter(upload => targets.some(target => target.adapter === upload.adapter))
        .map(upload => upload.file).sort();
    if (JSON.stringify(jars) !== JSON.stringify(expectedJars)) throw new Error('Unexpected or missing release JARs');
    return manifest;
}

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

/**
 * The GitHub release files to upload. A player JAR the release already has is kept, whatever this run built, and
 * SHA256SUMS lists the JARs the release ends up with: it is uploaded when missing and replaced only when it no longer
 * describes them. [built] maps each player JAR to its SHA-256; [existing] maps release asset names to { digest }, or
 * { content } for SHA256SUMS.
 */
export function planReleaseAssets(metadata, built, existing) {
    const upload = [], kept = [];
    let manifest = '';
    for (const { file } of metadata.uploads) {
        const asset = existing.get(file);
        if (asset) kept.push({ file, differs: asset.digest !== built.get(file) });
        else upload.push(file);
        manifest += `${asset ? asset.digest : built.get(file)}  ${file}\n`;
    }
    const sums = existing.get('SHA256SUMS');
    if (sums?.content !== manifest) upload.push('SHA256SUMS');
    return { upload, kept, manifest, replacesSums: !!sums && sums.content !== manifest };
}

/** Whether the release has every player JAR and a SHA256SUMS that lists them; [existing] is as for planReleaseAssets. */
export function githubReleaseComplete(metadata, existing) {
    let manifest = '';
    for (const { file } of metadata.uploads) {
        const asset = existing.get(file);
        if (!asset) return false;
        manifest += `${asset.digest}  ${file}\n`;
    }
    return existing.get('SHA256SUMS')?.content === manifest;
}

/** Build only adapters still needed by CurseForge, unless GitHub needs the complete set for its checksum manifest. */
export function planModRelease(metadata, uploaded, existing) {
    const uploads = metadata.uploads.filter(upload => !uploaded.has(upload.file));
    const github = !githubReleaseComplete(metadata, existing);
    const builds = metadata.targets.filter(target => github || uploads.some(upload => upload.adapter === target.adapter));
    return { uploads, github, builds };
}

/**
 * Successful uploads are recorded as refs, including files still under review. Scope them to the destination project.
 * [remoteRefs] is git ls-remote output. Manual uploads need a receipt too; no CurseForge file-list API is used.
 */
export function curseforgeUploads(remoteRefs, project, tag) {
    const prefix = `refs/curseforge/${project}/${tag}/`;
    return new Set(remoteRefs.split(/\r?\n/).map(line => line.split(/\s+/)[1] ?? '')
        .filter(ref => ref.startsWith(prefix)).map(ref => ref.slice(prefix.length)));
}

/** Read fresh receipts before planning or uploading. A failed Git query must never mean a file is missing. */
export function recordedCurseforgeFiles(project, tag, directory = root) {
    if (!/^[1-9]\d*$/.test(project ?? '')) throw new Error('Set the CURSEFORGE_ID secret in the mod-publish environment before dispatching mod-publish');
    let refs;
    try {
        refs = execFileSync('git', ['ls-remote', 'origin', `refs/curseforge/${project}/${tag}/*`],
            { cwd: directory, encoding: 'utf8', timeout: 60_000, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (error) {
        throw new Error('Cannot read CurseForge upload receipts from origin; no upload will be attempted. ' + error.message);
    }
    return curseforgeUploads(refs, project, tag);
}

function github(route, token, accept = 'application/vnd.github+json') {
    return fetch(`https://api.github.com${route}`, {
        headers: { Authorization: `Bearer ${token}`, Accept: accept, 'X-GitHub-Api-Version': '2022-11-28' },
        redirect: 'manual', signal: AbortSignal.timeout(600_000),
    });
}

async function downloadAsset(repository, asset, token) {
    const response = await github(`/repos/${repository}/releases/assets/${asset.id}`, token, 'application/octet-stream');
    // The API redirects to storage that must not receive the token.
    const location = response.headers.get('location');
    const file = location ? await fetch(location, { signal: AbortSignal.timeout(600_000) }) : response;
    if (file.status !== 200) throw new Error(`Cannot download release asset ${asset.name}: HTTP ${file.status}`);
    return Buffer.from(await file.arrayBuffer());
}

/** The assets of the release for [tag]: the SHA-256 of each file, and the content of SHA256SUMS. */
export async function releaseAssets(repository, tag, token) {
    const response = await github(`/repos/${repository}/releases/tags/${encodeURIComponent(tag)}`, token);
    if (response.status === 404) return new Map();
    if (response.status !== 200) throw new Error(`Cannot read the ${tag} release: HTTP ${response.status}`);
    const release = await response.json();
    const assets = new Map();
    for (let page = 1; ; page++) {
        const listing = await github(`/repos/${repository}/releases/${release.id}/assets?per_page=100&page=${page}`, token);
        if (listing.status !== 200) throw new Error(`Cannot list the ${tag} release assets: HTTP ${listing.status}`);
        const entries = await listing.json();
        for (const asset of entries) {
            if (asset.state !== 'uploaded' || asset.size <= 0) {
                throw new Error(`Incomplete GitHub asset ${asset.name}; inspect and remove it before retrying`);
            }
            const digest = /^sha256:([0-9a-f]{64})$/.exec(asset.digest ?? '')?.[1];
            if (asset.name === 'SHA256SUMS') assets.set(asset.name, { content: (await downloadAsset(repository, asset, token)).toString('utf8') });
            // Assets uploaded before GitHub computed digests are hashed from a download.
            else assets.set(asset.name, { digest: digest ?? sha256(await downloadAsset(repository, asset, token)) });
        }
        if (entries.length < 100) return assets;
    }
}

export async function githubReleaseFiles(metadata, directory, repository, token) {
    const built = new Map(metadata.uploads.map(({ file }) => [file, sha256(fs.readFileSync(path.join(directory, file)))]));
    const plan = planReleaseAssets(metadata, built, await releaseAssets(repository, metadata.tag, token));
    fs.writeFileSync(path.join(directory, 'SHA256SUMS'), plan.manifest);
    for (const { file, differs } of plan.kept) {
        annotate('notice', 'Already on GitHub', `${file} is already attached to the ${metadata.tag} release; skipped it` +
            `${differs ? ' and kept the published file, which differs from this build' : ''}.`);
    }
    if (plan.replacesSums) annotate('notice', 'Checksums updated', `SHA256SUMS no longer matched the ${metadata.tag} release files; replacing it.`);
    console.log(`Release files to upload: ${plan.upload.join(', ') || 'none'}`);
    return plan.upload.map(file => path.posix.join(directory, file));
}

export function githubOutput(values) {
    if (!process.env.GITHUB_OUTPUT) throw new Error('GITHUB_OUTPUT is required');
    for (const [key, value] of Object.entries(values)) {
        fs.appendFileSync(process.env.GITHUB_OUTPUT, `${key}<<${outputDelimiter}\n${value}\n${outputDelimiter}\n`);
    }
}

export function buildMatrix(metadata, targets) {
    const versions = targets.split(',');
    if (!targets || new Set(versions).size !== versions.length) throw new Error('Select distinct Minecraft targets');
    return { include: versions.map(version => {
        const target = metadata.targets.find(candidate => candidate.target === version);
        if (!target) throw new Error(`Unknown Minecraft target: ${version}`);
        return target;
    }) };
}

/** Wait for this dispatch's returned run ID, never a potentially unrelated "latest" run. */
export async function runParallelBuild(context, {
    fetchImpl = fetch, wait = delay, now = Date.now, timeout = 120 * 60_000, signal,
    started = () => {},
} = {}) {
    const { repository, token, ref, revision, targets, releaseRun } = context;
    if (!/^[\w.-]+\/[\w.-]+$/.test(repository ?? '') || !token || !ref ||
        !/^[0-9a-f]{40}$/.test(revision ?? '') || !/^[0-9]+-[0-9]+$/.test(releaseRun ?? '') || !targets.length) {
        throw new Error('A repository, token, ref, revision, selected targets and release run are required');
    }
    const api = `https://api.github.com/repos/${repository}/actions`;
    const request = (route, method = 'GET', body, cancel = false) => fetchImpl(`${api}${route}`, {
        method,
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body), redirect: 'error',
        signal: signal && !cancel ? AbortSignal.any([signal, AbortSignal.timeout(60_000)]) : AbortSignal.timeout(60_000),
    });
    // Do not retry a dispatch whose response is uncertain: it may already have created a run.
    const dispatched = await request('/workflows/mod-build.yml/dispatches', 'POST', {
        ref, return_run_details: true,
        inputs: { revision, targets: targets.map(target => target.target).join(','), release_run: releaseRun },
    });
    if (dispatched.status !== 200) throw new Error(`Cannot dispatch parallel builds: HTTP ${dispatched.status}`);
    const details = await dispatched.json();
    const runId = details.workflow_run_id;
    if (!Number.isSafeInteger(runId) || runId <= 0) throw new Error('Build dispatch did not return a valid run ID');
    const url = `https://github.com/${repository}/actions/runs/${runId}`;
    const deadline = now() + timeout;
    let completed = false;
    try {
        started(runId, url);
        console.log(`Parallel adapter builds: ${url}`);
        for (;;) {
            signal?.throwIfAborted();
            if (now() >= deadline) throw new Error(`Parallel builds timed out: ${url}`);
            const response = await request(`/runs/${runId}`);
            if (response.status !== 200) throw new Error(`Cannot read build run ${runId}: HTTP ${response.status}`);
            const run = await response.json();
            if (run.status === 'completed') {
                completed = true;
                if (run.conclusion !== 'success') throw new Error(`Parallel builds finished with ${run.conclusion}: ${url}`);
                return runId;
            }
            await wait(15_000, undefined, { signal });
        }
    } catch (error) {
        if (!completed) {
            try {
                const cancellation = await request(`/runs/${runId}/cancel`, 'POST', undefined, true);
                if (![202, 409].includes(cancellation.status)) console.error(`Could not cancel build run ${runId}: HTTP ${cancellation.status}`);
            } catch (cancelError) { console.error(`Could not cancel build run ${runId}: ${cancelError.message}`); }
        }
        throw error;
    }
}

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
    const [command, ...args] = process.argv.slice(2);
    const [argument] = args;
    const [adapter, directory = 'dist'] = command === 'github-assets' ? [undefined, ...args] : args;
    const metadata = releaseMetadata();
    const token = process.env.GITHUB_TOKEN, repository = process.env.GITHUB_REPOSITORY;
    const requireGitHub = () => {
        if (!token || !/^[\w.-]+\/[\w.-]+$/.test(repository ?? '')) throw new Error('GITHUB_TOKEN and GITHUB_REPOSITORY are required');
    };
    if (command === 'prepare') {
        requireGitHub();
        verifyTag(metadata);
        const changelog = releaseChangelog(metadata.version);
        const uploaded = recordedCurseforgeFiles(process.env.CURSEFORGE_ID, metadata.tag);
        const { uploads, github, builds } = planModRelease(metadata, uploaded, await releaseAssets(repository, metadata.tag, token));
        for (const { file } of metadata.uploads.filter(upload => uploaded.has(upload.file))) {
            annotate('notice', 'CurseForge upload recorded', `${file} has a successful upload receipt; skipped it.`);
        }
        if (!github) annotate('notice', 'Already on GitHub', `The ${metadata.tag} release already has every file; skipped it.`);
        githubOutput({
            version: metadata.version, tag: metadata.tag, type: metadata.type,
            builds: JSON.stringify({ include: builds }), uploads: JSON.stringify({ include: uploads }),
            upload_count: uploads.length, github, build: builds.length > 0,
            game_versions: metadata.targets.map(target => target.target).join('\n'),
            changelog,
        });
        console.log(`Prepared ${metadata.tag}: ${uploads.length} of ${metadata.uploads.length} CurseForge files to upload; ` +
            `GitHub release ${github ? 'incomplete' : 'complete'}`);
    } else if (command === 'curseforge-file') {
        const file = args[0];
        if (!metadata.uploads.some(upload => upload.file === file)) throw new Error(`Unknown release file: ${file}`);
        const uploaded = recordedCurseforgeFiles(process.env.CURSEFORGE_ID, metadata.tag).has(file);
        if (uploaded) annotate('notice', 'CurseForge upload recorded', `${file} has a successful upload receipt; skipped it.`);
        githubOutput({ uploaded });
    } else if (command === 'github-assets') {
        requireGitHub();
        githubOutput({ files: (await githubReleaseFiles(metadata, directory, repository, token)).join('\n') });
    } else if (command === 'tag') {
        verifyTag(metadata);
    } else if (command === 'checksums') {
        fs.writeFileSync(path.join(directory, `${adapter}.sha256`), checksumLines(metadata, adapter, directory));
    } else if (command === 'verify') {
        const manifest = verifyArtifacts(metadata, adapter, directory);
        if (adapter === 'all') fs.writeFileSync(path.join(directory, 'SHA256SUMS'), manifest);
        console.log(`Verified ${adapter} release artifacts`);
    } else if (command === 'matrix') {
        if (!/^[0-9a-f]{40}$/.test(process.env.REVISION ?? '') ||
            execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim() !== process.env.REVISION) {
            throw new Error('The build checkout must match the approved release revision');
        }
        githubOutput({ builds: JSON.stringify(buildMatrix(metadata, process.env.TARGETS ?? '')) });
    } else if (command === 'dispatch') {
        const targets = selectedEntries(process.env.BUILDS, metadata.targets, 'adapter');
        const controller = new AbortController();
        const abort = () => controller.abort(new Error('Release job cancelled'));
        process.once('SIGINT', abort);
        process.once('SIGTERM', abort);
        try {
            await runParallelBuild({
                repository: process.env.GITHUB_REPOSITORY, token: process.env.GITHUB_TOKEN,
                ref: process.env.GITHUB_REF_NAME, revision: process.env.GITHUB_SHA, targets,
                releaseRun: `${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT}`,
            }, {
                signal: controller.signal,
                started: (runId, url) => {
                    githubOutput({ run_id: runId });
                    if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY,
                        `Parallel adapter builds: [run ${runId}](${url})\n`);
                },
            });
        } finally {
            process.removeListener('SIGINT', abort);
            process.removeListener('SIGTERM', abort);
        }
    } else if (command === 'stage') {
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
        throw new Error('Usage: mod_publish.mjs prepare | matrix | dispatch | stage <adapter> | gather | publish <mc-publish-checkout> | tag | checksums <adapter> [directory] | verify <adapter|all> [directory] | github-assets [directory] | curseforge-file <file>');
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
