import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { Workspace } from '../src/workspace.mjs';
import { validateRoot, scanDirectory } from '../src/scanner.mjs';

const appDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const input = process.argv[2];
if (!input) throw new Error('用法：node scripts/local-check.mjs <代码目录>');
const data = path.join(appDir, 'data', 'local-validation');
let modelCalls = 0;
const workspace = await new Workspace(data, { callModel: async () => { modelCalls++; throw new Error('本地验收禁止调用模型'); } }).init({ watch: false });
try {
  workspace.config.root = await validateRoot(input, workspace.dataDir);
  const started = performance.now();
  await workspace.scan(true);
  const initial = workspace.status();
  const indexedMs = Math.round(performance.now() - started);
  await workspace.scan(true);
  assert.equal(workspace.status().counts.total, initial.counts.total, '重复扫描不应增加版本记录');
  const queries = ['二分 lower_bound', '线段树 segment', '最短路 dijkstra', '动态规划 dp'];
  const retrieval = [];
  for (const query of queries) {
    const result = await workspace.retrieve(query);
    for (const ref of result.sources) {
      const source = await workspace.content(workspace.project(), ref.hash);
      const lines = source.split('\n');
      assert.ok(ref.startLine >= 1 && ref.endLine <= lines.length);
      assert.ok(ref.content.startsWith(`${ref.startLine}: ${lines[ref.startLine - 1]}`));
    }
    retrieval.push({ query, sources: result.sources.map(({ content, review, ...ref }) => ref) });
  }
  const verified = await scanDirectory(workspace.config.root, workspace.dataDir);
  const sorted = files => files.map(f => [f.path, f.hash]).sort((a,b) => a[0].localeCompare(b[0]));
  assert.deepEqual(sorted(verified.files), sorted(initial.files), '原始源码在测试后应保持不变');
  assert.equal(modelCalls, 0);
  const output = { root: workspace.config.root, checkedAt: new Date().toISOString(), files: initial.files.length, bytes: initial.files.reduce((n,f) => n + f.bytes,0), lines: initial.files.reduce((n,f) => n + f.lines,0), indexedMs, skipped: workspace.skipped, modelCalls, duplicateScan: 'passed', retrieval };
  await writeFile(path.join(data, 'result.json'), JSON.stringify(output, null, 2));
  const markdown = ['# 本地真实代码验收', '', `- 源码目录：${output.root}`, `- 时间：${output.checkedAt}`, `- 索引：${output.files} 个文件，${output.lines} 行，${output.bytes} 字节`, `- 首次索引：${output.indexedMs} ms`, '- 重复扫描：未增加重复记录', '- 快照行号及内容对应：通过', `- 模型/API 调用：${modelCalls}`, '- 本次只读取原始目录，快照写入工具自己的 data/local-validation/', '', '## 检索结果', ...retrieval.flatMap(r => ['', `### ${r.query}`, ...r.sources.map(ref => `- ${ref.path}:${ref.startLine}–${ref.endLine}${ref.partial ? '（局部片段）' : ''}`)]), '', '说明：此次验证的是实际源码的扫描、快照、去重与检索，不代表所有文件已完成语义审查。没有使用模拟回答冒充真实审查。'].join('\n');
  await writeFile(path.join(data, 'report.md'), markdown);
  console.log(JSON.stringify(output, null, 2));
} finally { await workspace.close(); }
