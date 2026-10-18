// Use a disposable pinned npm installation; never opens a user account home.
import {readFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const root = resolve(process.argv[2], 'node_modules/@agentclientprotocol/codex-acp');
const patch = fileURLToPath(new URL('../app/src/main/assets/acp/codex-model-catalog.cjs', import.meta.url));
let previous;
for (let pass = 0; pass < 2; pass++) {
  const result = spawnSync(process.execPath, [patch, root], {encoding: 'utf8'});
  assert.equal(result.status, 0, result.stderr);
  const source = await readFile(resolve(root, 'dist/index.js'), 'utf8');
  if (previous) assert.equal(source, previous, 'Patch must be idempotent');
  previous = source;
}
const method = previous.split('  async fetchAvailableModels() {\n')[1]?.split('\n  async runSessionListDiagnostics()')[0];
assert(method?.endsWith('  }'), 'Pinned official catalog method changed');
const fetchModels = new Function('process', `return async function() {${method.slice(0, -3)}}`);
for (const subscription of [false, true]) {
  const calls = [];
  const official = Array.from({length: 13}, (_, index) => ({id: `official-fixture-${index}`}));
  const owner = {codexClient: {listModels: async args => {
    calls.push(args);
    const start = args.cursor == null ? 0 : Number(args.cursor);
    return {data: official.slice(start, start + 5), nextCursor: start + 5 < official.length ? String(start + 5) : null};
  }}};
  const result = await fetchModels({env: {OMNIBOT_CODEX_INCLUDE_HIDDEN_MODELS: subscription ? '1' : '0'}}).call(owner);
  assert.deepEqual(result, official, 'Keep every official page; no four-model limit');
  assert.equal(calls.length, 3);
  assert(calls.every(args => args.includeHidden === subscription));
}
console.log('PASS pinned official catalog: complete pagination, subscription hidden entries, API isolation, idempotent upgrade');
