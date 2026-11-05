import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {repository, repositoryId, workflows, eligible, reconcile, runController, githubApi} from '../tools/merge-controller.mjs';

const head = 'a'.repeat(40), main = 'b'.repeat(40), other = 'c'.repeat(40);
const repo = {id: repositoryId, full_name: repository, private: false, fork: false, default_branch: 'main', allow_squash_merge: true};
const root = `/repos/${repository}`;
function fixture() {
  const pr = {number: 2, state: 'open', draft: false, merged: false, auto_merge: null, mergeable: true, mergeable_state: 'clean', changed_files: 1,
    head: {sha: head, repo}, base: {sha: main, ref: 'main', repo}};
  return JSON.parse(JSON.stringify({repo, pr, main, files: [{filename: '.github/workflows/ci.yml', status: 'modified'}], threads: [], rules: [
    {type: 'deletion'}, {type: 'non_fast_forward'},
    {type: 'pull_request', parameters: {required_approving_review_count: 0, require_code_owner_review: false, require_last_push_approval: false,
      required_review_thread_resolution: true, required_reviewers: [], require_extra_approval_for_unattributed_changes: false}},
    {type: 'required_status_checks', parameters: {strict_required_status_checks_policy: true, required_status_checks: Object.values(workflows).flat().map(context => ({context, integration_id: 15368}))}}
  ], runs: Object.fromEntries(Object.entries(workflows).map(([name, jobs], i) => [name, {
    id: 100 + i, check_suite_id: 200 + i, path: `.github/workflows/${name}`, event: 'pull_request', head_sha: head,
    repository: repo, head_repository: repo, status: 'completed', conclusion: 'success', run_attempt: 1, pull_requests: [pr],
    suite: {app: {id: 15368}, head_sha: head, status: 'completed', conclusion: 'success'}, jobs: jobs.map(name => ({name, status: 'completed', conclusion: 'success', head_sha: head, run_attempt: 1}))
  }]))}));
}
assert.equal(eligible(fixture()), null, 'sensitive workflow changes have no blanket exclusion');
for (const mutate of [
  s => s.repo.id++, s => s.repo.private = true, s => s.repo.full_name = 'other/repo', s => s.repo.default_branch = 'dev',
  s => delete s.repo.allow_squash_merge, s => s.pr.head.repo.fork = true, s => s.pr.head.repo.id++,
  s => s.pr.draft = true, s => s.pr.state = 'closed', s => s.pr.auto_merge = {}, s => s.pr.base.sha = other,
  s => s.pr.mergeable = null, s => s.pr.mergeable_state = 'behind', s => s.pr.changed_files++,
  s => s.threads.push({isResolved: false, isOutdated: true}), s => s.threads = null,
  s => s.rules[2].parameters.required_approving_review_count = 1, s => s.rules[2].parameters.require_code_owner_review = true,
  s => s.rules[2].parameters.require_last_push_approval = true, s => s.rules[2].parameters.required_reviewers = [{}],
  s => s.rules[2].parameters.require_extra_approval_for_unattributed_changes = true,
  s => s.rules[2].parameters.required_review_thread_resolution = false,
  s => s.rules[3].parameters.strict_required_status_checks_policy = false,
  s => s.rules[3].parameters.required_status_checks.pop(), s => s.rules[3].parameters.required_status_checks[0].integration_id = 1,
  s => s.rules[3].parameters.required_status_checks.push({context: 'unmapped', integration_id: 15368}),
  s => s.rules.shift(), s => s.runs['ci.yml'].head_sha = other,
  s => s.runs['ci.yml'].pull_requests[0].base.sha = other, s => s.runs['ci.yml'].pull_requests = [],
  s => s.runs['ci.yml'].suite.app.id = 1, s => s.runs['ci.yml'].event = 'push',
  s => s.runs['ci.yml'].suite.conclusion = 'failure',
  s => s.runs['ci.yml'].status = 'queued', s => s.runs['android.yml'].jobs[1].conclusion = 'skipped',
  s => s.runs['android.yml'].jobs[2].run_attempt = 0, s => s.runs['ci.yml'].jobs.push(s.runs['ci.yml'].jobs[0])
]) {
  const s = fixture(); mutate(s); assert.notEqual(eligible(s), null);
}

function fake({onRead = () => {}, unconfirmed = false, newerFailure = false} = {}) {
  let reads = 0; const writes = []; const s = fixture();
  const api = async (path, options = {}) => {
    if (options.method === 'PUT') { writes.push({path, ...options}); return {merged: !unconfirmed, sha: other}; }
    if (path === root) { reads++; onRead(s, reads); return structuredClone(s.repo); }
    if (path === `${root}/pulls/2`) return structuredClone(s.pr);
    if (path === `${root}/git/ref/heads/main`) return {object: {sha: s.main}};
    if (path === `${root}/rules/branches/main`) return structuredClone(s.rules);
    if (path.startsWith(`${root}/pulls/2/files?`)) return structuredClone(s.files);
    if (path === '/graphql') return {data: {repository: {pullRequest: {reviewThreads: {nodes: structuredClone(s.threads), pageInfo: {hasNextPage: false}}}}}};
    for (const [name, run] of Object.entries(s.runs)) {
      if (path.startsWith(`${root}/actions/workflows/${name}/runs?`)) return {workflow_runs: structuredClone(newerFailure ? [run, {...run, id: run.id + 1000, conclusion: 'failure'}] : [run])};
      if (path.startsWith(`${root}/actions/runs/${run.id}/attempts/1/jobs?`) || path.startsWith(`${root}/actions/runs/${run.id + 1000}/attempts/1/jobs?`)) return {jobs: structuredClone(run.jobs)};
      if (path === `${root}/check-suites/${run.check_suite_id}`) return structuredClone(run.suite);
    }
    throw Error(`Unexpected fixture request: ${path}`);
  };
  return {api, writes, get reads() { return reads; }};
}
let f = fake();
assert.equal((await reconcile(f.api, 2, main, false)).merged, true);
assert.equal(f.reads, 2);
assert.deepEqual(f.writes, [{path: `${root}/pulls/2/merge`, method: 'PUT', body: {sha: head, merge_method: 'squash'}}]);
f = fake(); assert.equal((await reconcile(f.api, 2, main, true)).eligible, true); assert.equal(f.writes.length, 0);
f = fake(); assert.equal((await reconcile(f.api, 2, other, false)).reason, 'controller-revision'); assert.equal(f.writes.length, 0);
for (const change of [s => s.pr.head.sha = other, s => {s.main = other; s.pr.base.sha = other;}, s => s.threads.push({isResolved: false}),
  s => s.rules = [], s => s.runs['ci.yml'].conclusion = 'failure']) {
  f = fake({onRead: (s, n) => {if (n === 2) change(s);}});
  assert.equal((await reconcile(f.api, 2, main, false)).merged, false); assert.equal(f.writes.length, 0);
}
f = fake({onRead: (s, n) => {if (n === 2) s.threads.push({id: 'synthetic-thread', isResolved: true});}});
assert.equal((await reconcile(f.api, 2, main, false)).reason, 'changed-evidence'); assert.equal(f.writes.length, 0);
f = fake(); let finalRefs = 0;
const finalRace = async (path, options) => {
  if (path === `${root}/git/ref/heads/main` && ++finalRefs === 3) return {object: {sha: other}};
  return f.api(path, options);
};
assert.equal((await reconcile(finalRace, 2, main, false)).reason, 'final-race'); assert.equal(f.writes.length, 0);
f = fake({newerFailure: true}); assert.equal((await reconcile(f.api, 2, main, false)).merged, false); assert.equal(f.writes.length, 0);
f = fake({unconfirmed: true}); await assert.rejects(reconcile(f.api, 2, main, false)); assert.equal(f.writes.length, 1, 'unknown write outcomes are never retried');
f = fake(); const event = {repository: repo, workflow_run: {...fixture().runs['ci.yml']}};
assert.equal((await runController(f.api, event, 'workflow_run', main, false))[0].merged, true);
f = fake(); event.workflow_run.event = 'pull_request_review_comment';
assert.equal((await runController(f.api, event, 'workflow_run', main, false))[0].merged, true);
f = fake(); event.workflow_run.head_repository = {...repo, fork: true};
assert.deepEqual(await runController(f.api, event, 'workflow_run', main, false), []); assert.equal(f.reads, 0);
await assert.rejects(runController(f.api, {repository: repo, inputs: {pull_number: 2}}, 'workflow_dispatch', main, false));
const dispatch = {repository: repo, inputs: {pull_number: '2', mode: 'reconcile', head_sha: head, base_sha: main}};
f = fake(); assert.equal((await runController(f.api, dispatch, 'workflow_dispatch', main, false))[0].merged, true);
for (const change of [i => i.head_sha = other, i => i.base_sha = other]) {
  const stale = structuredClone(dispatch); change(stale.inputs); f = fake();
  assert.equal((await runController(f.api, stale, 'workflow_dispatch', main, false))[0].reason, 'stale-dispatch');
  assert.equal(f.writes.length, 0);
}
for (const change of [i => delete i.head_sha, i => i.base_sha = 'main', i => i.mode = 'readiness']) {
  const bad = structuredClone(dispatch); change(bad.inputs); f = fake();
  await assert.rejects(runController(f.api, bad, 'workflow_dispatch', main, false)); assert.equal(f.writes.length, 0);
}
f = fake(); assert.equal((await runController(f.api, {repository: repo, inputs: {pull_number: 2, mode: 'readiness'}}, 'workflow_dispatch', main, true))[0].eligible, true);
assert.equal(f.writes.length, 0);
await assert.rejects(runController(f.api, {repository: repo}, 'pull_request_target', main, false));

const calls = []; const api = githubApi('synthetic-token', async (url, options) => {calls.push({url, options}); return {ok: true, json: async () => ({})};});
await assert.rejects(api('/repos/other/repository/pulls/2/merge', {method: 'PUT', body: {sha: head, merge_method: 'squash'}}));
await assert.rejects(api(`${root}/actions/runs/1/rerun`, {method: 'POST'}));
await assert.rejects(api('/graphql', {method: 'POST', body: {query: 'mutation{noop}'}}));
assert.equal(calls.length, 0);
await api(`${root}/pulls/2/merge`, {method: 'PUT', body: {sha: head, merge_method: 'squash'}});
assert.equal(calls.length, 1); assert.equal(calls[0].options.redirect, 'error');

const workflow = readFileSync(new URL('../.github/workflows/merge-controller.yml', import.meta.url), 'utf8');
assert.ok(workflow.includes("github.repository_id == '1446445212'"));
assert.ok(workflow.includes("github.ref == 'refs/heads/main'"));
assert.equal((workflow.match(/ref: \$\{\{ github.sha \}\}/g) || []).length, 2);
assert.equal((workflow.match(/persist-credentials: false/g) || []).length, 2);
assert.ok(!/pull_request_target|download-artifact|self-hosted|schedule:|npm |pnpm |gradlew|pull-requests: write/.test(workflow));
assert.ok(!/contents: write/.test(workflow.slice(workflow.indexOf('  readiness:'))));
for (const match of workflow.matchAll(/uses: ([^\s]+)/g)) assert.match(match[1], /@[a-f0-9]{40}$/);
const signal = readFileSync(new URL('../.github/workflows/merge-review-signal.yml', import.meta.url), 'utf8');
assert.ok(signal.includes('permissions: {}'));
assert.ok(!/uses:|checkout|secrets\.|github.token|pull_request_target|write|self-hosted|schedule:/.test(signal));
assert.ok(signal.includes('pull_request_review_comment:'));
assert.ok(!signal.includes('pull_request_review_thread:'));
assert.ok(workflow.includes('default: readiness'));
console.log('Mobile merge controller synthetic identity/protection/CI/race/API boundary tests passed. No real merge request executed.');
