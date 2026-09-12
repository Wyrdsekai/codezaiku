'use strict';
// The launcher, both ways it starts the program: an installed one, and a release fetched, checked and unpacked.
// The download tests need LAUNCHER_TEST_DIST: a folder holding codezaiku-<version>.tar.gz and SHA256SUMS (the
// release-build output); without it they are skipped, and Java 21+ must be on PATH for them.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const { spawn, spawnSync } = require('child_process');

const TOOL = 'codezaiku', ENV = 'CODEZAIKU';
const LAUNCHER = path.join(__dirname, '..', 'bin', 'index.js');
const VERSION = require('../package.json').version;
const RELEASE = VERSION.replace(/-.*$/, '');   // the release the launcher fetches; the package may carry an npm-only suffix
const WIN = process.platform === 'win32';
const EXPECT_TOOL = 'code';

const made = [];
function tmp() { const d = fs.mkdtempSync(path.join(os.tmpdir(), `${TOOL}-mcp-`)); made.push(d); return d; }
// every temp home goes at the end: the download test unpacks a 60 MB runtime build into each, and a day of runs left gigabytes behind
test.after(() => { for (const d of made) fs.rmSync(d, { recursive: true, force: true }); });

/** A fake installed program: its start script echoes its arguments to stdout. */
function fakeInstall(dir, version) {
  const bin = path.join(dir, 'bin'); fs.mkdirSync(bin, { recursive: true });
  if (version) { fs.mkdirSync(path.join(dir, 'lib'), { recursive: true }); fs.writeFileSync(path.join(dir, 'lib', `${TOOL}-${version}.jar`), ''); }
  if (WIN) fs.writeFileSync(path.join(bin, `${TOOL}.bat`), `@echo off\r\necho FAKE %*\r\n`);
  else { const p = path.join(bin, TOOL); fs.writeFileSync(p, '#!/bin/sh\necho "FAKE $*"\n'); fs.chmodSync(p, 0o755); }
}

/** Run the launcher to its end; asynchronous, so the test's own download server keeps serving meanwhile. */
function runLauncher(env, input) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [LAUNCHER], { env });
    let stdout = '', stderr = '';
    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });
    const timer = setTimeout(() => child.kill('SIGKILL'), 180000);
    child.on('close', (status) => { clearTimeout(timer); resolve({ status, stdout, stderr }); });
    child.stdin.end(input);
  });
}

/** The name of the PATH variable as this process holds it: "Path" on Windows, "PATH" elsewhere. */
const PATH_KEY = Object.keys(process.env).find((k) => k.toUpperCase() === 'PATH') || 'PATH';

/**
 * An environment with no __TITLE__ in it: a fresh home (the JVM reads the real one, so the library and the config
 * are named explicitly), no installer prefix, and a PATH holding only what the download path needs — java and tar,
 * plus the system directories; on Windows the process's PATH minus any entry that names the program.
 */
function bareEnv(home) {
  const env = { ...process.env, HOME: home, USERPROFILE: home };
  delete env[`${ENV}_PREFIX`]; delete env[`${ENV}_DOWNLOAD_BASE`];
  env[`${ENV}_LIBRARY`] = path.join(home, 'library');
  env[`${ENV}_CONFIG`] = path.join(home, 'config');
  if (WIN) {
    env.LOCALAPPDATA = path.join(home, 'AppData', 'Local');
    env[PATH_KEY] = (process.env[PATH_KEY] || '').split(path.delimiter).filter((d) => !d.toLowerCase().includes(TOOL)).join(path.delimiter);
  } else {
    const dirs = new Set(['/usr/bin', '/bin']);
    for (const cmd of ['java', 'tar']) { const w = spawnSync('sh', ['-c', `command -v ${cmd}`], { encoding: 'utf8' }); if (w.status === 0) dirs.add(path.dirname(fs.realpathSync(w.stdout.trim()))); }
    env[PATH_KEY] = [...dirs].join(path.delimiter);
  }
  return env;
}

test('an installed program is started with mcp, found through the installer prefix', async () => {
  const home = tmp(); const prefix = path.join(home, 'prefix');
  if (WIN) fakeInstall(path.join(prefix, TOOL), '0.0.1'); else fakeInstall(path.join(prefix, 'share', TOOL), '0.0.1');
  if (!WIN) { fs.mkdirSync(path.join(prefix, 'bin'), { recursive: true }); const w = path.join(prefix, 'bin', TOOL); fs.writeFileSync(w, `#!/bin/sh\nexec "${path.join(prefix, 'share', TOOL, 'bin', TOOL)}" "$@"\n`); fs.chmodSync(w, 0o755); }
  const r = await runLauncher({ ...bareEnv(home), [`${ENV}_PREFIX`]: prefix }, '');
  assert.strictEqual(r.status, 0, r.stderr);
  assert.match(r.stdout, /^FAKE mcp\s*$/, r.stdout);
  assert.match(r.stderr, /starting .*mcp \(.* 0\.0\.1\)/, 'the installed version is read off its lib, through the wrapper: ' + r.stderr);
  assert.match(r.stderr, /installed .* is 0\.0\.1, this launcher starts /, 'an install older than the launcher is said out loud: ' + r.stderr);
});

test('an installed program on PATH is preferred over a download', async () => {
  const home = tmp(); const dir = path.join(home, 'onpath'); fakeInstall(dir);
  const env = bareEnv(home); env[PATH_KEY] = path.join(dir, 'bin') + path.delimiter + env[PATH_KEY];
  const r = await runLauncher(env, '');
  assert.strictEqual(r.status, 0, r.stderr);
  assert.match(r.stdout, /^FAKE mcp\s*$/, r.stdout);
});

const DIST = process.env.LAUNCHER_TEST_DIST;
const skip = DIST ? false : 'set LAUNCHER_TEST_DIST to a folder with the release tarball and SHA256SUMS';

function serve(dir, hits) {
  return new Promise((resolve) => {
    const s = http.createServer((req, res) => {
      hits.push(req.url);
      const f = path.join(dir, path.basename(req.url));
      if (!fs.existsSync(f)) { res.writeHead(404); res.end(); return; }
      res.writeHead(200); fs.createReadStream(f).pipe(res);
    });
    s.listen(0, '127.0.0.1', () => resolve(s));
  });
}

test('a release is fetched, checked against SHA256SUMS, unpacked once, and speaks MCP', { skip }, async () => {
  const home = tmp(); const hits = [];
  const server = await serve(DIST, hits);
  try {
    const env = { ...bareEnv(home), [`${ENV}_DOWNLOAD_BASE`]: `http://127.0.0.1:${server.address().port}` };
    const lines = [
      JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'launcher-test', version: '0' } } }),
      JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
      JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }),
    ].join('\n') + '\n';
    let r = await runLauncher(env, lines);
    assert.strictEqual(r.status, 0, r.stderr);
    assert.match(r.stderr, /checksum verified/);
    assert.match(r.stderr, new RegExp(`installed .*${TOOL}-${RELEASE.replace(/\./g, '\\.')}`));
    const replies = r.stdout.split('\n').filter((l) => l.startsWith('{')).map((l) => JSON.parse(l));
    const tools = replies.find((m) => m.id === 2);
    assert.ok(tools && tools.result && tools.result.tools.length > 0, r.stdout);
    assert.ok(tools.result.tools.some((t) => t.name === EXPECT_TOOL), tools.result.tools.map((t) => t.name).join(' '));
    assert.ok(fs.existsSync(path.join(home, `.${TOOL}`, 'launcher', `${TOOL}-${RELEASE}`, TOOL, 'bin')), 'unpacked under the launcher cache');
    if (TOOL === 'researchzosho') assert.ok(fs.existsSync(path.join(home, 'library', 'catalog')), 'a library was made for the client');
    // the second start finds the unpacked release: nothing is fetched again
    const before = hits.length;
    r = await runLauncher(env, lines);
    assert.strictEqual(r.status, 0, r.stderr);
    assert.strictEqual(hits.length, before, 'no second download');
    assert.match(r.stderr, /starting .*mcp/);
  } finally { server.close(); }
});

test('a tarball the SHA256SUMS does not vouch for is refused and nothing is kept', { skip }, async () => {
  const home = tmp(); const bad = tmp(); const hits = [];
  for (const f of fs.readdirSync(DIST)) if (f.endsWith('.tar.gz')) fs.copyFileSync(path.join(DIST, f), path.join(bad, f));
  fs.writeFileSync(path.join(bad, 'SHA256SUMS'), `${'0'.repeat(64)}  ${TOOL}-${RELEASE}.tar.gz\n`);
  const server = await serve(bad, hits);
  try {
    const r = await runLauncher({ ...bareEnv(home), [`${ENV}_DOWNLOAD_BASE`]: `http://127.0.0.1:${server.address().port}` }, '');
    assert.notStrictEqual(r.status, 0);
    assert.match(r.stderr, /checksum mismatch/);
    assert.strictEqual(r.stdout, '', 'nothing on stdout');
    assert.ok(!fs.existsSync(path.join(home, `.${TOOL}`, 'launcher', `${TOOL}-${RELEASE}`)), 'nothing kept');
  } finally { server.close(); }
});

/**
 * A machine without a usable Java: on Linux and macOS a `java` first on PATH that is too old (the launcher must not
 * use it); on Windows, where a process cannot stand in for java.exe, no java on PATH at all.
 */
function withoutJava(env, home) {
  if (WIN) { env[PATH_KEY] = env[PATH_KEY].split(path.delimiter).filter((d) => !/jdk|jre|java/i.test(d)).join(path.delimiter); return; }
  const dir = path.join(home, 'oldjava'); fs.mkdirSync(dir, { recursive: true });
  const p = path.join(dir, 'java'); fs.writeFileSync(p, '#!/bin/sh\necho \'java version "17.0.2" 2022-01-18\' >&2\n'); fs.chmodSync(p, 0o755);
  env[PATH_KEY] = dir + path.delimiter + env[PATH_KEY];
}

const PLATFORM = (() => { const os = { linux: 'linux', darwin: 'macos', win32: 'windows' }[process.platform]; const arch = { x64: 'x64', arm64: 'arm64' }[process.arch]; return os && arch ? `${os}-${arch}` : null; })();
const RUNTIME_ASSET = DIST && PLATFORM ? path.join(DIST, `${TOOL}-${RELEASE}-${PLATFORM}.tar.gz`) : null;
const skipRuntime = !DIST ? skip : (RUNTIME_ASSET && fs.existsSync(RUNTIME_ASSET) ? false : `no ${TOOL}-${RELEASE}-${PLATFORM}.tar.gz in LAUNCHER_TEST_DIST`);

test('without Java 21 on the machine, the build with its own runtime is fetched and runs on it', { skip: skipRuntime }, async () => {
  const home = tmp(); const hits = [];
  const server = await serve(DIST, hits);
  try {
    const env = { ...bareEnv(home), [`${ENV}_DOWNLOAD_BASE`]: `http://127.0.0.1:${server.address().port}` };
    withoutJava(env, home);
    const lines = [
      JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'launcher-test', version: '0' } } }),
      JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }),
    ].join('\n') + '\n';
    const r = await runLauncher(env, lines);
    assert.strictEqual(r.status, 0, r.stderr);
    assert.match(r.stderr, /java (17 found|not found).*carries its own runtime/);
    assert.ok(hits.some((h) => h.endsWith(`${TOOL}-${RELEASE}-${PLATFORM}.tar.gz`)), hits.join(' '));
    const jre = path.join(home, `.${TOOL}`, 'launcher', `${TOOL}-${RELEASE}`, TOOL, 'jre', 'bin', WIN ? 'java.exe' : 'java');
    assert.ok(fs.existsSync(jre), 'the runtime came with it');
    const replies = r.stdout.split('\n').filter((l) => l.startsWith('{')).map((l) => JSON.parse(l));
    const tools = replies.find((m) => m.id === 2);
    assert.ok(tools && tools.result.tools.some((t) => t.name === EXPECT_TOOL), r.stdout);
  } finally { server.close(); }
});

test('without Java and without a build for this machine, the launcher says what to install', { skip }, async () => {
  const home = tmp(); const empty = tmp();
  fs.writeFileSync(path.join(empty, 'SHA256SUMS'), '');
  const server = await serve(empty, []);
  try {
    const env = { ...bareEnv(home), [`${ENV}_DOWNLOAD_BASE`]: `http://127.0.0.1:${server.address().port}` };
    withoutJava(env, home);
    const r = await runLauncher(env, '');
    assert.notStrictEqual(r.status, 0);
    assert.match(r.stderr, /no build with its own runtime for this machine.*install Java 21/);
    assert.strictEqual(r.stdout, '');
  } finally { server.close(); }
});

