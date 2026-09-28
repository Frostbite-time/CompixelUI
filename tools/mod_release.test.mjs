import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { test } from 'node:test';
import {
    assertTagCommit, curseforgeUploads, githubReleaseComplete, planModRelease, planReleaseAssets, recordedCurseforgeFiles,
    releaseAssets, releaseChangelog, releaseMetadata, verifyArtifacts,
} from './mod_release.mjs';

test('the target registry produces distinct variants with target-specific provider requirements', () => {
    const metadata = releaseMetadata();
    assert.equal(metadata.uploads.length, metadata.targets.length * 2);
    assert.equal(new Set(metadata.uploads.map(upload => upload.file)).size, metadata.uploads.length);
    for (const target of metadata.targets) {
        const [standard, bundled] = metadata.uploads.filter(upload => upload.adapter === target.adapter);
        assert.equal(standard.dependencies, target.provider ? `${target.provider}(required)` : '');
        assert.equal(bundled.dependencies, '');
        assert.match(bundled.file, /-with-kotlin\.jar$/);
    }
    assert.equal(metadata.uploads.find(upload => upload.target === '26.3' && upload.variant === 'standard').dependencies, '');
});

test('existing lightweight and annotated tags must identify the selected commit', () => {
    const commit = 'a'.repeat(40);
    const other = 'b'.repeat(40);
    assertTagCommit('', 'v1.0.0', commit);
    assertTagCommit(`${commit}\trefs/tags/v1.0.0`, 'v1.0.0', commit);
    assertTagCommit(`${other}\trefs/tags/v1.0.0\n${commit}\trefs/tags/v1.0.0^{}`, 'v1.0.0', commit);
    assert.throws(() => assertTagCommit(`${other}\trefs/tags/v1.0.0`, 'v1.0.0', commit), /already points to/);
});

test('downloaded release artifacts reject corruption and stray JARs', t => {
    const directory = temporaryDirectory(t);
    const metadata = releaseMetadata();
    const adapter = metadata.targets[0].adapter;
    const uploads = metadata.uploads.filter(upload => upload.adapter === adapter);
    const content = Buffer.from('release fixture');
    const checksum = createHash('sha256').update(content).digest('hex');
    for (const { file } of uploads) fs.writeFileSync(path.join(directory, file), content);
    fs.writeFileSync(path.join(directory, `${adapter}.sha256`), uploads.map(({ file }) => `${checksum}  ${file}\n`).join(''));
    verifyArtifacts(metadata, adapter, directory);
    fs.writeFileSync(path.join(directory, uploads[0].file), 'corrupt');
    assert.throws(() => verifyArtifacts(metadata, adapter, directory), /checksum mismatch/);
    fs.writeFileSync(path.join(directory, uploads[0].file), content);
    fs.writeFileSync(path.join(directory, 'development.jar'), content);
    assert.throws(() => verifyArtifacts(metadata, adapter, directory), /Unexpected or missing/);
});

function temporaryDirectory(t) {
    const temporaryRoot = fs.realpathSync(os.tmpdir());
    const directory = fs.mkdtempSync(path.join(temporaryRoot, 'composemc-release-'));
    t.after(() => {
        assert.equal(path.dirname(fs.realpathSync(directory)), temporaryRoot);
        fs.rmSync(directory, { recursive: true, force: true });
    });
    return directory;
}

test('the changelog describes exactly the version being released', t => {
    const directory = temporaryDirectory(t);
    fs.mkdirSync(path.join(directory, 'docs'));
    const changelog = text => fs.writeFileSync(path.join(directory, 'docs/CHANGELOG.md'), text);
    changelog('# Compose MC 1.2.3\r\n\r\n- Faster tooltips.\r\n- Fewer allocations.\r\n');
    assert.equal(releaseChangelog('1.2.3', directory), '- Faster tooltips.\n- Fewer allocations.');
    assert.throws(() => releaseChangelog('1.2.4', directory), /describes "Compose MC 1\.2\.3", not 1\.2\.4/);
    assert.throws(() => releaseChangelog('1.2', directory), /not 1\.2\./);
    changelog('- Faster tooltips.\n');
    assert.throws(() => releaseChangelog('1.2.3', directory), /must start with/);
    changelog('# Compose MC 1.2.3\n\n');
    assert.throws(() => releaseChangelog('1.2.3', directory), /no release notes/);
    // A version bump must come with this version's notes.
    assert.ok(releaseChangelog(releaseMetadata().version).length > 0);
});

test('a release keeps the files it already has and lists the files it ends up with', () => {
    const metadata = releaseMetadata();
    const files = metadata.uploads.map(upload => upload.file);
    const built = new Map(files.map((file, index) => [file, index.toString(16).padStart(64, '0')]));
    const manifest = digests => files.map(file => `${digests.get(file)}  ${file}\n`).join('');

    const fresh = planReleaseAssets(metadata, built, new Map());
    assert.deepEqual(fresh.upload, [...files, 'SHA256SUMS']);
    assert.equal(fresh.manifest, manifest(built));

    const published = new Map(files.slice(0, 3).map(file => [file, { digest: built.get(file) }]));
    const partial = planReleaseAssets(metadata, built, published);
    assert.deepEqual(partial.upload, [...files.slice(3), 'SHA256SUMS']);
    assert.deepEqual(partial.kept, files.slice(0, 3).map(file => ({ file, differs: false })));

    const complete = new Map([...files.map(file => [file, { digest: built.get(file) }]), ['SHA256SUMS', { content: manifest(built) }]]);
    assert.deepEqual(planReleaseAssets(metadata, built, complete).upload, []);

    // A file published by an earlier build stays; the checksums follow it.
    const earlier = 'f'.repeat(64);
    complete.set(files[0], { digest: earlier });
    const replaced = planReleaseAssets(metadata, built, complete);
    assert.deepEqual(replaced.upload, ['SHA256SUMS']);
    assert.equal(replaced.replacesSums, true);
    assert.deepEqual(replaced.kept[0], { file: files[0], differs: true });
    assert.equal(replaced.manifest, manifest(new Map([...built, [files[0], earlier]])));
});

test('the refs of earlier CurseForge uploads name the files to skip', () => {
    const refs = [
        `${'a'.repeat(40)}\trefs/curseforge/123/v1.0.0/composemc-neoforge-1.21.1-1.0.0.jar`,
        `${'b'.repeat(40)}\trefs/curseforge/123/v1.0.0/composemc-neoforge-1.21.1-1.0.0-with-kotlin.jar`,
        `${'c'.repeat(40)}\trefs/curseforge/123/v1.0.1/composemc-neoforge-1.21.1-1.0.1.jar`,
        `${'d'.repeat(40)}\trefs/curseforge/456/v1.0.0/other-project.jar`,
        '',
    ].join('\n');
    assert.deepEqual([...curseforgeUploads(refs, '123', 'v1.0.0')],
        ['composemc-neoforge-1.21.1-1.0.0.jar', 'composemc-neoforge-1.21.1-1.0.0-with-kotlin.jar']);
    assert.equal(curseforgeUploads('', '123', 'v1.0.0').size, 0);
});

test('a GitHub release is complete only with every JAR and checksums that list them', () => {
    const metadata = releaseMetadata();
    const assets = new Map(metadata.uploads.map(({ file }, index) => [file, { digest: index.toString(16).padStart(64, '0') }]));
    assert.equal(githubReleaseComplete(metadata, assets), false);
    const sums = metadata.uploads.map(({ file }) => `${assets.get(file).digest}  ${file}\n`).join('');
    assets.set('SHA256SUMS', { content: sums });
    assert.equal(githubReleaseComplete(metadata, assets), true);
    assets.set(metadata.uploads[0].file, { digest: 'f'.repeat(64) });
    assert.equal(githubReleaseComplete(metadata, assets), false);
    assets.delete(metadata.uploads[0].file);
    assert.equal(githubReleaseComplete(metadata, assets), false);
});

test('release planning skips complete runs and builds only adapters needed by missing CurseForge files', () => {
    const metadata = releaseMetadata();
    const files = metadata.uploads.map(upload => upload.file);
    const uploaded = new Set(files);
    const existing = new Map(files.map(file => [file, { digest: 'a'.repeat(64) }]));
    existing.set('SHA256SUMS', { content: files.map(file => `${'a'.repeat(64)}  ${file}\n`).join('') });
    assert.deepEqual(planModRelease(metadata, uploaded, existing), { uploads: [], builds: [], github: false });
    uploaded.delete(files[1]);
    assert.deepEqual(planModRelease(metadata, uploaded, existing), {
        uploads: [metadata.uploads[1]], builds: [metadata.targets[0]], github: false,
    });
    const missingGitHub = planModRelease(metadata, new Set(files), new Map());
    assert.equal(missingGitHub.github, true);
    assert.deepEqual(missingGitHub.uploads, []);
    assert.deepEqual(missingGitHub.builds, metadata.targets);
});

function receiptRepository(t) {
    const directory = temporaryDirectory(t);
    const remote = path.join(directory, 'remote.git');
    const checkout = path.join(directory, 'checkout');
    const git = (cwd, args, input) => execFileSync('git', args, {
        cwd, input, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
    git(directory, ['init', '--bare', remote]);
    git(directory, ['init', checkout]);
    git(checkout, ['remote', 'add', 'origin', remote]);
    // Receipt discovery reads ref names; an opaque object is sufficient without making fixture commits.
    const object = git(remote, ['hash-object', '-w', '--stdin'], 'published release fixture\n');
    return { remote, checkout, git, object };
}

test('CurseForge retries read current Git receipts and skip only recorded files without an API key', t => {
    t.mock.method(globalThis, 'fetch', () => assert.fail('Receipt checks must not call the CurseForge API'));
    const { remote, checkout, git, object } = receiptRepository(t);
    const metadata = releaseMetadata();
    const file = metadata.uploads[0].file;
    const ref = `refs/curseforge/123/${metadata.tag}/${file}`;
    const read = () => recordedCurseforgeFiles('123', metadata.tag, checkout);
    assert.deepEqual(read(), new Set());
    git(remote, ['update-ref', ref, object]);
    assert.deepEqual(read(), new Set([file]));
    const plan = planModRelease(metadata, read(), new Map());
    assert.deepEqual(plan.uploads, metadata.uploads.slice(1));
    git(remote, ['update-ref', '-d', ref]);
    assert.deepEqual(read(), new Set());
});

test('invalid project settings or failed Git receipt queries never allow an upload', t => {
    const { checkout, git } = receiptRepository(t);
    for (const project of ['', '0', '../123']) {
        assert.throws(() => recordedCurseforgeFiles(project, 'v1.0.0', checkout), /CURSEFORGE_ID/);
    }
    git(checkout, ['remote', 'set-url', 'origin', path.join(checkout, 'missing.git')]);
    assert.throws(() => recordedCurseforgeFiles('123', 'v1.0.0', checkout), /Cannot read CurseForge upload receipts.*no upload will be attempted/);
});

test('GitHub lists all asset pages, uses stored digests and downloads legacy files without forwarding credentials', async t => {
    const legacy = 'legacy release JAR';
    const digest = createHash('sha256').update(legacy).digest('hex');
    const sums = `${digest}  legacy.jar\n`;
    const assets = Array.from({ length: 100 }, (_, index) => ({
        id: index, name: `file-${index}.jar`, digest: `sha256:${'a'.repeat(64)}`, state: 'uploaded', size: 10,
    }));
    const requests = [];
    t.mock.method(globalThis, 'fetch', async (url, options) => {
        requests.push(url);
        if (url.startsWith('https://storage.example/')) {
            assert.equal(options.headers, undefined);
            return new Response(legacy);
        }
        assert.equal(options.headers.Authorization, 'Bearer test-token');
        assert.equal(options.redirect, 'manual');
        if (url.endsWith('/tags/v1.0.0')) return Response.json({ id: 123 });
        if (url.endsWith('/assets?per_page=100&page=1')) return Response.json(assets);
        if (url.endsWith('/assets?per_page=100&page=2')) return Response.json([
            { id: 100, name: 'legacy.jar', state: 'uploaded', size: 10 },
            { id: 101, name: 'SHA256SUMS', state: 'uploaded', size: sums.length },
        ]);
        assert.equal(options.headers.Accept, 'application/octet-stream');
        if (url.endsWith('/assets/100')) return new Response(null, { status: 302, headers: { location: 'https://storage.example/legacy' } });
        if (url.endsWith('/assets/101')) return new Response(sums);
        assert.fail(`Unexpected request: ${url}`);
    });
    const actual = await releaseAssets('owner/repo', 'v1.0.0', 'test-token');
    assert.equal(actual.size, 102);
    assert.deepEqual(actual.get('legacy.jar'), { digest });
    assert.deepEqual(actual.get('SHA256SUMS'), { content: sums });
    assert.equal(requests.length, 6);
});

test('GitHub distinguishes an absent release from API errors and failed uploads', async t => {
    let response;
    t.mock.method(globalThis, 'fetch', async url => url.endsWith('/tags/v1.0.0') ? response : Response.json([
        { id: 1, name: 'broken.jar', state: 'starter', size: 0 },
    ]));
    response = new Response(null, { status: 404 });
    assert.deepEqual(await releaseAssets('owner/repo', 'v1.0.0', 'test-token'), new Map());
    for (const status of [401, 403, 500]) {
        response = new Response(null, { status });
        await assert.rejects(releaseAssets('owner/repo', 'v1.0.0', 'test-token'), new RegExp(`HTTP ${status}`));
    }
    response = Response.json({ id: 123 });
    await assert.rejects(releaseAssets('owner/repo', 'v1.0.0', 'test-token'), /Incomplete GitHub asset broken.jar/);
});
