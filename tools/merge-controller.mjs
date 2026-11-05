import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';

export const repository = 'Digifender-LLC/whumpo-mobile';
export const repositoryId = 1446445212;
export const workflows = {
  'ci.yml': ['Mobile policy', 'Secret scan'],
  'android.yml': ['Assemble, lint and unit tests', 'Instrumented tests (API 28)', 'Instrumented tests (API 36)']
};
const root = `/repos/${repository}`;
const sha = /^[a-f0-9]{40}$/;
const required = Object.values(workflows).flat();
const threadQuery = 'query($number:Int!,$after:String){repository(owner:"Digifender-LLC",name:"whumpo-mobile"){pullRequest(number:$number){reviewThreads(first:100,after:$after){nodes{isResolved} pageInfo{hasNextPage endCursor}}}}}';
const identity = r => r?.id === repositoryId && r.full_name === repository && r.private === false && r.fork === false;

export function protectionsReady(rules) {
  if (!Array.isArray(rules)) return false;
  const prs = rules.filter(r => r.type === 'pull_request');
  if (!prs.length || !prs.every(({parameters: p}) => p?.required_approving_review_count === 0
    && p.require_code_owner_review === false && p.require_last_push_approval === false
    && (p.required_reviewers === undefined || Array.isArray(p.required_reviewers) && p.required_reviewers.length === 0)
    && (p.require_extra_approval_for_unattributed_changes === undefined || p.require_extra_approval_for_unattributed_changes === false))) return false;
  if (!prs.some(r => r.parameters.required_review_thread_resolution === true)) return false;
  if (!['deletion', 'non_fast_forward'].every(type => rules.some(r => r.type === type))) return false;
  const checkRules = rules.filter(r => r.type === 'required_status_checks');
  if (!checkRules.length || !checkRules.some(r => r.parameters?.strict_required_status_checks_policy === true)) return false;
  const checks = checkRules.flatMap(r => r.parameters?.required_status_checks ?? []);
  return required.every(name => checks.some(c => c.context === name && c.integration_id === 15368))
    && checks.every(c => required.includes(c.context) && c.integration_id === 15368);
}

export function eligible(s) {
  if (!identity(s.repo) || s.repo.default_branch !== 'main' || s.repo.allow_squash_merge !== true) return 'repository-settings';
  const p = s.pr;
  if (!Number.isSafeInteger(p?.number) || p.number < 1 || p.state !== 'open' || p.draft !== false || p.merged !== false
    || !identity(p.head?.repo) || !identity(p.base?.repo) || p.base.ref !== 'main') return 'pull-request';
  if (!sha.test(p.head.sha) || !sha.test(s.main) || p.base.sha !== s.main) return 'head-or-base';
  if (p.auto_merge !== null) return 'native-auto-merge';
  if (p.mergeable !== true || p.mergeable_state !== 'clean') return 'mergeability';
  if (!protectionsReady(s.rules)) return 'protections';
  if (!Array.isArray(s.files) || !s.files.length || s.files.length !== p.changed_files || s.files.length > 300) return 'incomplete-files';
  const validPath = name => typeof name === 'string' && !name.includes('\0') && name.split('/').every(part => part && part !== '.' && part !== '..');
  if (s.files.some(f => !validPath(f.filename) || !['added', 'modified', 'removed', 'renamed', 'copied', 'changed', 'unchanged'].includes(f.status)
    || f.status === 'renamed' && !validPath(f.previous_filename))) return 'file-metadata';
  if (!Array.isArray(s.threads) || s.threads.some(t => t.isResolved !== true)) return 'review-threads';
  for (const [name, jobs] of Object.entries(workflows)) {
    const r = s.runs?.[name];
    if (!r || r.path !== `.github/workflows/${name}` || r.event !== 'pull_request' || r.head_sha !== p.head.sha
      || !identity(r.repository) || !identity(r.head_repository) || r.status !== 'completed' || r.conclusion !== 'success'
      || !Number.isSafeInteger(r.run_attempt) || r.run_attempt < 1) return `workflow:${name}`;
    if (!r.pull_requests?.some(pr => pr.number === p.number && pr.head?.sha === p.head.sha && pr.base?.sha === s.main
      && pr.base.ref === 'main' && pr.base.repo?.id === repositoryId && pr.head.repo?.id === repositoryId)) return `workflow-revisions:${name}`;
    if (r.suite?.app?.id !== 15368 || r.suite.head_sha !== p.head.sha) return `workflow-source:${name}`;
    if (!Array.isArray(r.jobs) || r.jobs.length !== jobs.length || jobs.some(job => r.jobs.filter(j => j.name === job
      && j.status === 'completed' && j.conclusion === 'success' && j.head_sha === p.head.sha && j.run_attempt === r.run_attempt).length !== 1)) return `jobs:${name}`;
  }
  return null;
}

export function githubApi(token, fetcher = fetch) {
  if (!token) throw Error('Missing workflow token');
  return async (path, {method = 'GET', body} = {}) => {
    const read = method === 'GET' && (path === root || path.startsWith(`${root}/`)) && !path.includes('..');
    const threads = method === 'POST' && path === '/graphql' && body?.query === threadQuery;
    const merge = method === 'PUT' && new RegExp(`^${root}/pulls/[1-9][0-9]*/merge$`).test(path)
      && body?.merge_method === 'squash' && sha.test(body.sha) && Object.keys(body).length === 2;
    if (!read && !threads && !merge) throw Error('Disallowed API request');
    const response = await fetcher(`https://api.github.com${path}`, {
      method, redirect: 'error', signal: AbortSignal.timeout(20000),
      headers: {Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', 'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28'},
      ...(body ? {body: JSON.stringify(body)} : {})
    });
    if (!response.ok) throw Error(`GitHub request failed (${response.status})`);
    const value = await response.json();
    if (value.errors) throw Error('Incomplete GitHub response');
    return value;
  };
}

async function pages(api, path, key, limit = 4) {
  const all = [];
  for (let page = 1; page <= limit; page++) {
    const response = await api(`${path}${path.includes('?') ? '&' : '?'}per_page=100&page=${page}`);
    const items = key ? response[key] : response;
    if (!Array.isArray(items)) throw Error('Missing page');
    all.push(...items);
    if (items.length < 100) return all;
  }
  throw Error('Pagination exceeded bound');
}

async function threads(api, number) {
  const all = []; let after = null;
  for (let page = 0; page < 4; page++) {
    const response = await api('/graphql', {method: 'POST', body: {query: threadQuery, variables: {number, after}}});
    const c = response.data?.repository?.pullRequest?.reviewThreads;
    if (!Array.isArray(c?.nodes) || typeof c.pageInfo?.hasNextPage !== 'boolean') throw Error('Missing threads');
    all.push(...c.nodes);
    if (!c.pageInfo.hasNextPage) return all;
    if (!c.pageInfo.endCursor || c.pageInfo.endCursor === after) throw Error('Invalid thread cursor');
    after = c.pageInfo.endCursor;
  }
  throw Error('Thread pagination exceeded bound');
}

export async function snapshot(api, number) {
  if (!Number.isSafeInteger(number) || number < 1) throw Error('Invalid PR number');
  const repo = await api(root), pr = await api(`${root}/pulls/${number}`);
  if (pr.number !== number || !sha.test(pr.head?.sha)) throw Error('Invalid PR identity');
  const main = (await api(`${root}/git/ref/heads/main`)).object?.sha;
  const rules = await api(`${root}/rules/branches/main`);
  const files = await pages(api, `${root}/pulls/${number}/files`);
  const reviewThreads = await threads(api, number), runs = {};
  for (const name of Object.keys(workflows)) {
    const candidates = await pages(api, `${root}/actions/workflows/${name}/runs?event=pull_request&head_sha=${pr.head.sha}`, 'workflow_runs');
    const matching = candidates.filter(r => !Array.isArray(r.pull_requests) || !r.pull_requests.length || r.pull_requests.some(p => p.number === number));
    if (matching.some(r => !Number.isSafeInteger(r.id) || r.id < 1)) throw Error('Invalid run ID');
    const run = matching.sort((a, b) => b.id - a.id)[0];
    if (!run) continue;
    if (!Number.isSafeInteger(run.run_attempt) || run.run_attempt < 1 || !Number.isSafeInteger(run.check_suite_id)) throw Error('Invalid run attempt');
    run.jobs = await pages(api, `${root}/actions/runs/${run.id}/attempts/${run.run_attempt}/jobs`, 'jobs');
    run.suite = await api(`${root}/check-suites/${run.check_suite_id}`);
    runs[name] = run;
  }
  return {repo, pr, main, rules, files, threads: reviewThreads, runs};
}

export async function reconcile(api, number, controllerSha, dryRun = true) {
  const first = await snapshot(api, number), firstReason = eligible(first);
  if (firstReason) return {number, merged: false, reason: firstReason};
  if (first.main !== controllerSha) return {number, merged: false, reason: 'controller-revision'};
  const second = await snapshot(api, number), secondReason = eligible(second);
  if (secondReason) return {number, merged: false, reason: secondReason};
  if (first.main !== second.main || first.pr.head.sha !== second.pr.head.sha) return {number, merged: false, reason: 'changed-revisions'};
  const evidence = s => JSON.stringify({files: s.files, rules: s.rules, threads: s.threads,
    runs: Object.values(s.runs).map(r => ({id: r.id, attempt: r.run_attempt, suite: r.check_suite_id,
      status: r.status, conclusion: r.conclusion, jobs: r.jobs}))});
  if (evidence(first) !== evidence(second)) return {number, merged: false, reason: 'changed-evidence'};
  // No write follows an unchanged-status assumption: recheck the head and base at the boundary.
  const finalPr = await api(`${root}/pulls/${number}`);
  const finalMain = (await api(`${root}/git/ref/heads/main`)).object?.sha;
  if (finalPr.head?.sha !== second.pr.head.sha || finalPr.base?.sha !== second.main || finalMain !== second.main
    || finalPr.state !== 'open' || finalPr.draft !== false || finalPr.auto_merge !== null
    || finalPr.mergeable !== true || finalPr.mergeable_state !== 'clean') return {number, merged: false, reason: 'final-race'};
  if (dryRun) return {number, merged: false, eligible: true, head: second.pr.head.sha, base: second.main};
  // The SHA precondition and strict required checks enforce the remaining server-side race window.
  const result = await api(`${root}/pulls/${number}/merge`, {method: 'PUT', body: {sha: second.pr.head.sha, merge_method: 'squash'}});
  if (result.merged !== true || !sha.test(result.sha)) throw Error('Merge outcome unconfirmed; do not retry');
  return {number, merged: true, head: second.pr.head.sha, base: second.main, merge: result.sha};
}

export async function runController(api, event, eventName, controllerSha, dryRun) {
  if (!identity(event.repository) || event.repository.default_branch !== 'main' || !sha.test(controllerSha)) throw Error('Untrusted event');
  let numbers;
  if (eventName === 'workflow_dispatch' && dryRun === true) {
    const number = Number(event.inputs?.pull_number);
    if (!Number.isSafeInteger(number) || number < 1) throw Error('One positive PR number required');
    numbers = [number];
  } else if (eventName === 'workflow_run' && dryRun === false) {
    const run = event.workflow_run;
    if (!['pull_request', 'pull_request_review', 'pull_request_review_thread'].includes(run?.event)
      || run.status !== 'completed' || run.conclusion !== 'success'
      || !identity(run.repository) || !identity(run.head_repository) || !Array.isArray(run.pull_requests)) return [];
    numbers = [...new Set(run.pull_requests.map(p => p.number))];
    if (numbers.length > 3 || numbers.some(n => !Number.isSafeInteger(n) || n < 1)) throw Error('Unbounded PR event');
  } else throw Error('Unsupported event/mode');
  const results = [];
  for (const number of numbers) {
    const result = await reconcile(api, number, controllerSha, dryRun);
    results.push(result);
    if (result.merged) break;
  }
  return results;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    if (process.env.GITHUB_REPOSITORY !== repository || process.env.GITHUB_REPOSITORY_ID !== String(repositoryId)
      || process.env.GITHUB_REF !== 'refs/heads/main') throw Error('Untrusted execution context');
    const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
    const result = await runController(githubApi(process.env.GITHUB_TOKEN), event, process.env.GITHUB_EVENT_NAME,
      process.env.GITHUB_SHA, process.env.MERGE_CONTROLLER_DRY_RUN === '1');
    console.log(JSON.stringify(result));
  } catch {
    console.error('Controller stopped: incomplete evidence, race or unconfirmed outcome. No retry or bypass.');
    process.exitCode = 1;
  }
}
