// Read-only Maven publication preflight. Never overwrite or repair a partially published version.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export function properties(file) {
    return Object.fromEntries(fs.readFileSync(file, 'utf8').split(/\r?\n/)
        .filter(line => line.trim() && !line.trimStart().startsWith('#'))
        .map(line => {
            const separator = line.indexOf('=');
            if (separator < 1) throw new Error(`Invalid property in ${file}: ${line}`);
            return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
        }));
}

/** Some files of a version are in the repository and some are not. Only a manual cleanup can fix it. */
export class IncompletePublicationError extends Error {}

function publication(artifact, version, suffixes) {
    return {
        artifact, version,
        directory: `dev/compixel/${artifact}/${version}`,
        files: suffixes.map(suffix => `${artifact}-${version}${suffix}`),
    };
}

export function runtimePublication(bundle, version) {
    if (!['standard', 'vulkan', 'kotlin'].includes(bundle)) throw new Error(`Unknown bundle: ${bundle}`);
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(version ?? '')) throw new Error(`Invalid runtime version: ${version}`);
    const artifact = bundle === 'kotlin' ? 'compixel-kotlin' : `compixel-runtime-${bundle}`;
    return publication(artifact, version, ['.jar', '.pom', '-sources.jar', '-javadoc.jar']);
}

/** The two publications of an adapter, each with the Gradle task that uploads it: the library, and the POM that adds Kotlin. */
export function adapterPublications(adapter, version) {
    if (!/^(forge|neoforge)-[0-9.]+$/.test(adapter ?? '')) throw new Error(`Invalid adapter: ${adapter}`);
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(version ?? '')) throw new Error(`Invalid mod version: ${version}`);
    const artifact = `compixel-${adapter}`;
    const task = name => `:minecraft:${adapter}:publish${name}PublicationToWintercogsRepository`;
    return [
        { ...publication(artifact, version, ['.jar', '.pom', '-sources.jar', '-javadoc.jar', '-development.jar']), task: task('Library') },
        { ...publication(`${artifact}-with-kotlin`, version, ['.pom']), task: task('LibraryWithKotlin') },
    ];
}

export function minecraftAdapters(directory = root) {
    const registry = properties(path.join(directory, 'gradle/minecraft-targets.properties'));
    return registry.targets.split(',').map(target => {
        const project = registry[`${target}.project`];
        const match = /^minecraft\/((?:forge|neoforge)-([0-9.]+))$/.exec(project ?? '');
        if (!match || match[2] !== target) throw new Error(`Invalid target directory: ${project}`);
        return { target, adapter: match[1] };
    });
}

// A kept-alive connection that the server has just closed fails before any response arrives. Such read-only requests are
// retried; HTTP statuses and redirects are answers and are never retried.
const retriedNetworkErrors = new Set(['UND_ERR_SOCKET', 'ECONNRESET', 'EPIPE', 'ETIMEDOUT', 'UND_ERR_CONNECT_TIMEOUT', 'EAI_AGAIN']);

async function request(url, method) {
    for (let attempt = 1; ; attempt++) {
        try {
            return await fetch(url, { method, signal: AbortSignal.timeout(30_000), redirect: 'error' });
        } catch (error) {
            if (attempt === 3 || !retriedNetworkErrors.has(error.cause?.code)) throw error;
            await new Promise(resolve => setTimeout(resolve, 500 * attempt));
        }
    }
}

export async function inspectPublication(repository, publication) {
    const base = `${repository.replace(/\/$/, '')}/${publication.directory}/`;
    const files = publication.files.flatMap(name => [{ name, checksum: false }, { name: `${name}.sha256`, checksum: true }]);
    const states = await Promise.all(files.map(async ({ name, checksum }) => {
        const response = await request(base + name, checksum ? 'GET' : 'HEAD');
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
    throw new IncompletePublicationError(`Incomplete publication ${publication.artifact} ${publication.version}; missing: ${missing}. ` +
        'Inspect and remove only this incomplete version in Reposilite before retrying. Complete versions must stay unchanged.');
}

/**
 * What a release still has to publish. Complete versions are skipped. An incomplete runtime bundle stops the whole run,
 * because every adapter depends on the bundles; an incomplete adapter is kept in the plan so that only its own job fails.
 */
export async function planPublications(repository, directory = root) {
    const config = properties(path.join(directory, 'gradle.properties'));
    const plan = { bundles: [], adapters: [], skipped: [], incomplete: [] };
    for (const bundle of ['standard', 'vulkan', 'kotlin']) {
        const runtime = runtimePublication(bundle, config[`runtime_${bundle}_version`]);
        if (await inspectPublication(repository, runtime) === 'missing') plan.bundles.push(bundle);
        else plan.skipped.push(runtime);
    }
    for (const { target, adapter } of minecraftAdapters(directory)) {
        let pending = false;
        for (const library of adapterPublications(adapter, config.mod_version)) {
            try {
                if (await inspectPublication(repository, library) === 'complete') plan.skipped.push(library);
                else pending = true;
            } catch (error) {
                if (!(error instanceof IncompletePublicationError)) throw error;
                plan.incomplete.push(library);
                pending = true;
            }
        }
        if (pending) plan.adapters.push({ target, adapter });
    }
    return plan;
}

/** The Gradle tasks that publish what the repository lacks for one adapter. */
export async function adapterTasks(repository, adapter, directory = root) {
    const version = properties(path.join(directory, 'gradle.properties')).mod_version;
    const result = { tasks: [], skipped: [] };
    for (const library of adapterPublications(adapter, version)) {
        if (await inspectPublication(repository, library) === 'missing') result.tasks.push(library.task);
        else result.skipped.push(library);
    }
    return result;
}

// Workflow commands for the Actions log; property and message characters are escaped as the runner expects.
export function annotate(level, title, message) {
    const escape = value => value.replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A');
    console.log(`::${level} title=${escape(title).replace(/:/g, '%3A').replace(/,/g, '%2C')}::${escape(message)}`);
}

function skippedNotice(library) {
    annotate('notice', 'Already published', `${library.artifact} ${library.version} is already in the repository; skipped it.`);
}

function output(values) {
    if (!process.env.GITHUB_OUTPUT) return;
    for (const [key, value] of Object.entries(values)) fs.appendFileSync(process.env.GITHUB_OUTPUT, `${key}=${value}\n`);
}

async function main() {
    const [command, ...args] = process.argv.slice(2);
    const requireComplete = args.includes('--require-complete');
    const names = args.filter(arg => arg !== '--require-complete');
    const repository = process.env.MAVEN_RELEASES ?? 'https://maven.wintercogs.com/releases';
    if (command === 'plan' && !names.length) {
        const plan = await planPublications(repository);
        plan.skipped.forEach(skippedNotice);
        for (const library of plan.incomplete) {
            annotate('warning', 'Incomplete publication', `${library.artifact} ${library.version} is partly in the repository; its job will fail until it is removed.`);
        }
        output({ bundles: plan.bundles.join(' '), adapters: JSON.stringify({ include: plan.adapters }), adapter_count: plan.adapters.length });
        console.log(`Runtime bundles to publish: ${plan.bundles.join(', ') || 'none'}. ` +
            `Adapters to publish: ${plan.adapters.map(({ adapter }) => adapter).join(', ') || 'none'}.`);
    } else if (command === 'runtime' && names.length) {
        // The runtime job captures this list afresh immediately before each upload, including on job retries.
        const config = properties(path.join(root, 'gradle.properties'));
        const missing = [];
        for (const bundle of names) {
            const runtime = runtimePublication(bundle, config[`runtime_${bundle}_version`]);
            const state = await inspectPublication(repository, runtime);
            console.error(`${runtime.artifact} ${runtime.version}: ${state}`);
            if (state === 'missing') missing.push(bundle);
        }
        if (requireComplete && missing.length) throw new Error(`Publication is still missing: ${missing.join(', ')}`);
        console.log(missing.join(' '));
    } else if (command === 'adapter' && names.length === 1) {
        const { tasks, skipped } = await adapterTasks(repository, names[0]);
        if (requireComplete) {
            if (tasks.length) throw new Error(`Publication is still missing: ${tasks.join(', ')}`);
        } else {
            skipped.forEach(skippedNotice);
        }
        output({ tasks: tasks.join(' ') });
        console.log(`Publications to upload for ${names[0]}: ${tasks.join(' ') || 'none'}`);
    } else {
        throw new Error('Usage: maven_publication.mjs plan | runtime <bundle...> [--require-complete] | adapter <adapter> [--require-complete]');
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    main().catch(error => {
        if (process.env.GITHUB_ACTIONS) annotate('error', 'Maven publication', error.message);
        else console.error(error.message);
        process.exitCode = 1;
    });
}
