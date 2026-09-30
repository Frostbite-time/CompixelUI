import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildMatrix, runParallelBuild } from './mod_builds.mjs';
import { releaseMetadata } from './mod_release.mjs';

const metadata = releaseMetadata();
const context = {
    repository: 'owner/repo', token: 'test-token', ref: 'main', revision: 'a'.repeat(40),
    targets: metadata.targets.slice(0, 2), releaseRun: '12345-2',
};

test('the worker matrix contains only selected registered targets and rejects empty, unknown or duplicate targets', () => {
    const selected = [metadata.targets[0], metadata.targets[2]];
    assert.deepEqual(buildMatrix(metadata, selected.map(target => target.target).join(',')), { include: selected });
    for (const value of ['', '99.0', '1.20.1,1.20.1']) assert.throws(() => buildMatrix(metadata, value));
});

test('dispatch pins the release revision without publication secrets and waits only for the returned build run', async t => {
    t.mock.method(console, 'log', () => {});
    const requests = [], starts = [];
    let queries = 0, waits = 0;
    const runId = await runParallelBuild(context, {
        started: (...args) => starts.push(args),
        wait: async ms => { assert.equal(ms, 15_000); waits++; },
        fetchImpl: async (url, options) => {
            requests.push(url);
            assert.equal(options.headers.Authorization, 'Bearer test-token');
            if (url.endsWith('/dispatches')) {
                assert.equal(options.method, 'POST');
                assert.deepEqual(JSON.parse(options.body), {
                    ref: 'main', return_run_details: true,
                    inputs: { revision: context.revision, targets: '1.20.1,1.21.1', release_run: '12345-2' },
                });
                return Response.json({ workflow_run_id: 456 });
            }
            assert.ok(url.endsWith('/runs/456'));
            return Response.json(++queries === 1 ? { status: 'in_progress' } : { status: 'completed', conclusion: 'success' });
        },
    });
    assert.equal(runId, 456);
    assert.equal(waits, 1);
    assert.equal(requests.length, 3);
    assert.deepEqual(starts, [[456, 'https://github.com/owner/repo/actions/runs/456']]);
});

test('failed, cancelled or timed-out build conclusions never become a successful artifact source', async t => {
    t.mock.method(console, 'log', () => {});
    for (const conclusion of ['failure', 'cancelled', 'timed_out']) {
        await assert.rejects(runParallelBuild(context, {
            fetchImpl: async url => url.endsWith('/dispatches') ? Response.json({ workflow_run_id: 456 }) :
                Response.json({ status: 'completed', conclusion }),
            wait: async () => assert.fail('Completed runs must not wait'),
        }), new RegExp(`finished with ${conclusion}`));
    }
});

test('an uncertain or rejected dispatch is not blindly repeated', async () => {
    for (const status of [204, 403, 500]) {
        let requests = 0;
        await assert.rejects(runParallelBuild(context, {
            fetchImpl: async () => { requests++; return new Response(null, { status }); },
        }), new RegExp(`HTTP ${status}`));
        assert.equal(requests, 1);
    }
});

test('a wait timeout cancels only the dispatched build workflow', async t => {
    t.mock.method(console, 'log', () => {});
    const requests = [];
    await assert.rejects(runParallelBuild(context, {
        timeout: 0,
        fetchImpl: async (url, options) => {
            requests.push([url, options.method]);
            return url.endsWith('/dispatches') ? Response.json({ workflow_run_id: 789 }) : new Response(null, { status: 202 });
        },
    }), /builds timed out/);
    assert.deepEqual(requests.map(([url, method]) => [url.split('/actions')[1], method]), [
        ['/workflows/mod-build.yml/dispatches', 'POST'], ['/runs/789/cancel', 'POST'],
    ]);
});

test('cancelling the release cancels its build without letting the aborted signal prevent cleanup', async t => {
    t.mock.method(console, 'log', () => {});
    const controller = new AbortController();
    let cancelled = false;
    await assert.rejects(runParallelBuild(context, {
        signal: controller.signal,
        started: () => controller.abort(new Error('release cancelled')),
        fetchImpl: async (url, options) => {
            if (url.endsWith('/dispatches')) return Response.json({ workflow_run_id: 789 });
            assert.ok(url.endsWith('/runs/789/cancel'));
            assert.equal(options.signal.aborted, false);
            cancelled = true;
            return new Response(null, { status: 202 });
        },
    }), /release cancelled/);
    assert.equal(cancelled, true);
});
