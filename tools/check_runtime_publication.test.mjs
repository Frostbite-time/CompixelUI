import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { test } from 'node:test';
import { inspectPublication, runtimePublication } from './check_runtime_publication.mjs';

const publication = runtimePublication('standard', '1.0.0');
const checksum = 'a'.repeat(64);

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
        const missing = `composemc-runtime-standard-1.0.0${suffix}`;
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
    assert.equal(runtimePublication('kotlin', '1.2.3').artifact, 'composemc-kotlin');
    assert.throws(() => runtimePublication('unknown', '1.0.0'), /Unknown bundle/);
    assert.throws(() => runtimePublication('standard', '../1.0.0'), /Invalid runtime version/);
});
