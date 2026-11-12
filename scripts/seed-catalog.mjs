import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { topicDefinitions, cfTagMap, curatedProblems } from './topics.mjs'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const data = process.argv[2]
  ? JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
  : await (await fetch('https://codeforces.com/api/problemset.problems')).json()
if (data.status !== 'OK') throw new Error('CF 题库获取失败')
const date = new Date().toISOString().slice(0, 10)
const problems = data.result.problems
  .filter((p) => p.contestId && p.index)
  .map((p) => ({
    id: `CF-${p.contestId}-${p.index}`,
    platform: 'CF',
    code: `${p.contestId}${p.index}`,
    title: p.name,
    url: `https://codeforces.com/problemset/problem/${p.contestId}/${p.index}`,
    tags: [...new Set(p.tags.map((tag) => cfTagMap[tag]).filter(Boolean))],
    sourceTags: p.tags,
    rating: p.rating ?? null,
    difficulty: p.rating ? `${p.rating}` : '暂无评分',
    minutes: !p.rating
      ? 45
      : p.rating <= 1000
        ? 30
        : p.rating <= 1400
          ? 40
          : p.rating <= 1800
            ? 50
            : p.rating <= 2200
              ? 60
              : p.rating <= 2600
                ? 75
                : 90,
    minLevel: 800,
    maxLevel: 4000,
    provenance: `CF 官方题库快照 ${date}`,
  }))
for (const [code, title, tags, level] of curatedProblems)
  problems.push({
    id: `LG-${code}`,
    platform: 'LG',
    code,
    title,
    tags,
    sourceTags: [],
    url: `https://www.luogu.com.cn/problem/${code}`,
    rating: null,
    difficulty: '查看原题',
    minutes: level <= 1200 ? 35 : level <= 1600 ? 45 : level <= 2000 ? 60 : 75,
    minLevel: Math.max(800, level - 300),
    maxLevel: level + 400,
    provenance: '人工精选；名称为训练主题，难度与内容以洛谷原题为准',
  })
const topics = topicDefinitions.map((topic) => ({
  ...topic,
  examples: curatedProblems.filter((p) => p[2].includes(topic.name)).map((p) => `LG-${p[0]}`),
}))
const directory = path.join(root, 'backend/src/main/resources')
fs.writeFileSync(path.join(directory, 'catalog.json'), JSON.stringify(problems, null, 2) + '\n')
fs.writeFileSync(path.join(directory, 'topics.json'), JSON.stringify(topics, null, 2) + '\n')
fs.writeFileSync(
  path.join(directory, 'catalog-info.json'),
  JSON.stringify(
    {
      snapshot: date,
      cf: problems.filter((p) => p.platform === 'CF').length,
      luogu: curatedProblems.length,
      topics: topics.length,
    },
    null,
    2,
  ) + '\n',
)
console.log(
  JSON.stringify({
    problems: problems.length,
    topics: topics.length,
    luogu: curatedProblems.length,
  }),
)
