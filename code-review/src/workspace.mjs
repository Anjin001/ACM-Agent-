import { DatabaseSync } from 'node:sqlite';
import { mkdir, readFile, writeFile, realpath } from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { scanDirectory, validateRoot } from './scanner.mjs';
import { complete, parseReview, validateApi, REVIEW_PROMPT, CHAT_PROMPT } from './model.mjs';

export class Workspace {
  constructor(dataDir, options = {}) {
    this.dataDir = path.resolve(dataDir);
    this.callModel = options.callModel || complete;
    this.pollMs = options.pollMs || 3000;
    this.pending = new Map();
    this.scanError = '';
    this.skipped = [];
    this.lastScan = null;
    this.scanning = false;
    this.processing = false;
    this.chatting = false;
    this.closed = false;
    this.modelError = '';
    this.abort = new AbortController();
  }
  async init({ watch = true } = {}) {
    await mkdir(this.dataDir, { recursive: true });
    this.dataDir = await realpath(this.dataDir);
    this.configPath = path.join(this.dataDir, 'settings.json');
    try { this.config = JSON.parse(await readFile(this.configPath, 'utf8')); }
    catch (error) { if (error.code !== 'ENOENT') throw error; this.config = { baseUrl: '', model: '', apiKey: '', root: '', autoReview: false }; }
    this.db = new DatabaseSync(path.join(this.dataDir, 'memory.sqlite'));
    this.db.exec(`PRAGMA journal_mode=WAL;
      CREATE TABLE IF NOT EXISTS files (project TEXT, path TEXT, hash TEXT, lines INTEGER, bytes INTEGER, active INTEGER, updated_at TEXT, PRIMARY KEY(project,path));
      CREATE TABLE IF NOT EXISTS reviews (id INTEGER PRIMARY KEY, project TEXT, path TEXT, hash TEXT, status TEXT, report TEXT, error TEXT, created_at TEXT, updated_at TEXT, UNIQUE(project,path,hash));
      CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY, project TEXT, role TEXT, content TEXT, sources TEXT, created_at TEXT);
      UPDATE reviews SET status='queued', error='' WHERE status='running';`);
    const failure = this.db.prepare("SELECT error FROM reviews WHERE project=? AND status='failed' ORDER BY id DESC LIMIT 1").get(this.project());
    this.modelError = failure?.error || '';
    if (watch) this.timer = setInterval(() => { if (this.config.autoReview && this.config.root) void this.scan().catch(() => {}); }, this.pollMs);
    return this;
  }
  project(root = this.config.root) { return createHash('sha256').update(root).digest('hex').slice(0, 24); }
  configured() { return Boolean(this.config.baseUrl && this.config.model); }
  status() {
    const project = this.project();
    const files = this.db.prepare('SELECT path,hash,lines,bytes,updated_at FROM files WHERE project=? AND active=1 ORDER BY path').all(project);
    const reviews = this.db.prepare('SELECT * FROM reviews WHERE project=? ORDER BY id DESC LIMIT 100').all(project);
    const counts = this.db.prepare(`SELECT COUNT(*) AS total, SUM(CASE WHEN r.status='done' AND f.active=1 AND r.hash=f.hash THEN 1 ELSE 0 END) AS currentDone FROM reviews r LEFT JOIN files f ON f.project=r.project AND f.path=r.path WHERE r.project=?`).get(project);
    return { settings: { baseUrl: this.config.baseUrl, model: this.config.model, root: this.config.root, autoReview: this.config.autoReview, hasApiKey: Boolean(this.config.apiKey) },
      files, counts, reviews: reviews.map(({ project: _, report, ...r }) => ({ ...r, current: files.some(f => f.path === r.path && f.hash === r.hash), report: report ? JSON.parse(report) : null })),
      messages: this.db.prepare('SELECT role,content,sources,created_at FROM messages WHERE project=? ORDER BY id DESC LIMIT 60').all(project).reverse().map(m => ({ ...m, sources: JSON.parse(m.sources || '[]') })),
      scanning: this.scanning, processing: this.processing, chatting: this.chatting, pending: this.pending.size, scanError: this.scanError, modelError: this.modelError, skipped: this.skipped, lastScan: this.lastScan, configured: this.configured() };
  }
  async saveSettings(input) {
    const api = validateApi(input);
    const root = await validateRoot(input.root, this.dataDir);
    if (this.processing || this.scanning || this.chatting) throw new Error('正在处理任务，请等待完成后再修改设置');
    const apiKey = input.clearApiKey ? '' : (String(input.apiKey || '').trim() || this.config.apiKey);
    if (apiKey.length > 2000) throw new Error('API Key 过长');
    this.config = { ...api, root, apiKey, autoReview: input.autoReview === true };
    await writeFile(this.configPath, JSON.stringify(this.config, null, 2), { mode: 0o600 });
    this.pending.clear();
    this.db.prepare("UPDATE reviews SET status='queued',error='' WHERE project=? AND status='failed'").run(this.project());
    // Explicit setup starts the first scan, even when continuous monitoring is disabled.
    await this.scan(true);
    return this.status();
  }
  async scan(force = false) {
    if (this.scanning) throw new Error('正在扫描，请稍候');
    if (!this.config.root) throw new Error('请先设置代码目录和 API');
    this.scanning = true;
    const project = this.project();
    try {
      const root = await validateRoot(this.config.root, this.dataDir);
      const { files, skipped } = await scanDirectory(root, this.dataDir);
      const snapshots = path.join(this.dataDir, 'sandboxes', project, 'snapshots');
      await mkdir(snapshots, { recursive: true });
      const updates = [];
      const present = new Set(files.map(f => f.path));
      for (const file of files) {
        const previous = this.db.prepare('SELECT hash FROM files WHERE project=? AND path=? AND active=1').get(project, file.path);
        if (previous?.hash === file.hash) { this.pending.delete(file.path); continue; }
        if (!force && this.pending.get(file.path) !== file.hash) { this.pending.set(file.path, file.hash); continue; }
        await writeFile(path.join(snapshots, `${file.hash}.txt`), file.content, { mode: 0o600 });
        updates.push(file);
        this.pending.delete(file.path);
      }
      const now = new Date().toISOString();
      this.db.exec('BEGIN');
      try {
        for (const file of updates) {
          this.db.prepare(`INSERT INTO files VALUES(?,?,?,?,?,1,?) ON CONFLICT(project,path) DO UPDATE SET hash=excluded.hash,lines=excluded.lines,bytes=excluded.bytes,active=1,updated_at=excluded.updated_at`).run(project, file.path, file.hash, file.lines, file.bytes, now);
          this.db.prepare(`INSERT OR IGNORE INTO reviews(project,path,hash,status,error,created_at,updated_at) VALUES(?,?,?,'queued','',?,?)`).run(project, file.path, file.hash, now, now);
        }
        for (const file of this.db.prepare('SELECT path FROM files WHERE project=? AND active=1').all(project)) {
          if (!present.has(file.path)) this.db.prepare('UPDATE files SET active=0 WHERE project=? AND path=?').run(project, file.path);
        }
        for (const key of this.pending.keys()) if (!present.has(key)) this.pending.delete(key);
        if (force) this.db.prepare("UPDATE reviews SET status='queued',error='' WHERE project=? AND status='failed'").run(project);
        this.db.exec('COMMIT');
      } catch (error) { this.db.exec('ROLLBACK'); throw error; }
      this.lastScan = now; this.skipped = skipped; this.scanError = '';
      if (force) this.modelError = '';
    } catch (error) { this.scanError = error.message; throw error; }
    finally { this.scanning = false; }
    void this.drain();
    return { indexed: this.status().files.length };
  }
  async content(project, hash) {
    if (!/^[a-f0-9]{24}$/.test(project) || !/^[a-f0-9]{64}$/.test(hash)) throw new Error('无效快照');
    return readFile(path.join(this.dataDir, 'sandboxes', project, 'snapshots', `${hash}.txt`), 'utf8');
  }
  async drain() {
    if (this.processing || !this.configured() || this.closed || this.modelError) return;
    this.processing = true;
    const config = { ...this.config };
    const project = this.project(config.root);
    try {
      while (!this.closed) {
        const job = this.db.prepare(`SELECT r.*,f.lines FROM reviews r JOIN files f ON f.project=r.project AND f.path=r.path AND f.hash=r.hash AND f.active=1 WHERE r.project=? AND r.status='queued' ORDER BY r.id LIMIT 1`).get(project);
        if (!job) break;
        this.db.prepare("UPDATE reviews SET status='running' WHERE id=?").run(job.id);
        try {
          const content = await this.content(project, job.hash);
          const memory = this.db.prepare("SELECT path,report FROM reviews WHERE project=? AND path=? AND status='done' ORDER BY id DESC LIMIT 2").all(project, job.path).map(r => ({ path: r.path, report: JSON.parse(r.report) }));
          const text = await this.callModel(config, [{ role: 'system', content: REVIEW_PROMPT }, { role: 'user', content: JSON.stringify({ file: job.path, lines: job.lines, content, previousReviews: memory }) }], { signal: this.abort.signal });
          const report = parseReview(text, job.lines);
          this.db.prepare("UPDATE reviews SET status='done',report=?,error='',updated_at=? WHERE id=?").run(JSON.stringify(report), new Date().toISOString(), job.id);
        } catch (error) {
          this.modelError = this.safeError(error);
          this.db.prepare("UPDATE reviews SET status='failed',error=?,updated_at=? WHERE id=?").run(this.safeError(error), new Date().toISOString(), job.id);
          // Fail once per scan/run; avoid spending quota on every file after a service/configuration failure.
          break;
        }
      }
    } finally { this.processing = false; }
  }
  safeError(error) {
    let message = error?.message || '调用失败';
    if (this.config.apiKey) message = message.split(this.config.apiKey).join('[密钥已隐藏]');
    return message.slice(0, 400);
  }
  async retrieve(question, history = []) {
    const project = this.project();
      const query = `${history.slice(-2).map(m => m.content).join(' ')} ${question}`.toLowerCase();
      const terms = query.match(/[a-z0-9_./-]{2,}|[\u4e00-\u9fff]{2,}/g) || [];
      for (const term of [...terms]) if (/^[\u4e00-\u9fff]+$/.test(term)) for (let i = 0; i < term.length - 1; i++) terms.push(term.slice(i, i + 2));
      const tokens = [...new Set(terms)].slice(-120);
      const files = this.db.prepare(`SELECT f.*,r.report FROM files f LEFT JOIN reviews r ON r.project=f.project AND r.path=f.path AND r.hash=f.hash AND r.status='done' WHERE f.project=? AND f.active=1 ORDER BY f.updated_at DESC`).all(project);
      const ranked = await Promise.all(files.map(async file => {
        const content = await this.content(project, file.hash);
        const haystack = `${file.path}\n${file.report || ''}\n${content}`.toLowerCase();
        const score = tokens.reduce((sum, token) => sum + (file.path.toLowerCase().includes(token) ? 5 : haystack.includes(token) ? 1 : 0), 0);
        return { ...file, content, score };
      }));
      ranked.sort((a, b) => b.score - a.score || b.updated_at.localeCompare(a.updated_at));
      let budget = 32000;
      const sources = [];
      for (const file of ranked.slice(0, 6)) {
        const lines = file.content.split('\n');
        let start = 0;
        if (file.content.length > 6000 && tokens.length) {
          const hit = lines.findIndex(line => tokens.some(token => line.toLowerCase().includes(token)));
          if (hit >= 0) start = Math.max(0, hit - 15);
        }
        let snippet = ''; let end = start;
        while (end < lines.length && snippet.length + lines[end].length + 20 <= Math.min(6000, budget)) { snippet += `${end + 1}: ${lines[end]}\n`; end++; }
        if (!snippet) continue;
        budget -= snippet.length;
        sources.push({ path: file.path, hash: file.hash, startLine: start + 1, endLine: end, partial: start > 0 || end < lines.length, content: snippet, review: file.report ? JSON.parse(file.report) : null });
      }
    return { files, sources };
  }
  async chat(question) {
    if (!this.configured()) throw new Error('请先配置 API');
    if (typeof question !== 'string' || !question.trim() || question.length > 6000) throw new Error('消息需要 1～6000 个字符');
    if (this.chatting) throw new Error('上一条消息还在处理中');
    this.chatting = true;
    try {
      const project = this.project();
      const history = this.db.prepare('SELECT role,content FROM messages WHERE project=? ORDER BY id DESC LIMIT 8').all(project).reverse();
      const { files, sources } = await this.retrieve(question, history);
      const answer = await this.callModel(this.config, [{ role: 'system', content: CHAT_PROMPT }, ...history, { role: 'user', content: JSON.stringify({ question, indexedFiles: files.map(f => f.path), selectedSources: sources, note: '仅提供最多六个相关文件的局部或完整片段；未提供的代码不能声称已读。' }) }], { signal: this.abort.signal });
      const refs = sources.map(({ content, review, ...ref }) => ref);
      const now = new Date().toISOString();
      this.db.exec('BEGIN');
      try {
        this.db.prepare('INSERT INTO messages(project,role,content,sources,created_at) VALUES(?,?,?,?,?)').run(project, 'user', question, '[]', now);
        this.db.prepare('INSERT INTO messages(project,role,content,sources,created_at) VALUES(?,?,?,?,?)').run(project, 'assistant', answer, JSON.stringify(refs), now);
        this.db.exec('COMMIT');
      } catch (error) { this.db.exec('ROLLBACK'); throw error; }
      return { answer, sources: refs };
    } finally { this.chatting = false; }
  }
  async close() {
    this.closed = true; clearInterval(this.timer); this.abort.abort();
    while (this.processing || this.chatting || this.scanning) await new Promise(resolve => setTimeout(resolve, 10));
    this.db.close();
  }
}
