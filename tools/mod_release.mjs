// Shared metadata and read-only publication checks for the mod-publish workflow.
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { annotate, properties } from './maven_publication.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const outputDelimiter = 'COMPOSEMC_OUTPUT';

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
        file: `composemc-${target.adapter}-${version}${variant === 'standard' ? '' : '-with-kotlin'}.jar`,
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
    if (!title) throw new Error('docs/CHANGELOG.md must start with a "# Compose MC <version>" heading');
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

function verifyTag(metadata) {
    if (!/^[0-9a-f]{40}$/.test(process.env.GITHUB_SHA ?? '')) throw new Error('GITHUB_SHA is required to check the release tag');
    const refs = execFileSync('git', ['ls-remote', '--tags', 'origin', `refs/tags/${metadata.tag}`, `refs/tags/${metadata.tag}^{}`],
        { cwd: root, encoding: 'utf8', timeout: 60_000 });
    assertTagCommit(refs, metadata.tag, process.env.GITHUB_SHA);
}

function checksumLines(metadata, adapter, directory) {
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

function githubOutput(values) {
    if (!process.env.GITHUB_OUTPUT) throw new Error('GITHUB_OUTPUT is required');
    for (const [key, value] of Object.entries(values)) {
        fs.appendFileSync(process.env.GITHUB_OUTPUT, `${key}<<${outputDelimiter}\n${value}\n${outputDelimiter}\n`);
    }
}

async function main() {
    const [command, ...args] = process.argv.slice(2);
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
        const built = new Map(metadata.uploads.map(({ file }) => [file, sha256(fs.readFileSync(path.join(directory, file)))]));
        const plan = planReleaseAssets(metadata, built, await releaseAssets(repository, metadata.tag, token));
        fs.writeFileSync(path.join(directory, 'SHA256SUMS'), plan.manifest);
        for (const { file, differs } of plan.kept) {
            annotate('notice', 'Already on GitHub', `${file} is already attached to the ${metadata.tag} release; skipped it` +
                `${differs ? ' and kept the published file, which differs from this build' : ''}.`);
        }
        if (plan.replacesSums) annotate('notice', 'Checksums updated', `SHA256SUMS no longer matched the ${metadata.tag} release files; replacing it.`);
        githubOutput({ files: plan.upload.map(file => path.posix.join(directory, file)).join('\n') });
        console.log(`Release files to upload: ${plan.upload.join(', ') || 'none'}`);
    } else if (command === 'tag') {
        verifyTag(metadata);
    } else if (command === 'checksums') {
        fs.writeFileSync(path.join(directory, `${adapter}.sha256`), checksumLines(metadata, adapter, directory));
    } else if (command === 'verify') {
        const manifest = verifyArtifacts(metadata, adapter, directory);
        if (adapter === 'all') fs.writeFileSync(path.join(directory, 'SHA256SUMS'), manifest);
        console.log(`Verified ${adapter} release artifacts`);
    } else {
        throw new Error('Usage: mod_release.mjs prepare | tag | checksums <adapter> [directory] | verify <adapter|all> [directory] | github-assets [directory] | curseforge-file <file>');
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
