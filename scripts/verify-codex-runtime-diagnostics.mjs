// Disposable pinned npm fixture only. No account homes or credential files.
import {mkdtemp, mkdir, readFile, writeFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn, spawnSync} from 'node:child_process';
import {createInterface} from 'node:readline';
import assert from 'node:assert/strict';

const originalRoot = resolve(process.argv[2], 'node_modules/@agentclientprotocol/codex-acp');
const root = await mkdtemp(resolve(tmpdir(), 'omnibot-diagnostic-fixture-'));
const patch = fileURLToPath(new URL('../app/src/main/assets/acp/codex-runtime-diagnostics.cjs', import.meta.url));
try {
  await mkdir(resolve(root, 'dist'));
  await writeFile(resolve(root, 'package.json'), await readFile(resolve(originalRoot, 'package.json')));
  await writeFile(resolve(root, 'dist/index.js'), await readFile(resolve(originalRoot, 'dist/index.js')));
  let previous;
  for (let pass = 0; pass < 2; pass++) {
    const result = spawnSync(process.execPath, [patch, root], {encoding: 'utf8'});
    assert.equal(result.status, 0, result.stderr);
    const source = await readFile(resolve(root, 'dist/index.js'), 'utf8');
    if (previous) assert.equal(source, previous, 'Patch must be idempotent');
    previous = source;
  }
  // Simulate an installed code21 helper, then upgrade it in place. The v2
  // section is the only difference from the shipped v1 patch.
  const v1Source = previous.replace(/    \/\/ OOB ACP missing rollout normalization v2\n[\s\S]*?(?=    throw error;)/, '');
  assert.notEqual(v1Source, previous);
  await writeFile(resolve(root, 'dist/index.js'), v1Source);
  assert.equal(spawnSync(process.execPath, [patch, root]).status, 0);
  assert.equal(await readFile(resolve(root, 'dist/index.js'), 'utf8'), previous,
    'Existing v1 installations must receive the same fix as fresh installations');
  const helper = previous.split('// OOB ACP error diagnostics v1\n')[1]?.split('\nfunction startAcpServer() {')[0];
  assert(helper);
  const logs = [];
  class RequestError extends Error { constructor(code, message) { super(message); this.code = code; } }
  const observe = new Function('console', 'RequestError', helper + '\nreturn omnibotObserveAcpFailure;')({error: line => logs.push(line)}, RequestError);
  assert.equal(await observe('session/new', async () => 'original result'), 'original result');
  assert.equal(logs.length, 0, 'Successful operations must not dump traffic');
  const original = Object.assign(new Error('failed to reload config access_token=fixture-secret'), {
    code: -32603,
    data: {message: 'TLS failed', params: {prompt: 'private prompt'}, config: 'private config'},
    cause: new Error('refresh_token=fixture-refresh user@example.com https://u:p@example.com/config?secret=fixture-query'),
  });
  await assert.rejects(observe('session/load', async () => { throw original; }), error => error === original);
  assert.equal(logs.length, 1);
  const record = JSON.parse(logs[0].slice('[OMNIBOT_ACP_ERROR] '.length));
  assert.equal(record.method, 'session/load');
  assert.equal(record.error.code, '-32603');
  assert.equal(record.error.data.message, 'TLS failed');
  for (const secret of ['fixture-secret', 'fixture-refresh', 'user@example.com', 'u:p', 'fixture-query', 'private prompt', 'private config']) {
    assert(!logs[0].includes(secret), `Leaked ${secret}`);
  }
  for (const method of ['initialize', 'session/new', 'session/load', 'session/resume', 'session/set_config_option', 'session/prompt']) {
    assert(previous.includes(`omnibotObserveAcpFailure(${JSON.stringify(method)}, () => getAgent().`), `Missing handler ${method}`);
  }
  const missing = Object.assign(new Error('no rollout found for thread id aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'), {code:-32600});
  for (const method of ['session/load', 'session/resume']) {
    await assert.rejects(observe(method, () => { throw missing; }), error =>
      error instanceof RequestError && error.code === -32600 && error.message === missing.message);
  }
  await assert.rejects(observe('session/prompt', () => { throw missing; }), error => error === missing);
  const unrelated = Object.assign(new Error('provider authentication failed'), {code:-32600});
  await assert.rejects(observe('session/load', () => { throw unrelated; }), error => error === unrelated);
  // A broken diagnostic output must still preserve the original failure.
  const broken = new Function('console', 'RequestError', helper + '\nreturn omnibotObserveAcpFailure;')({error: () => { throw Error('fixture log unavailable'); }}, RequestError);
  await assert.rejects(broken('session/new', () => { throw original; }), error => error === original);
  // Exercise the real bundled SDK: it hides the original untyped upstream
  // exception on the ACP wire, while our stderr record retains its cause.
  const mock = resolve(root, 'mock-codex.cjs');
  await writeFile(mock, `#!${process.execPath}
const readline = require('node:readline');
readline.createInterface({input:process.stdin}).on('line', line => {
  const m = JSON.parse(line); if (m.id === undefined) return;
  const error = m.method === 'thread/resume'
    ? {code:-32600,message:'no rollout found for thread id aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'}
    : {code:-32603,message:'fixture_upstream_root_cause access_token=fixture-wire-secret'};
  if (m.method === 'initialize') process.stderr.write('fixture_runtime_stderr token=fixture-runtime-secret\\n');
  const result = m.method === 'initialize' ? {userAgent:'fixture',codexHome:${JSON.stringify(root)}}
    : m.method === 'config/read' ? {config:{}}
    : m.method === 'skills/list' ? {data:[]} : {};
  process.stdout.write(JSON.stringify(m.method === 'thread/start' || m.method === 'thread/resume'
    ? {id:m.id,error} : {id:m.id,result})+'\\n');
});
`, {mode: 0o700});
  const child = spawn(process.execPath, [resolve(root, 'dist/index.js')], {
    cwd: root, env: {PATH: process.env.PATH, HOME: root, CODEX_HOME: root, CODEX_PATH: mock},
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let stderr = '';
  child.stderr.on('data', chunk => { stderr += chunk; });
  const pending = new Map();
  const lines = createInterface({input: child.stdout});
  lines.on('line', line => {
    const message = JSON.parse(line);
    pending.get(message.id)?.(message);
  });
  let nextId = 0;
  const request = (method, params) => new Promise(resolve => {
    const id = ++nextId; pending.set(id, resolve);
    child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');
  });
  let timer;
  try {
    await Promise.race([
      (async () => {
        const initialized = await request('initialize', {protocolVersion:1,clientCapabilities:{}});
        assert.equal(initialized.result?.protocolVersion, 1);
        const response = await request('session/new', {cwd:root,mcpServers:[]});
        assert.equal(response.error?.code, -32603);
        assert.equal(response.error?.message, 'Internal error');
        const missingResponse = await request('session/load', {sessionId:'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',cwd:root,mcpServers:[]});
        assert.equal(missingResponse.error?.code, -32600, stderr);
        assert.equal(missingResponse.error?.message, missing.message,
          'Missing rollout cause must survive the real SDK wire for the existing restore owner');
        // stdout and stderr are separate OS pipes: wait for the error record,
        // never infer their read order from the response arrival time.
        for (let i = 0; i < 30 && !stderr.includes('[OMNIBOT_ACP_ERROR]'); i++) {
          await new Promise(resolve => setTimeout(resolve, 10));
        }
        assert(stderr.includes('fixture_upstream_root_cause'), 'Real SDK lost diagnostic cause');
        assert(stderr.includes('fixture_runtime_stderr'), 'Missing corresponding Codex stderr tail');
        assert(!stderr.includes('fixture-wire-secret'), 'Real SDK diagnostic leaked credential');
        assert(!stderr.includes('fixture-runtime-secret'), 'Codex stderr leaked credential');
      })(),
      new Promise((_, reject) => { timer = setTimeout(() => reject(Error('Pinned wire fixture timed out')), 15_000); }),
    ]);
  } finally {
    clearTimeout(timer); lines.close(); child.stdin.end(); child.kill();
    await new Promise(resolve => { if (child.exitCode != null) resolve(); else child.once('close', resolve); });
  }
  await writeFile(resolve(root, 'package.json'), '{"version":"0.0.0"}');
  assert.notEqual(spawnSync(process.execPath, [patch, root]).status, 0, 'Reject unsupported adapters');
  console.log('PASS pinned ACP diagnostics: missing rollout survives real SDK wire, fresh/v1 upgrade, unrelated failures unchanged, cause/stderr redaction, no request data, handlers, idempotence, version guard');
} finally {
  await rm(root, {recursive: true, force: true});
}
