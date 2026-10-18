// Narrow compatibility patch for the pinned official ACP adapter. No account
// files, model IDs or credentials are read here. The official server remains
// responsible for availability and access checks.
const fs = require('node:fs');
const path = require('node:path');
const root = process.argv[2];
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
if (pkg.version !== '1.10.0') throw Error('Unsupported Codex ACP catalog patch version');
const file = path.join(root, 'dist/index.js');
const marker = '// OOB official subscription model catalog v1';
const source = fs.readFileSync(file, 'utf8');
if (!source.includes(marker)) {
  const before = 'this.codexClient.listModels({ cursor, limit: null })';
  if (source.split(before).length !== 2) throw Error('Codex ACP catalog patch source mismatch');
  const after = `this.codexClient.listModels({ cursor, limit: null,
        ${marker}
        includeHidden: process.env.OMNIBOT_CODEX_INCLUDE_HIDDEN_MODELS === "1"
      })`;
  const temporary = file + '.omnibot-catalog-tmp';
  fs.writeFileSync(temporary, source.replace(before, after), { mode: fs.statSync(file).mode });
  fs.renameSync(temporary, file);
}
