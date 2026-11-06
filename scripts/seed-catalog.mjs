import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const data = process.argv[2]
  ? JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
  : await (await fetch('https://codeforces.com/api/problemset.problems')).json()
if (data.status !== 'OK') throw new Error('CF 题库获取失败')
const topicMap = {
  'binary search': '二分', 'two pointers': '双指针', 'dfs and similar': '搜索',
  'bfs': '搜索', 'dp': '动态规划', 'greedy': '贪心', 'graphs': '图论',
  'math': '数学', 'strings': '字符串', 'data structures': '数据结构', 'implementation': '模拟',
}
const fixed = new Set(['706B','474B','279B','492B','4A','71A','231A','158A','282A','580C','363B','230B','977E','1324D'])
const selected = new Map()
for (const topic of Object.keys(topicMap)) {
  for (const difficulty of [800, 1000, 1200, 1400]) {
    data.result.problems.filter(p => p.rating === difficulty && p.tags.includes(topic)).slice(0, 3)
      .forEach(p => selected.set(`${p.contestId}${p.index}`, p))
  }
}
data.result.problems.filter(p => fixed.has(`${p.contestId}${p.index}`)).forEach(p => selected.set(`${p.contestId}${p.index}`, p))
const problems = [...selected.values()].map(p => ({
  id: `CF-${p.contestId}-${p.index}`, platform: 'CF', code: `${p.contestId}${p.index}`,
  title: p.name, url: `https://codeforces.com/problemset/problem/${p.contestId}/${p.index}`,
  tags: [...new Set(p.tags.map(tag => topicMap[tag]).filter(Boolean))], rating: p.rating,
  difficulty: `${p.rating}`, minutes: p.rating <= 1000 ? 30 : p.rating <= 1200 ? 40 : 55,
  provenance: 'CF 公开题库快照 2026-11-06',
}))
const luogu = [
  ['P2249','【深基13.例1】查找','二分','普及−',35],
  ['P1102','A-B 数对','二分','普及−',40],
  ['P1873','[COCI 2011/2012 #5] EKO / 砍树','二分','普及−',40],
  ['P2440','木材加工','二分','普及−',40],
  ['P1162','填涂颜色','搜索','普及−',45],
  ['P1025','[NOIP 2001 提高组] 数的划分','搜索','普及/提高−',60],
  ['P1605','迷宫','搜索','普及−',45],
  ['P1451','求细胞数量','搜索','普及−',40],
  ['P1219','[USACO1.5] 八皇后 Checker Challenge','搜索','普及/提高−',60],
  ['P1048','[NOIP 2005 普及组] 采药','动态规划','普及−',45],
  ['P1049','[NOIP 2001 普及组] 装箱问题','动态规划','普及−',45],
  ['P1216','[IOI 1994] 数字三角形 Number Triangles','动态规划','普及−',35],
  ['P1803','凌乱的yyy / 线段覆盖','贪心','普及−',40],
  ['P1090','[NOIP 2004 提高组] 合并果子','贪心','普及−',40],
  ['P3371','【模板】单源最短路径（弱化版）','图论','普及/提高−',60],
  ['P3367','【模板】并查集','数据结构','普及−',40],
  ['P3375','【模板】KMP','字符串','普及/提高−',60],
  ['P3383','【模板】线性筛素数','数学','普及−',40],
]
luogu.forEach(([code,title,topic,difficulty,minutes]) => problems.push({
  id: `LG-${code}`, platform:'LG', code,title, url:`https://www.luogu.com.cn/problem/${code}`,
  tags:[topic], rating:null, difficulty, minutes, provenance:'人工精选，阶段标签仅供排期参考',
}))
fs.writeFileSync(path.join(root,'backend/src/main/resources/catalog.json'), JSON.stringify(problems,null,2)+'\n')
console.log(`已保存 ${problems.length} 道题目，其中洛谷 ${luogu.length} 道`)
