import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { once } from 'node:events';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
    IncompletePublicationError, adapterPublications, adapterTasks, inspectPublication, minecraftAdapters, planPublications,
    properties, runtimePublication,
} from './maven_publication.mjs';

const publication = runtimePublication('standard', '1.0.0');
const checksum = 'a'.repeat(64);
const config = properties(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'gradle.properties'));

function publish(entries, published) {
    for (const file of published.files) {
        entries.set(file, { status: 200 });
        entries.set(file + '.sha256', { status: 200, body: checksum });
    }
}

async function repository(t, entries) {
    const server = http.createServer((request, response) => {
        const file = request.url.split('/').at(-1);
        const entry = entries.get(file);
        response.writeHead(entry?.status ?? 404);
        response.end(entry?.body ?? '');
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    t.after(() => { server.closeAllConnections(); server.close(); });
    return `http://127.0.0.1:${server.address().port}`;
}

function complete() {
    return new Map(publication.files.flatMap(file => [
        [file, { status: 200 }], [file + '.sha256', { status: 200, body: checksum }],
    ]));
}

test('retries observe current remote state and skip a completed bundle', async t => {
    const entries = new Map();
    const url = await repository(t, entries);
    assert.equal(await inspectPublication(url, publication), 'missing');
    for (const [name, value] of complete()) entries.set(name, value);
    assert.equal(await inspectPublication(url, publication), 'complete');
});

test('a POM is insufficient when an attachment or checksum is missing', async t => {
    for (const suffix of ['-sources.jar', '-javadoc.jar.sha256', '.jar']) {
        const entries = complete();
        const missing = `compixel-runtime-standard-1.0.0${suffix}`;
        entries.delete(missing);
        const url = await repository(t, entries);
        await assert.rejects(inspectPublication(url, publication), error =>
            error.message.includes('Incomplete publication') && error.message.includes(missing));
    }
});

test('a main JAR uploaded before the POM cannot be republished as absent', async t => {
    const entries = new Map([[publication.files[0], { status: 200 }]]);
    await assert.rejects(inspectPublication(await repository(t, entries), publication), /Incomplete publication/);
});

test('an orphan checksum cannot be republished as absent', async t => {
    const entries = new Map([[publication.files[0] + '.sha256', { status: 200, body: checksum }]]);
    await assert.rejects(inspectPublication(await repository(t, entries), publication), /Incomplete publication/);
});

test('authentication and server errors fail closed', async t => {
    for (const status of [401, 403, 500]) {
        const entries = complete();
        entries.set(publication.files[0], { status });
        await assert.rejects(inspectPublication(await repository(t, entries), publication), new RegExp(`HTTP ${status}`));
    }
});

test('malformed checksum is not a complete publication', async t => {
    const entries = complete();
    entries.set(publication.files[0] + '.sha256', { status: 200, body: '<html>proxy error</html>' });
    await assert.rejects(inspectPublication(await repository(t, entries), publication), /Invalid SHA-256/);
});

test('the Kotlin bundle uses its separate coordinate and invalid input is rejected', () => {
    assert.equal(runtimePublication('kotlin', '1.2.3').artifact, 'compixel-kotlin');
    assert.throws(() => runtimePublication('unknown', '1.0.0'), /Unknown bundle/);
    assert.throws(() => runtimePublication('standard', '../1.0.0'), /Invalid runtime version/);
});

test('an adapter has a library with its attachments and a POM-only Kotlin selection', () => {
    const [library, withKotlin] = adapterPublications('neoforge-1.21.1', '1.0.0');
    const stem = 'compixel-neoforge-1.21.1-1.0.0';
    assert.deepEqual(library.files, [`${stem}.jar`, `${stem}.pom`, `${stem}-sources.jar`, `${stem}-javadoc.jar`, `${stem}-development.jar`]);
    assert.equal(library.task, ':minecraft:neoforge-1.21.1:publishLibraryPublicationToWintercogsRepository');
    assert.deepEqual(withKotlin.files, ['compixel-neoforge-1.21.1-with-kotlin-1.0.0.pom']);
    assert.equal(withKotlin.task, ':minecraft:neoforge-1.21.1:publishLibraryWithKotlinPublicationToWintercogsRepository');
    assert.throws(() => adapterPublications('fabric-1.21.1', '1.0.0'), /Invalid adapter/);
    assert.throws(() => adapterPublications('neoforge-1.21.1', '../1.0.0'), /Invalid mod version/);
});

test('an adapter uploads only the publications the repository lacks', async t => {
    const entries = new Map();
    const url = await repository(t, entries);
    const [library, withKotlin] = adapterPublications('neoforge-1.21.1', config.mod_version);
    assert.deepEqual((await adapterTasks(url, 'neoforge-1.21.1')).tasks, [library.task, withKotlin.task]);
    publish(entries, library);
    const partial = await adapterTasks(url, 'neoforge-1.21.1');
    assert.deepEqual(partial.tasks, [withKotlin.task]);
    assert.deepEqual(partial.skipped.map(skipped => skipped.artifact), [library.artifact]);
    publish(entries, withKotlin);
    assert.deepEqual((await adapterTasks(url, 'neoforge-1.21.1')).tasks, []);
    entries.delete(library.files.at(-1));
    await assert.rejects(adapterTasks(url, 'neoforge-1.21.1'), IncompletePublicationError);
});

test('the plan skips complete versions and leaves an incomplete adapter to its own job', async t => {
    const entries = new Map();
    const url = await repository(t, entries);
    for (const bundle of ['standard', 'vulkan', 'kotlin']) publish(entries, runtimePublication(bundle, config[`runtime_${bundle}_version`]));
    const adapters = minecraftAdapters();
    adapterPublications(adapters[0].adapter, config.mod_version).forEach(published => publish(entries, published));
    const [broken] = adapterPublications(adapters[1].adapter, config.mod_version);
    entries.set(broken.files[1], { status: 200 });
    const plan = await planPublications(url);
    assert.deepEqual(plan.bundles, []);
    assert.deepEqual(plan.adapters, adapters.slice(1));
    assert.deepEqual(plan.incomplete.map(incomplete => incomplete.artifact), [broken.artifact]);
    assert.equal(plan.skipped.length, 5);
});

test('an incomplete runtime bundle stops the whole plan', async t => {
    const entries = new Map();
    const runtime = runtimePublication('vulkan', config.runtime_vulkan_version);
    entries.set(runtime.files[0], { status: 200 });
    await assert.rejects(planPublications(await repository(t, entries)), IncompletePublicationError);
});

test('a connection the server closes before answering is retried', async t => {
    let dropped = 0;
    const server = http.createServer((request, response) => {
        if (dropped++ < 2) return request.socket.destroy();
        response.writeHead(404);
        response.end();
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    t.after(() => { server.closeAllConnections(); server.close(); });
    assert.equal(await inspectPublication(`http://127.0.0.1:${server.address().port}`, publication), 'missing');
});
