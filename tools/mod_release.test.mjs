import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { assertTagCommit, releaseMetadata, verifyArtifacts } from './mod_release.mjs';

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
    const temporaryRoot = fs.realpathSync(os.tmpdir());
    const directory = fs.mkdtempSync(path.join(temporaryRoot, 'composemc-release-'));
    t.after(() => {
        assert.equal(path.dirname(fs.realpathSync(directory)), temporaryRoot);
        fs.rmSync(directory, { recursive: true, force: true });
    });
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
