// Shared metadata and artifact checks for the mod-publish workflow; no upload credentials needed.
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { properties } from './check_runtime_publication.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

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

function main() {
    const [command, adapter, directory = 'dist'] = process.argv.slice(2);
    const metadata = releaseMetadata();
    if (command === 'prepare') {
        if (!/^\d+$/.test(process.env.CURSEFORGE_ID ?? '')) throw new Error('Set the repository variable CURSEFORGE_ID before dispatching mod-publish');
        verifyTag(metadata);
        const outputs = {
            version: metadata.version, tag: metadata.tag, type: metadata.type,
            builds: JSON.stringify({ include: metadata.targets }), uploads: JSON.stringify({ include: metadata.uploads }),
            game_versions: metadata.targets.map(target => target.target).join('\n'),
        };
        if (!process.env.GITHUB_OUTPUT) throw new Error('GITHUB_OUTPUT is required');
        for (const [key, value] of Object.entries(outputs)) fs.appendFileSync(process.env.GITHUB_OUTPUT, `${key}<<COMPOSEMC_OUTPUT\n${value}\nCOMPOSEMC_OUTPUT\n`);
        console.log(`Prepared ${metadata.tag}: ${metadata.targets.length} builds, ${metadata.uploads.length} independent CurseForge files`);
    } else if (command === 'tag') {
        verifyTag(metadata);
    } else if (command === 'checksums') {
        fs.writeFileSync(path.join(directory, `${adapter}.sha256`), checksumLines(metadata, adapter, directory));
    } else if (command === 'verify') {
        const manifest = verifyArtifacts(metadata, adapter, directory);
        if (adapter === 'all') fs.writeFileSync(path.join(directory, 'SHA256SUMS'), manifest);
        console.log(`Verified ${adapter} release artifacts`);
    } else {
        throw new Error('Usage: mod_release.mjs prepare | tag | checksums <adapter> [directory] | verify <adapter|all> [directory]');
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
