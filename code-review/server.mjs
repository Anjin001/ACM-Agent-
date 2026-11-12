import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Workspace } from './src/workspace.mjs';
import { complete, validateApi } from './src/model.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
export async function startServer({ port = Number(process.env.PORT || 18081), dataDir = path.join(here, 'data'), callModel, watch = true } = {}) {
  const workspace = await new Workspace(dataDir, { callModel }).init({ watch });
  const publicFiles = { '/': ['index.html', 'text/html; charset=utf-8'], '/app.js': ['app.js', 'text/javascript; charset=utf-8'], '/style.css': ['style.css', 'text/css; charset=utf-8'] };
  const server = http.createServer(async (req, res) => {
    const address = server.address();
    const hosts = [`127.0.0.1:${address.port}`, `localhost:${address.port}`];
    const send = (status, data) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(data)); };
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    if (!hosts.includes(req.headers.host) || (req.headers.origin && req.headers.origin !== `http://${req.headers.host}`)) return send(403, { error: '只允许本机工作台访问' });
    try {
      const url = new URL(req.url, `http://${req.headers.host}`);
      if (req.method === 'GET' && publicFiles[url.pathname]) {
        const [file, type] = publicFiles[url.pathname];
        res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' });
        return res.end(await readFile(path.join(here, 'public', file)));
      }
      if (req.method === 'GET' && url.pathname === '/api/state') return send(200, workspace.status());
      if (req.method === 'GET' && url.pathname === '/api/source') {
        const sourcePath = url.searchParams.get('path');
        const hash = url.searchParams.get('hash');
        const file = workspace.db.prepare('SELECT hash FROM files WHERE project=? AND path=? AND active=1').get(workspace.project(), sourcePath);
        const review = workspace.db.prepare('SELECT hash FROM reviews WHERE project=? AND path=? AND hash=?').get(workspace.project(), sourcePath, hash);
        if ((!hash && !file) || (hash && !review)) return send(404, { error: '快照不存在' });
        return send(200, { path: sourcePath, content: await workspace.content(workspace.project(), hash || file.hash) });
      }
      if (req.method !== 'POST' || !req.headers['content-type']?.startsWith('application/json')) return send(404, { error: '接口不存在' });
      const chunks = []; let bytes = 0;
      for await (const chunk of req) { bytes += chunk.length; if (bytes > 32000) { send(413, { error: '请求过大' }); req.resume(); return; } chunks.push(chunk); }
      const raw = Buffer.concat(chunks).toString('utf8');
      let body;
      try { body = JSON.parse(raw); } catch { return send(400, { error: '请求 JSON 格式错误' }); }
      if (!body || typeof body !== 'object' || Array.isArray(body)) return send(400, { error: '请求需要 JSON 对象' });
      if (url.pathname === '/api/settings') return send(200, await workspace.saveSettings(body));
      if (url.pathname === '/api/scan') return send(200, await workspace.scan(true));
      if (url.pathname === '/api/chat') return send(200, await workspace.chat(body.message));
      if (url.pathname === '/api/test') {
        const config = { ...validateApi(body), apiKey: body.clearApiKey ? '' : String(body.apiKey || workspace.config.apiKey || '').trim() };
        await (callModel || complete)(config, [{ role: 'user', content: '请回复 OK，用于测试连接。' }]);
        return send(200, { ok: true });
      }
      send(404, { error: '接口不存在' });
    } catch (error) { send(400, { error: workspace.safeError(error) }); }
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  return { server, workspace, url: `http://127.0.0.1:${server.address().port}`, async close() { server.close(); await workspace.close(); server.closeAllConnections(); } };
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  startServer().then(app => {
    console.log(`\n循练 · 代码记忆工作台\n打开 ${app.url}\n数据目录：${app.workspace.dataDir}\n按 Ctrl+C 停止。\n`);
    for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { void app.close().then(() => process.exit(0)); });
  }).catch(error => { console.error(`启动失败：${error.message}`); process.exitCode = 1; });
}
