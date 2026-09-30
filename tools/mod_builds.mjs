// The approved publisher dispatches a build-only matrix; no publication secrets leave its job.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';
import { githubOutput, releaseMetadata } from './mod_release.mjs';
import { selectedEntries } from './mod_publish.mjs';

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

async function main() {
    const metadata = releaseMetadata();
    if (process.argv[2] === 'matrix') {
        if (!/^[0-9a-f]{40}$/.test(process.env.REVISION ?? '') ||
            execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim() !== process.env.REVISION) {
            throw new Error('The build checkout must match the approved release revision');
        }
        githubOutput({ builds: JSON.stringify(buildMatrix(metadata, process.env.TARGETS ?? '')) });
    } else if (process.argv[2] === 'dispatch') {
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
    } else {
        throw new Error('Usage: mod_builds.mjs matrix | dispatch');
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
