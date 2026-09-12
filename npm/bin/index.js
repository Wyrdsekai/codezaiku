#!/usr/bin/env node
'use strict';
/*
 * @wyrdsekai/codezaiku-mcp — starts CodeZaiku's MCP server (`codezaiku mcp`) over stdio, so any MCP client
 * can run it with `npx @wyrdsekai/codezaiku-mcp`.
 *
 * There is no Java in this package. It finds an installed CodeZaiku and starts it; failing that it fetches the
 * release this version names from GitHub, checks it against the release's own SHA256SUMS (no sums, no install),
 * unpacks it under ~/.codezaiku/launcher, and starts it. With Java 21 or newer on the machine that is the small
 * tarball; without it, the build for this platform that carries its own runtime (linux, macOS and Windows, x64 and
 * arm64 where the release has them). A release with no such build prints the install one-liner and exits 1.
 *
 * stdout is the MCP stream and nothing else is ever written to it. Every message here goes to stderr.
 *
 *   CODEZAIKU_PREFIX          where the one-line installer put the program (default ~/.local, or %LOCALAPPDATA%\Programs)
 *   CODEZAIKU_DOWNLOAD_BASE   test hook: fetch the tarball and SHA256SUMS from here instead of GitHub
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { spawn, spawnSync } = require('child_process');
const { pipeline } = require('stream/promises');
const { Readable } = require('stream');

const TOOL = 'codezaiku';
const TITLE = 'CodeZaiku';
const ENV = 'CODEZAIKU';
const REPO = 'Wyrdsekai/codezaiku';
const SITE = 'https://codezaiku.org';
const VERSION = require('../package.json').version;
/** The release this launcher starts: the package version without an npm-only suffix (0.1.6-1 is the launcher, 0.1.6 the release). */
const RELEASE = VERSION.replace(/-.*$/, '');
const WIN = process.platform === 'win32';
const HOME = os.homedir();

function say(message) { process.stderr.write(`${TOOL}-mcp: ${message}\n`); }

/** The program's start script inside an unpacked release folder. */
function startScript(dir) { return path.join(dir, 'bin', WIN ? `${TOOL}.bat` : TOOL); }
function runnable(p) { try { return p && fs.statSync(p).isFile(); } catch (e) { return false; } }

/** An installed CodeZaiku: the installer's prefix, PATH, the known roots, then this launcher's own cache. */
function installed() {
  const found = [];
  const prefix = process.env[`${ENV}_PREFIX`];
  if (prefix) {
    if (WIN) found.push(startScript(path.join(prefix, TOOL)));
    else found.push(path.join(prefix, 'bin', TOOL), startScript(path.join(prefix, 'share', TOOL)));
  }
  const names = WIN ? [`${TOOL}.bat`, `${TOOL}.cmd`, `${TOOL}.exe`] : [TOOL];
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    if (!dir) continue;
    for (const n of names) found.push(path.join(dir, n));
  }
  if (WIN) {
    if (process.env.LOCALAPPDATA) found.push(startScript(path.join(process.env.LOCALAPPDATA, 'Programs', TOOL)));
  } else {
    found.push(path.join(HOME, '.local', 'bin', TOOL), path.join('/usr/local/bin', TOOL), path.join('/usr/bin', TOOL), startScript(path.join('/opt', TOOL)));
  }
  found.push(startScript(path.join(cacheRoot(), `${TOOL}-${RELEASE}`, TOOL)));
  return found.find(runnable) || null;
}

function cacheRoot() { return path.join(HOME, `.${TOOL}`, 'launcher'); }

/**
 * The version an installed program is, read off the jar beside its start script — or, through the installer's
 * wrapper (`exec "<root>/bin/<tool>" "$@"`), the root it names; null when it cannot be told without starting it.
 */
function installedVersion(script) {
  let root = path.resolve(path.dirname(script), '..');
  if (!fs.existsSync(path.join(root, 'lib'))) {
    try {
      const m = /exec "([^"]+)\/bin\/[^"/]+"/.exec(fs.readFileSync(script, 'utf8'));
      if (m) root = m[1];
    } catch (e) { return null; }
  }
  try {
    const re = new RegExp(`^(?:${TOOL}|librarian|core)-(\\d+\\.\\d+\\.\\d+)\\.jar$`);
    for (const f of fs.readdirSync(path.join(root, 'lib'))) { const m = re.exec(f); if (m) return m[1]; }
  } catch (e) { /* not a layout we know */ }
  return null;
}

/** The major version of the java on PATH, or 0. */
function javaMajor() {
  const r = spawnSync('java', ['-version'], { encoding: 'utf8', windowsHide: true });
  if (r.error || r.status !== 0) return 0;
  const m = /version "(\d+)/.exec(`${r.stderr}${r.stdout}`);
  return m ? parseInt(m[1], 10) : 0;
}

/** Fetch to a file, streamed: a 60 MB runtime build never sits in memory whole. */
async function fetchTo(url, file) {
  const r = await fetch(url, { redirect: 'follow' });
  if (!r.ok || !r.body) throw new Error(`HTTP ${r.status} for ${url}`);
  await pipeline(Readable.fromWeb(r.body), fs.createWriteStream(file));
}

/** The sha256 SHA256SUMS lists for a bare file name, or null. */
function expectedSum(sums, name) {
  for (const line of sums.split(/\r?\n/)) {
    const m = /^([0-9a-f]{64})\s+\*?(?:\.\/)?(.+?)\s*$/.exec(line);
    if (m && m[2] === name) return m[1];
  }
  return null;
}

/** The release's name for a build with its own runtime on this machine, or null where there is none. */
function platformBuild() {
  const os = { linux: 'linux', darwin: 'macos', win32: 'windows' }[process.platform];
  const arch = { x64: 'x64', arm64: 'arm64' }[process.arch];
  if (!os || !arch || (os === 'windows' && arch !== 'x64')) return null;
  return `${os}-${arch}`;
}

/** Fetch, check and unpack the release this launcher names; returns the start script. */
async function download() {
  const major = javaMajor();
  const noJava = major ? `java ${major} found; ${TITLE} needs Java 21 or newer.` : `java not found; ${TITLE} needs Java 21 or newer on PATH (https://adoptium.net).`;
  const base = process.env[`${ENV}_DOWNLOAD_BASE`] || `https://github.com/${REPO}/releases/download/v${RELEASE}`;
  let tar = `${TOOL}-${RELEASE}.tar.gz`;
  if (major < 21) {
    const build = platformBuild();
    if (!build) { say(noJava); say(`Install ${TITLE} itself and this launcher will start it:  ${SITE}`); process.exit(1); }
    tar = `${TOOL}-${RELEASE}-${build}.tar.gz`;
    say(`${noJava} Fetching the ${build} build that carries its own runtime.`);
  }
  const root = cacheRoot();
  const dest = path.join(root, `${TOOL}-${RELEASE}`);
  const part = path.join(root, `.part-${process.pid}`);
  fs.mkdirSync(part, { recursive: true });
  try {
    say(`fetching ${tar} from ${base}`);
    try { await fetchTo(`${base}/${tar}`, path.join(part, tar)); }
    catch (e) {
      if (major < 21 && /HTTP 404/.test(e.message)) throw new Error(`this release has no build with its own runtime for this machine (${e.message}); install Java 21 or newer (https://adoptium.net)`);
      throw e;
    }
    let sums;
    try { await fetchTo(`${base}/SHA256SUMS`, path.join(part, 'SHA256SUMS')); sums = fs.readFileSync(path.join(part, 'SHA256SUMS'), 'utf8'); }
    catch (e) { throw new Error(`no SHA256SUMS in the release (${e.message}). Refusing to install something unchecked`); }
    const want = expectedSum(sums, tar);
    if (!want) throw new Error(`${tar} is not listed in SHA256SUMS`);
    const got = crypto.createHash('sha256').update(fs.readFileSync(path.join(part, tar))).digest('hex');
    if (got !== want) throw new Error(`checksum mismatch for ${tar}. Refusing to install\n  expected ${want}\n  got      ${got}`);
    say('checksum verified');
    const x = spawnSync('tar', ['xzf', path.join(part, tar), '-C', part], { encoding: 'utf8', windowsHide: true });
    if (x.error || x.status !== 0) throw new Error(`could not unpack ${tar}: ${x.error ? x.error.message : x.stderr}`);
    fs.rmSync(path.join(part, tar), { force: true });
    fs.rmSync(dest, { recursive: true, force: true });
    fs.renameSync(part, dest);
    if (!WIN) fs.chmodSync(startScript(path.join(dest, TOOL)), 0o755);
  } catch (e) {
    fs.rmSync(part, { recursive: true, force: true });
    say(e.message);
    say(`Or install ${TITLE} itself:  ${SITE}`);
    process.exit(1);
  }
  say(`installed ${dest}`);
  return startScript(path.join(dest, TOOL));
}

function run(script, args) {
  const child = WIN
    ? spawn('cmd.exe', ['/d', '/s', '/c', `""${script}" ${args.join(' ')}"`], { stdio: 'inherit', windowsHide: true, windowsVerbatimArguments: true })   // /s strips the outer pair of quotes
    : spawn(script, args, { stdio: 'inherit' });
  for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    try { process.on(sig, () => { try { child.kill(sig); } catch (e) { /* already gone */ } }); } catch (e) { /* not on this platform */ }
  }
  child.on('error', (e) => { say(`could not start ${script}: ${e.message}`); process.exit(1); });
  child.on('exit', (code, signal) => process.exit(code == null ? (signal ? 1 : 0) : code));
}

(async () => {
  const args = process.argv.slice(2);
  if (args[0] === '--help' || args[0] === '-h') {
    process.stderr.write(`usage: npx @wyrdsekai/${TOOL}-mcp\nStarts \`${TOOL} mcp\` (the MCP server over stdio), installing ${TITLE} ${RELEASE} first when it is not on this machine.\n`);
    process.exit(0);
  }
  if (args[0] === '--version') { process.stderr.write(`${TOOL}-mcp ${VERSION}\n`); process.exit(0); }
  let script = installed();
  if (script) {
    const v = installedVersion(script);
    say(`starting ${script} mcp${v ? ` (${TITLE} ${v})` : ''}`);
    // the registry lists this launcher as VERSION; an older install on the machine is what actually answers
    if (v && v !== RELEASE) say(`note: the installed ${TITLE} is ${v}, this launcher starts ${RELEASE}; \`${TOOL} update now\` brings the install up to date`);
  } else script = await download();
  run(script, ['mcp', ...args]);
})().catch((e) => { say(e.message); process.exit(1); });
