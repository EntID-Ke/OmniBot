// Uses a disposable npm installation and empty homes. No real account or paid inference.
import assert from 'node:assert/strict';
import {spawn, spawnSync} from 'node:child_process';
import {createServer} from 'node:http';
import {mkdtemp, mkdir, readFile, writeFile, rm} from 'node:fs/promises';
import {join, resolve} from 'node:path';
import {tmpdir} from 'node:os';
import {createInterface} from 'node:readline';
import {fileURLToPath} from 'node:url';
import {respondHarnessSuccess} from './harness-success-fixture.mjs';
const [installation] = process.argv.slice(2);
assert(installation, 'Pass a disposable npm installation');
const modules = resolve(installation, 'node_modules');
const adapter = join(modules, '@agentclientprotocol/codex-acp');
const cli = join(modules, '.bin/codex');
const installer = await readFile(new URL('../app/src/main/assets/acp/install-codex.sh', import.meta.url), 'utf8');
const completionPatch = installer.split("<<'OOB_CODEX_PATCH'\n")[1]?.split('\nOOB_CODEX_PATCH')[0];
assert(completionPatch);
assert.equal(spawnSync(process.execPath, ['-', adapter], {input: completionPatch, encoding:'utf8'}).status, 0);
for (const patch of ['codex-model-catalog.cjs','codex-runtime-diagnostics.cjs']) {
  const result = spawnSync(process.execPath, [fileURLToPath(new URL(`../app/src/main/assets/acp/${patch}`, import.meta.url)), adapter], {encoding:'utf8'});
  assert.equal(result.status, 0, result.stderr);
}
const root = await mkdtemp(join(tmpdir(), 'oob-cli-model-parity-'));
const modelsUsed = [];
const server = createServer(async (req,res) => {
  let raw=''; for await (const chunk of req) raw += chunk;
  const body = JSON.parse(raw || '{}');
  if (req.url.endsWith('/responses')) modelsUsed.push({model:body.model,input:JSON.stringify(body.input)});
  respondHarnessSuccess(req,res,body);
});
await new Promise(r => server.listen(0,'127.0.0.1',r));
const children=[];
function rpc(command,args,env) {
  const child=spawn(command,args,{env,cwd:root,stdio:['pipe','pipe','pipe'],detached:true}); children.push(child);
  let stderr='';child.stderr.on('data',c=>stderr=(stderr+c).slice(-8000));
  const pending=new Map();let next=0;
  createInterface({input:child.stdout}).on('line',line=>{
    let message;try{message=JSON.parse(line)}catch{return}
    if(message.method && message.id!==undefined) child.stdin.write(JSON.stringify({jsonrpc:'2.0',id:message.id,error:{code:-32601,message:'Fixture grants no tools'}})+'\n');
    else pending.get(message.id)?.(message);
  });
  child.stdin.on('error',()=>{});
  return {
    notify(method,params={}){child.stdin.write(JSON.stringify({jsonrpc:'2.0',method,params})+'\n')},
    async request(method,params={}) {
      const id=++next;let timer;
      try {
        const response=await Promise.race([new Promise(r=>{pending.set(id,r);child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n')}),new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error(`${method} timeout: ${stderr}`)),30000)})]);
        assert(!response.error, `${method}: ${JSON.stringify(response.error)} ${stderr}`);
        return response.result;
      } finally{clearTimeout(timer);pending.delete(id)}
    },
  };
}
try {
  const env={PATH:process.env.PATH, HOME:root, CODEX_HOME:join(root,'codex'), CODEX_PATH:cli,
    OMNIBOT_CODEX_INCLUDE_HIDDEN_MODELS:'1', NO_PROXY:'127.0.0.1,localhost',no_proxy:'127.0.0.1,localhost'};
  await mkdir(env.CODEX_HOME);
  await writeFile(join(env.CODEX_HOME,'config.toml'), `model = "gpt-6.1-sol"\nmodel_provider = "fixture"\n[model_providers.fixture]\nname = "Local fixture"\nbase_url = "http://127.0.0.1:${server.address().port}/v1"\nwire_api = "responses"\nrequires_openai_auth = true\n`,{mode:0o600});
  await writeFile(join(env.CODEX_HOME,'auth.json'), JSON.stringify({OPENAI_API_KEY:'local-parity-fixture-value'}),{mode:0o600});
  const version=spawnSync(cli,['--version'],{env,encoding:'utf8'}).stdout.trim();
  assert.equal(version,'codex-cli 0.163.0');
  const native=rpc(cli,['app-server'],env);
  await native.request('initialize',{clientInfo:{name:'oob_model_parity',version:'1'},capabilities:{experimentalApi:true}});
  native.notify('initialized');
  const nativeModels=[];let cursor=null;
  do {
    const result=await native.request('model/list',{includeHidden:false,limit:2,cursor});
    nativeModels.push(...result.data);cursor=result.nextCursor;
  } while(cursor);
  const expected=nativeModels.map(m=>m.model);
  assert(expected.includes('gpt-6.1-sol'));assert(expected.includes('gpt-6-luna'));
  assert(nativeModels.every(m=>!m.hidden));
  const acp=rpc(join(modules,'.bin/codex-acp'),[],env);
  await acp.request('initialize',{protocolVersion:1,clientInfo:{name:'oob-model-parity',version:'1'},clientCapabilities:{}});
  const session=await acp.request('session/new',{cwd:root,mcpServers:[]});
  const option=session.configOptions.find(o=>o.category==='model');
  assert(option);
  const actual=option.options.flatMap(o=>o.options||[o]).map(o=>o.value);
  assert.deepEqual(actual,expected,'ACP must expose exactly the CLI /model visible catalog');
  for (const model of expected) {
    const update=await acp.request('session/set_config_option',{sessionId:session.sessionId,configId:option.id,value:model});
    assert.equal(update.configOptions.find(o=>o.id===option.id).currentValue,model);
    modelsUsed.length=0;
    const marker = `OOB_SELECTION_${expected.indexOf(model)}`;
    const response=await acp.request('session/prompt',{sessionId:session.sessionId,prompt:[{type:'text',text:`${marker}: Reply OK`}]});
    assert.equal(response.stopReason,'end_turn');
    const selectedRequests = modelsUsed.filter(request=>request.input.includes(marker));
    assert(selectedRequests.length>0,'Selected model must reach the local wire endpoint');
    assert(selectedRequests.every(request=>request.model===model),`Wrong model used after selecting ${model}: ${JSON.stringify(selectedRequests)}`);
  }
  console.log(JSON.stringify({passed:true,accountUsed:false,realInference:false,version,cliVisibleModels:expected,acpModels:actual,selectionAndWireVerified:expected}));
} finally {
  for (const child of children) {try{process.kill(-child.pid,'SIGTERM')}catch{}}
  await Promise.all(children.map(child=>child.exitCode !== null || child.signalCode !== null
    ? Promise.resolve() : new Promise(r=>child.once('close',r))));
  server.closeAllConnections();await new Promise(r=>server.close(r));
  await rm(root,{recursive:true,force:true,maxRetries:5,retryDelay:200});
}
