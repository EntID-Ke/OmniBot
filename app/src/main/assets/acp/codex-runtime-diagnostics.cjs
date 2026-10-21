// Observe failures before the pinned ACP SDK converts them to "Internal error".
// No request arguments, protocol traffic, account files or environment dumps.
const fs = require('node:fs');
const path = require('node:path');
const root = process.argv[2];
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
if (pkg.version !== '1.10.0') throw Error('Unsupported Codex ACP diagnostic patch version');
const file = path.join(root, 'dist/index.js');
let source = fs.readFileSync(file, 'utf8');
const originalSource = source;
function replaceOnce(before, after) {
  if (source.split(before).length !== 2) throw Error('Codex ACP diagnostic patch source mismatch');
  source = source.replace(before, after);
}
const marker = '// OOB ACP error diagnostics v1';
if (!source.includes(marker)) {
  const helper = String.raw`
// OOB ACP error diagnostics v1
function omnibotDiagnosticRedact(value) {
  return String(value)
    .replace(/-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)/g, '[PRIVATE KEY REMOVED]')
    .replace(/\b(?:Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+/gi, '[AUTH REMOVED]')
    .replace(/(["']?(?:[a-z_]*api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|device[_-]?code|user[_-]?code|code[_-]?verifier|authorization[_-]?code|client[_-]?secret|private[_-]?key|token|authorization|password|passwd|secret|cookie|set-cookie)["']?\s*[:=]\s*)(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;}]+)/gi, '$1[REDACTED]')
    .replace(/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/g, '[JWT REMOVED]')
    .replace(/sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{16,}/g, '[API KEY REMOVED]')
    .replace(/https?:\/\/[^\s<>"']+/g, value => {
      try { const url = new URL(value); url.username = ''; url.password = ''; url.search = ''; url.hash = ''; return url.toString(); }
      catch { return '[URL REMOVED]'; }
    })
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '[EMAIL REMOVED]');
}
function omnibotDiagnosticError(error, depth = 0) {
  if (depth > 4 || error == null) return undefined;
  if (typeof error !== 'object') return omnibotDiagnosticRedact(error).slice(0, 2048);
  const result = {};
  for (const key of ['name', 'message', 'detail', 'reason', 'code', 'status', 'type', 'failureKind', 'stack', 'cause', 'error', 'data']) {
    // Error.message/stack are non-enumerable. Never stringify an entire error
    // or arbitrary data: those may also carry params, credentials or prompts.
    if (error[key] !== undefined) result[key] = omnibotDiagnosticError(error[key], depth + 1);
  }
  return result;
}
async function omnibotObserveAcpFailure(method, operation, recentStderr = () => '') {
  try { return await operation(); }
  catch (error) {
    try {
      console.error('[OMNIBOT_ACP_ERROR] ' + JSON.stringify({method,
        error: omnibotDiagnosticError(error),
        stderr: omnibotDiagnosticRedact(recentStderr()).slice(-2048)}));
    } catch { /* A diagnostic must never replace the original failure. */ }
    throw error;
  }
}
`;
  replaceOnce('function startAcpServer() {', helper + '\nfunction startAcpServer() {');
  for (const [method, call] of [
    ['initialize', 'initialize(ctx.params)'],
    ['session/new', 'newSession(ctx.params)'],
    ['session/load', 'loadSession(ctx.params)'],
    ['session/resume', 'resumeSession(ctx.params)'],
    ['session/set_config_option', 'setSessionConfigOption(ctx.params)'],
    ['session/prompt', 'prompt(ctx.params, ctx.signal)'],
  ]) {
    replaceOnce(`(ctx) => getAgent().${call})`,
      `(ctx) => omnibotObserveAcpFailure(${JSON.stringify(method)}, () => getAgent().${call}, () => getAgent().getRecentStderr()))`);
  }
}
// Also upgrade the v1 helper already installed by earlier test APKs. Only
// normalize the observed missing-rollout error, using the SDK's own JSON-RPC
// error type. Other errors keep their original identity and behavior.
if (!source.includes('// OOB ACP missing rollout normalization v2')) {
  replaceOnce('    throw error;\n  }\n}\n\nfunction startAcpServer() {', String.raw`    // OOB ACP missing rollout normalization v2
    if ((method === 'session/load' || method === 'session/resume') &&
        error?.code === -32600 &&
        /^no rollout found for thread id [0-9a-f-]+$/i.test(error?.message ?? '')) {
      throw new RequestError(error.code, error.message);
    }
    throw error;
  }
}

function startAcpServer() {`);
}
if (source !== originalSource) {
  const temporary = file + '.omnibot-diagnostic-tmp';
  fs.writeFileSync(temporary, source, {mode: fs.statSync(file).mode});
  fs.renameSync(temporary, file);
}
