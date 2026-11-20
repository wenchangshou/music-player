#!/usr/bin/env node
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const remoteDir = __dirname;
const srcDir = path.join(remoteDir, 'src');
const outAppJs = path.join(remoteDir, 'app.js');

function build() {
  const startTime = Date.now();
  if (!fs.existsSync(srcDir)) {
    console.error(`Error: src directory not found at ${srcDir}`);
    process.exit(1);
  }

  const files = fs.readdirSync(srcDir)
    .filter(f => f.endsWith('.js') && /^\d+/.test(f))
    .sort((a, b) => {
      const numA = parseInt(a.match(/^(\d+)/)[1], 10);
      const numB = parseInt(b.match(/^(\d+)/)[1], 10);
      return numA - numB;
    });

  if (files.length === 0) {
    console.error('Error: No module files found in src/');
    process.exit(1);
  }

  let totalLines = 0;
  const chunks = [];

  files.forEach(file => {
    const filePath = path.join(srcDir, file);
    const content = fs.readFileSync(filePath, 'utf8');
    const lineCount = content.split('\n').length;
    totalLines += lineCount;
    chunks.push(content);
  });

  const bundled = chunks.join('\n');
  fs.writeFileSync(outAppJs, bundled, 'utf8');

  // Syntax check
  try {
    execSync(`node -c "${outAppJs}"`);
  } catch (err) {
    console.error('Syntax check failed on bundled app.js:', err.message);
    process.exit(1);
  }

  const stat = fs.statSync(outAppJs);
  const elapsed = Date.now() - startTime;
  console.log(`[Build OK] Bundled ${files.length} modules (${totalLines} lines, ${(stat.size / 1024).toFixed(1)} KB) in ${elapsed}ms -> ${outAppJs}`);
}

build();

if (process.argv.includes('--watch')) {
  console.log(`Watching ${srcDir} for changes...`);
  fs.watch(srcDir, { recursive: true }, (eventType, filename) => {
    if (filename && filename.endsWith('.js')) {
      console.log(`Change detected in ${filename}, rebuilding...`);
      try {
        build();
      } catch (e) {
        console.error('Build error:', e);
      }
    }
  });
}
