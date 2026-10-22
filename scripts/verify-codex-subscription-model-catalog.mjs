// Disposable pinned SDK fixture. Never reads a user's Codex account home.
import {readFile,writeFile,mkdir,mkdtemp,rm} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {resolve} from 'node:path';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
const original = resolve(process.argv[2], 'node_modules/@agentclientprotocol/codex-acp');
const root = await mkdtemp(resolve(tmpdir(), 'omnibot-picker-catalog-'));
const patch = fileURLToPath(new URL('../app/src/main/assets/acp/codex-model-catalog.cjs', import.meta.url));
const plain = 'this.codexClient.listModels({ cursor, limit: null })';
const v1 = `this.codexClient.listModels({ cursor, limit: null,
        // OOB official subscription model catalog v1
        includeHidden: process.env.OMNIBOT_CODEX_INCLUDE_HIDDEN_MODELS === "1"
      })`;
const v2 = `this.codexClient.listModels({ cursor, limit: null,
        // OOB CLI model picker catalog v2
        includeHidden: false
      })`;
try {
  await mkdir(resolve(root,'dist'));
  await writeFile(resolve(root,'package.json'), await readFile(resolve(original,'package.json')));
  const source = (await readFile(resolve(original,'dist/index.js'),'utf8')).replace(v1,plain).replace(v2,plain);
  assert.equal(source.split(plain).length,2);
  let expected;
  for (const installed of [source, source.replace(plain,v1)]) {
    await writeFile(resolve(root,'dist/index.js'), installed);
    for(let pass=0;pass<2;pass++) {
      const result=spawnSync(process.execPath,[patch,root],{encoding:'utf8'});
      assert.equal(result.status,0,result.stderr);
      const output=await readFile(resolve(root,'dist/index.js'),'utf8');
      if(expected) assert.equal(output,expected,'Fresh/v1 upgrade and repeat must match');
      expected=output;
    }
  }
  const method=expected.split('  async fetchAvailableModels() {\n')[1]?.split('\n  async runSessionListDiagnostics()')[0];
  assert(method?.endsWith('  }'));
  const fetchModels=new Function('process',`return async function() {${method.slice(0,-3)}}`);
  // The official server filters hidden entries, just as /model's picker does.
  const catalog=Array.from({length:15},(_,i)=>({id:`official-fixture-${i}`,hidden:i===3||i===9}));
  const visible=catalog.filter(m=>!m.hidden);
  for(const staleFlag of ['0','1']) {
    const calls=[];
    const owner={codexClient:{listModels:async args=>{
      calls.push(args);const allowed=args.includeHidden?catalog:visible;
      const start=Number(args.cursor??0);
      return {data:allowed.slice(start,start+5),nextCursor:start+5<allowed.length?String(start+5):null};
    }}};
    assert.deepEqual(await fetchModels({env:{OMNIBOT_CODEX_INCLUDE_HIDDEN_MODELS:staleFlag}}).call(owner),visible);
    assert.equal(calls.length,3);
    assert(calls.every(args=>args.includeHidden===false));
  }
  await writeFile(resolve(root,'package.json'),'{"version":"0.0.0"}');
  assert.notEqual(spawnSync(process.execPath,[patch,root]).status,0);
  console.log('PASS official CLI picker catalog: all visible pages, hidden entries excluded, stale flags ignored, fresh/v1 upgrade, idempotence, version guard');
} finally {await rm(root,{recursive:true,force:true});}
