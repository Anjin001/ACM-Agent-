import { readdir, readFile, realpath, stat } from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';

const EXTENSIONS = new Set(['.cpp', '.cc', '.cxx', '.c', '.h', '.hpp', '.py', '.java', '.js', '.mjs', '.cjs', '.ts', '.tsx', '.jsx', '.vue', '.rs', '.go', '.cs', '.kt', '.sql']);
const EXCLUDED = new Set(['.git', '.svn', '.hg', 'node_modules', 'vendor', 'target', 'dist', 'build', 'coverage', '__pycache__', '.venv', 'venv', '.idea', '.vscode', '.next', '.nuxt', 'data', '.test-m2']);
export const MAX_FILE_BYTES = 96 * 1024;
const MAX_FILES = 2000;
const MAX_TOTAL_BYTES = 10 * 1024 * 1024;

export function inside(root, file) {
  const relative = path.relative(root, file);
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}

export function sensitive(name) {
  return /(^|[._-])(secret|secrets|credential|credentials|token|tokens|password|passwords)([._-]|$)/i.test(name)
    || /^\.env/i.test(name) || /^(id_rsa|id_ed25519)/i.test(name)
    || /\.(min\.(js|css)|pem|key|p12|pfx|db|sqlite|sqlite3)$/i.test(name);
}

export async function validateRoot(input, dataDir) {
  if (typeof input !== 'string' || !input.trim()) throw new Error('请输入代码目录路径');
  const root = await realpath(path.resolve(input));
  if (!(await stat(root)).isDirectory()) throw new Error('所选路径不是文件夹');
  if (inside(dataDir, root)) throw new Error('不能将工具的数据目录作为源码目录');
  return root;
}

// Do not follow links or junctions. Only recognized source files inside the selected root are read.
export async function scanDirectory(root, dataDir) {
  const files = [];
  const skipped = [];
  let total = 0;
  let visited = 0;
  let truncated = false;
  async function walk(directory) {
    if (++visited > 10000) { truncated = true; return; }
    const entries = (await readdir(directory, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name));
    for (const entry of entries) {
      const full = path.join(directory, entry.name);
      const relative = path.relative(root, full).split(path.sep).join('/');
      if (entry.isSymbolicLink() || sensitive(entry.name)) continue;
      const canonical = await realpath(full);
      if (!inside(root, canonical) || inside(dataDir, canonical)) continue;
      if (entry.isDirectory()) {
        if (!EXCLUDED.has(entry.name) && !entry.name.startsWith('.')) await walk(full);
      } else if (entry.isFile() && EXTENSIONS.has(path.extname(entry.name).toLowerCase())) {
        if (files.length >= MAX_FILES) { truncated = true; return; }
        const metadata = await stat(full);
        if (metadata.size > MAX_FILE_BYTES) { skipped.push(`${relative}：超过 96 KB`); continue; }
        if (total + metadata.size > MAX_TOTAL_BYTES) { truncated = true; return; }
        const buffer = await readFile(full);
        if (buffer.length > MAX_FILE_BYTES || buffer.includes(0)) { skipped.push(`${relative}：过大或非文本`); continue; }
        const content = buffer.toString('utf8').replace(/\r\n/g, '\n');
        const hash = createHash('sha256').update(content).digest('hex');
        files.push({ path: relative, hash, content, bytes: buffer.length, lines: content.split('\n').length });
        total += buffer.length;
      }
    }
  }
  await walk(root);
  if (truncated) throw new Error('目录超过 2000 个源码文件、10 MB 内容或 10000 个子目录，请选择更小的训练目录');
  return { files, skipped };
}
