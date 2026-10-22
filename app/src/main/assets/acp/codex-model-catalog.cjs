// Version-checked compatibility with Codex CLI's /model picker. Model IDs,
// access checks and pagination remain owned by the official app server.
const fs = require('node:fs');
const path = require('node:path');
const root = process.argv[2];
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
if (pkg.version !== '1.10.0') throw Error('Unsupported Codex ACP catalog patch version');
const file = path.join(root, 'dist/index.js');
let source = fs.readFileSync(file, 'utf8');
const marker = '// OOB CLI model picker catalog v2';
if (!source.includes(marker)) {
  const legacy = `this.codexClient.listModels({ cursor, limit: null,
        // OOB official subscription model catalog v1
        includeHidden: process.env.OMNIBOT_CODEX_INCLUDE_HIDDEN_MODELS === "1"
      })`;
  const before = source.includes('// OOB official subscription model catalog v1')
    ? legacy : 'this.codexClient.listModels({ cursor, limit: null })';
  if (source.split(before).length !== 2) throw Error('Codex ACP catalog patch source mismatch');
  // The CLI requests all entries internally, then its picker filters hidden
  // presets. Ask the same server to apply that filter; never maintain a list.
  const after = `this.codexClient.listModels({ cursor, limit: null,
        ${marker}
        includeHidden: false
      })`;
  const temporary = file + '.omnibot-catalog-tmp';
  fs.writeFileSync(temporary, source.replace(before, after), { mode: fs.statSync(file).mode });
  fs.renameSync(temporary, file);
}
