import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, symlink } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import { Workspace } from '../src/workspace.mjs';
import { scanDirectory, validateRoot, inside, MAX_FILE_BYTES } from '../src/scanner.mjs';
import { complete, parseReview } from '../src/model.mjs';
import { startServer } from '../server.mjs';

async function setup(t) {
  const temp = await mkdtemp(path.join(os.tmpdir(), 'code-memory-test-'));
  t.cleanups = [];
  t.after(async () => { for (const close of t.cleanups) await close(); await rm(temp, { recursive: true, force: true }); });
  const root = path.join(temp, 'solutions'); const data = path.join(temp, 'data');
  await mkdir(root); await mkdir(data);
  return { temp, root, data };
}
const report = JSON.stringify({ summary: '二分实现，注意整数边界', topics: ['二分'], issues: [{ line: 1, severity: 'medium', message: '整数求和可能溢出，需要结合输入范围确认', suggestion: '检查类型和端点范围' }] });
async function idle(workspace) {
  const start = Date.now();
  while (workspace.processing || workspace.scanning) {
    if (Date.now() - start > 5000) throw new Error('任务没有结束');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}
function config(root) { return { root, baseUrl: 'http://localhost:12345/v1', apiKey: 'test-secret', model: 'test-model', autoReview: true }; }

test('scanner respects root, excludes dependency/secret/data files, and reports oversized sources', async t => {
  const { root, data, temp } = await setup(t);
  await writeFile(path.join(root, 'main.cpp'), 'int main(){}');
  await mkdir(path.join(root, 'node_modules')); await writeFile(path.join(root, 'node_modules', 'bad.js'), 'doNotSend');
  await writeFile(path.join(root, 'credentials.py'), 'secret');
  await writeFile(path.join(root, 'big.cpp'), 'x'.repeat(MAX_FILE_BYTES + 1));
  await writeFile(path.join(temp, 'outside.cpp'), 'outside');
  try { await symlink(path.join(temp, 'outside.cpp'), path.join(root, 'link.cpp')); } catch (error) { if (error.code !== 'EPERM') throw error; }
  const scan = await scanDirectory(root, data);
  assert.deepEqual(scan.files.map(f => f.path), ['main.cpp']);
  assert.equal(scan.skipped.length, 1);
  assert.equal(inside(root, path.join(temp, 'solutions-other', 'x')), false);
  assert.equal(await validateRoot(path.relative(process.cwd(), root), data), await validateRoot(root, data));
  await assert.rejects(validateRoot(data, data), /数据目录/);
});

test('realistic collections larger than 500 source files remain indexable', async t => {
  const { root, data } = await setup(t);
  await Promise.all(Array.from({ length: 510 }, (_, i) => writeFile(path.join(root, `${i}.cpp`), 'int value;')));
  await writeFile(path.join(root, 'ignored.exe'), 'not source');
  assert.equal((await scanDirectory(root, data)).files.length, 510);
});

test('index, review memory, persisted chat sources, content deduplication, edits and deletion form a complete flow', async t => {
  const { root, data } = await setup(t);
  const file = path.join(root, 'binary.cpp'); await writeFile(file, 'int mid = (l + r) / 2;');
  const calls = [];
  const workspace = await new Workspace(data, { callModel: async (settings, messages) => {
    calls.push(messages);
    return messages[0].content.includes('只输出 JSON') ? report : '你实现了二分查找 [binary.cpp:1]。';
  } }).init({ watch: false });
  t.cleanups.push(() => workspace.close());
  await workspace.saveSettings(config(root)); await idle(workspace);
  assert.equal(workspace.status().reviews[0].status, 'done');
  assert.equal(calls.length, 1);
  await workspace.scan(true); await idle(workspace); assert.equal(calls.length, 1);
  const reply = await workspace.chat('binary.cpp 的二分有什么边界问题？');
  assert.equal(reply.sources[0].path, 'binary.cpp');
  assert.ok(JSON.parse(calls[1].at(-1).content).selectedSources[0].review.summary.includes('二分'));
  assert.equal(workspace.status().messages.length, 2);
  assert.equal(JSON.stringify(workspace.status()).includes('test-secret'), false);
  await writeFile(file, 'long long mid = l + (r-l)/2;');
  await workspace.scan(); await idle(workspace); assert.equal(workspace.status().pending, 1);
  await workspace.scan(); await idle(workspace); assert.equal(workspace.status().reviews.length, 2);
  assert.equal(workspace.status().reviews.filter(r => r.current).length, 1);
  await rm(file); await workspace.scan(true); await idle(workspace);
  assert.equal(workspace.status().files.length, 0);
  assert.equal(workspace.status().reviews.every(r => !r.current), true);
});

test('memory survives restart and separate source folders have separate chats and indexes', async t => {
  const { root, data, temp } = await setup(t);
  await writeFile(path.join(root, 'a.cpp'), 'int a;');
  let workspace = await new Workspace(data, { callModel: async () => report }).init({ watch: false });
  await workspace.saveSettings(config(root)); await idle(workspace); await workspace.chat('a.cpp'); await workspace.close();
  workspace = await new Workspace(data, { callModel: async () => report }).init({ watch: false });
  t.cleanups.push(() => workspace.close());
  assert.equal(workspace.status().messages.length, 2);
  assert.equal(workspace.status().reviews.length, 1);
  const second = path.join(temp, 'second'); await mkdir(second);
  await workspace.saveSettings(config(second)); await idle(workspace);
  assert.equal(workspace.status().messages.length, 0);
  assert.equal(workspace.status().files.length, 0);
});

test('model failure pauses queue without repeated automatic API calls; manual scan retries', async t => {
  const { root, data } = await setup(t);
  await writeFile(path.join(root, 'a.cpp'), 'int a;'); await writeFile(path.join(root, 'b.cpp'), 'int b;');
  let fail = true; let calls = 0;
  const workspace = await new Workspace(data, { callModel: async () => { calls++; if (fail) throw new Error('HTTP 401 test-secret'); return report; } }).init({ watch: false });
  t.cleanups.push(() => workspace.close());
  await workspace.saveSettings(config(root)); await idle(workspace);
  assert.equal(calls, 1); assert.equal(workspace.status().modelError.includes('test-secret'), false);
  await workspace.scan(); await idle(workspace); assert.equal(calls, 1);
  fail = false; await workspace.scan(true); await idle(workspace);
  assert.equal(workspace.status().reviews.every(r => r.status === 'done'), true);
});

test('real polling detects a saved file and automatically reviews it', async t => {
  const { root, data } = await setup(t);
  let calls = 0;
  const workspace = await new Workspace(data, { pollMs: 30, callModel: async () => { calls++; return report; } }).init();
  t.cleanups.push(() => workspace.close());
  await workspace.saveSettings(config(root)); await writeFile(path.join(root, 'saved.cpp'), 'int value;');
  const end = Date.now() + 3000;
  while (!calls && Date.now() < end) await new Promise(resolve => setTimeout(resolve, 20));
  await idle(workspace);
  assert.equal(calls, 1); assert.equal(workspace.status().reviews[0].status, 'done');
});

test('model client sends compatible requests, preserves long source content, and rejects malformed reports', async t => {
  let request;
  const answer = await complete(config('unused'), [{ role: 'user', content: 'x'.repeat(12000) }], { fetchImpl: async (url, options) => { request = { url, options }; return new Response(JSON.stringify({ choices: [{ message: { content: report } }] })); } });
  assert.ok(request.url.endsWith('/v1/chat/completions'));
  assert.equal(request.options.headers.Authorization, 'Bearer test-secret');
  assert.equal(JSON.parse(request.options.body).messages[0].content.length, 12000);
  assert.equal(parseReview(answer, 1).issues.length, 1);
  assert.throws(() => parseReview('{"summary":"x","issues":[{"line":99,"message":"x"}]}', 1), /行号/);
  assert.throws(() => parseReview('not json', 1), /JSON/);
  await assert.rejects(complete(config('unused'), [], { fetchImpl: async () => new Response('', { status: 401 }) }), /401/);
});

test('HTTP flow serves UI, saves API settings, rejects cross-origin calls, and only exposes registered snapshots', async t => {
  const { root, data } = await setup(t);
  await writeFile(path.join(root, 'main.cpp'), 'int main(){}');
  const app = await startServer({ port: 0, dataDir: data, watch: false, callModel: async () => report });
  t.cleanups.push(() => app.close());
  const post = (route, body, headers = {}) => fetch(`${app.url}/api/${route}`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
  assert.equal((await fetch(app.url)).status, 200);
  assert.equal((await post('settings', config(root))).status, 200); await idle(app.workspace);
  const state = await (await fetch(`${app.url}/api/state`)).json();
  assert.equal(state.settings.hasApiKey, true); assert.equal(state.settings.apiKey, undefined);
  assert.equal((await post('scan', {}, { Origin: 'https://untrusted.example' })).status, 403);
  assert.equal((await fetch(`${app.url}/api/source?path=../../settings.json`)).status, 404);
  assert.equal((await fetch(`${app.url}/api/source?path=main.cpp`)).status, 200);
  assert.equal((await post('chat', { message: '这份代码做什么？' })).status, 200);
  const persisted = JSON.parse(await readFile(path.join(data, 'settings.json'), 'utf8'));
  assert.equal(persisted.apiKey, 'test-secret');
});

test('user-provided API works through the real HTTP model transport for review and chat', async t => {
  const { root, data } = await setup(t);
  await writeFile(path.join(root, 'local.cpp'), 'int mid = (l + r) / 2;');
  const requests = [];
  const provider = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());
    requests.push({ url: req.url, key: req.headers.authorization, body });
    const content = body.messages[0].content.includes('只输出 JSON') ? report : '来自本地源码 [local.cpp:1]，请检查整数范围。';
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ choices: [{ message: { content } }] }));
  });
  await new Promise(resolve => provider.listen(0, '127.0.0.1', resolve));
  const workspace = await new Workspace(data).init({ watch: false });
  t.cleanups.push(async () => { await workspace.close(); provider.close(); provider.closeAllConnections(); });
  await workspace.saveSettings({ ...config(root), baseUrl: `http://127.0.0.1:${provider.address().port}/v1` });
  await idle(workspace);
  assert.equal(workspace.status().reviews[0].status, 'done');
  const reply = await workspace.chat('local.cpp 有什么问题？');
  assert.ok(reply.answer.includes('local.cpp:1'));
  assert.equal(requests.length, 2);
  assert.equal(requests[0].url, '/v1/chat/completions');
  assert.equal(requests[0].key, 'Bearer test-secret');
  assert.equal(requests[0].body.model, 'test-model');
  assert.equal(JSON.parse(requests[1].body.messages.at(-1).content).selectedSources[0].path, 'local.cpp');
});
