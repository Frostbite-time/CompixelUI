import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { releaseMetadata, verifyArtifacts } from './mod_release.mjs';
import {
    buildArguments, curseforgeInputs, publishCurseforgeFiles, runMcPublish, selectedEntries, stageArtifacts,
} from './mod_publish.mjs';

function temporaryDirectory(t) {
    const temporaryRoot = fs.realpathSync(os.tmpdir());
    const directory = fs.mkdtempSync(path.join(temporaryRoot, 'compixel-publish-'));
    t.after(() => {
        assert.equal(path.dirname(fs.realpathSync(directory)), temporaryRoot);
        fs.rmSync(directory, { recursive: true, force: true });
    });
    return directory;
}

test('build selection follows the current registry and rejects unexpected or duplicate entries', () => {
    const metadata = releaseMetadata();
    const target = metadata.targets[1];
    const selected = selectedEntries(JSON.stringify({ include: [{ adapter: target.adapter, target: 'wrong', project: '../wrong' }] }),
        metadata.targets, 'adapter');
    assert.deepEqual(selected, [target]);
    const args = buildArguments(selected);
    assert.ok(args.includes(`-PcompixelTargets=${target.target}`));
    assert.deepEqual(args.filter(arg => arg.endsWith(':build')), [`:minecraft:${target.adapter}:build`]);
    assert.throws(() => selectedEntries('{"include":[{"adapter":"unknown"}]}', metadata.targets, 'adapter'), /Unknown/);
    assert.throws(() => selectedEntries(JSON.stringify({ include: [target, target] }), metadata.targets, 'adapter'), /duplicate/);
    assert.throws(() => selectedEntries('{}', metadata.targets, 'adapter'), /include array/);
    assert.throws(() => buildArguments([]), /No adapters/);
});

test('staging supports a partial CurseForge build and a complete GitHub release without mixing adapter checksums', t => {
    const directory = temporaryDirectory(t);
    const metadata = releaseMetadata();
    for (const upload of metadata.uploads) {
        const release = path.join(directory, upload.project, 'build/release');
        fs.mkdirSync(release, { recursive: true });
        fs.writeFileSync(path.join(release, upload.file), `archive ${upload.file}`);
        fs.writeFileSync(path.join(release, 'old-version.jar'), 'must not be staged');
    }
    stageArtifacts(metadata, metadata.targets.slice(0, 1), directory);
    const combined = path.join(directory, 'dist/github');
    assert.equal(fs.readdirSync(combined).filter(file => file.endsWith('.jar')).length, 2);
    verifyArtifacts(metadata, metadata.targets[0].adapter, path.join(directory, 'dist', metadata.targets[0].adapter));
    assert.throws(() => verifyArtifacts(metadata, 'all', combined));

    stageArtifacts(metadata, metadata.targets.slice(1), directory);
    const manifest = verifyArtifacts(metadata, 'all', combined);
    assert.equal(manifest.trim().split('\n').length, metadata.uploads.length);
    for (const target of metadata.targets) verifyArtifacts(metadata, target.adapter, path.join(directory, 'dist', target.adapter));
    fs.writeFileSync(path.join(combined, metadata.uploads[0].file), 'corrupted');
    assert.throws(() => verifyArtifacts(metadata, 'all', combined), /checksum mismatch/);
});

test('per-file publishing retains version, loader, provider and changelog metadata without enabling another platform', t => {
    const directory = temporaryDirectory(t);
    const metadata = releaseMetadata();
    const changelog = '- Notes with literal $() and `backticks`.\n- Another change.';
    const context = { project: '123', token: 'test-token', repository: 'owner/repo', sha: 'a'.repeat(40) };
    for (const upload of metadata.uploads) {
        const inputs = curseforgeInputs(metadata, upload, changelog, context);
        assert.equal(inputs['game-versions'], upload.target);
        assert.equal(inputs.loaders, upload.loader);
        assert.equal(inputs.java, upload.java);
        assert.equal(inputs.dependencies, upload.variant === 'standard' && upload.provider ? `${upload.provider}(required)` : '');
        assert.ok(inputs.changelog.startsWith(changelog));
        assert.ok(inputs.changelog.includes(upload.installation));
        assert.equal(inputs['retry-attempts'], '1');
        assert.equal(inputs['github-token'], undefined);
    }

    const action = path.join(directory, 'fake action');
    fs.mkdirSync(path.join(action, 'dist'), { recursive: true });
    fs.writeFileSync(path.join(action, 'dist/index.js'), `
        require('node:fs').writeFileSync(process.env.TEST_RESULT, JSON.stringify({
            cwd: process.cwd(), inputs: Object.fromEntries(Object.entries(process.env).filter(([key]) => key.startsWith('INPUT_')))
        }));
    `);
    const inputs = curseforgeInputs(metadata, metadata.uploads[0], changelog, context);
    const result = path.join(directory, 'result.json');
    runMcPublish(action, inputs, directory, {
        ...process.env, TEST_RESULT: result, 'INPUT_GITHUB-TOKEN': 'must-not-be-forwarded', 'INPUT_MODRINTH-TOKEN': 'also-not-forwarded',
    });
    const captured = JSON.parse(fs.readFileSync(result, 'utf8'));
    assert.equal(fs.realpathSync(captured.cwd), fs.realpathSync(directory));
    assert.deepEqual(captured.inputs, Object.fromEntries(Object.entries(inputs).map(([key, value]) => [`INPUT_${key.toUpperCase()}`, value])));
    fs.writeFileSync(path.join(action, 'dist/index.js'), 'process.exit(7);');
    assert.throws(() => runMcPublish(action, inputs, directory), error => error.status === 7);
});

test('a rerun checks fresh receipts for each file and does not retry successful uploads', async t => {
    t.mock.method(console, 'log', () => {});
    const uploads = releaseMetadata().uploads.slice(0, 3);
    const receipts = new Set([uploads[0].file]);
    const published = [], recorded = [], checked = [];
    const operations = {
        recorded: upload => { checked.push(upload.file); return receipts.has(upload.file); },
        publish: upload => { published.push(upload.file); },
        record: upload => { recorded.push(upload.file); receipts.add(upload.file); },
    };
    await publishCurseforgeFiles(uploads, operations);
    assert.deepEqual(checked, uploads.map(upload => upload.file));
    assert.deepEqual(published, uploads.slice(1).map(upload => upload.file));
    assert.deepEqual(recorded, published);
    await publishCurseforgeFiles(uploads, operations);
    assert.equal(checked.length, uploads.length * 2);
    assert.equal(published.length, 2);
});

test('receipt lookup, upload and receipt-write failures leave other files independent and make the overall result fail', async t => {
    t.mock.method(console, 'log', () => {});
    const uploads = releaseMetadata().uploads.slice(0, 4);
    const events = [];
    await assert.rejects(publishCurseforgeFiles(uploads, {
        recorded: upload => {
            events.push(`check ${upload.file}`);
            if (upload === uploads[0]) throw new Error('receipt query unavailable');
            return false;
        },
        publish: upload => {
            events.push(`publish ${upload.file}`);
            if (upload === uploads[1]) throw new Error('upload response uncertain');
        },
        record: upload => {
            events.push(`record ${upload.file}`);
            if (upload === uploads[2]) throw new Error('receipt write unavailable');
        },
    }), /3 CurseForge files failed/);
    assert.deepEqual(events, [
        `check ${uploads[0].file}`,
        `check ${uploads[1].file}`, `publish ${uploads[1].file}`,
        `check ${uploads[2].file}`, `publish ${uploads[2].file}`, `record ${uploads[2].file}`,
        `check ${uploads[3].file}`, `publish ${uploads[3].file}`, `record ${uploads[3].file}`,
    ]);
});
