import assert from 'node:assert/strict'

const base = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:18080'
let cookie = ''
async function request(path, method = 'GET', data, expected = 200) {
  const response = await fetch(`${base}/api${path}`, {
    method,
    headers: { ...(cookie ? { Cookie: cookie } : {}), ...(data ? { 'Content-Type': 'application/json' } : {}) },
    body: data ? JSON.stringify(data) : undefined,
  })
  const nextCookie = response.headers.getSetCookie()[0]
  if (nextCookie) cookie = nextCookie.split(';')[0]
  const result = await response.json()
  assert.equal(response.status, expected, `${path}: ${JSON.stringify(result)}`)
  return result
}

await request('/state', 'GET', undefined, 401)
const username = `verify_${Date.now()}`
let state = await request('/auth/register', 'POST', { username, password: 'local-verification-2026' })
const profile = { ...state.workspace.profile, name: '验收学员', handle: 'Fefer_Ivan', dailyMinutes: [90,90,90,90,90,90,90] }
state = await request('/profile', 'PUT', { revision: state.revision, profile })
const oldRevision = state.revision
state = await request('/plan/generate', 'POST', { revision: state.revision })
await request('/plan/generate', 'POST', { revision: oldRevision }, 409)
assert.ok(state.workspace.plan.tasks.length > 0)
const task = state.workspace.plan.tasks[0]
state = await request('/feedback', 'POST', { revision: state.revision, taskId: task.id, outcome: 'editorial', minutes: 53, note: '验收反馈：参考题解后理解边界，希望再独立练习。' })
state = await request('/plan/proposal', 'POST', { revision: state.revision, instruction: '每天30分钟' })
const proposal = state.workspace.proposal
assert.equal(proposal.engine, 'rules')
assert.ok(proposal.plan.dailyMinutes.every(minutes => minutes === 30))
state = await request('/plan/accept', 'POST', { revision: state.revision, proposalId: proposal.id })
state = await request('/plan/undo', 'POST', { revision: state.revision })
assert.equal(state.workspace.feedback[0].minutes, 53)
const reloaded = await request('/state')
assert.equal(reloaded.workspace.profile.name, '验收学员')
assert.equal(reloaded.workspace.feedback[0].note, state.workspace.feedback[0].note)
console.log('PASS 注册、设置、生成计划、旧版本冲突、反馈、调整、接受、撤销及再次读取')

// 中文时间意图：槽位解析 + 回显 + 计划依据
state = await request('/plan/proposal', 'POST', { revision: state.revision, instruction: '这周六抽半小时，周四没时间，最近总是边界出错' })
assert.equal(state.workspace.proposal.plan.dailyMinutes[5], 30)
assert.equal(state.workspace.proposal.plan.dailyMinutes[3], 0)
assert.ok(state.workspace.proposal.plan.timeEcho.includes('周六 30 分钟'))
assert.ok(state.workspace.proposal.basis.length > 0)
console.log(`PASS 中文时间解析：${state.workspace.proposal.plan.timeEcho}`)

// 教练记忆：有出处、可更正、可停用
const memory = await request('/memory')
assert.ok(memory.observations.length > 0)
assert.ok(memory.events.length > 0)
const observation = memory.observations[0]
// 接口返回整表，前端直接用 observations 覆盖，停用后这条必须还在列表里
const offline = await request(`/memory/${observation.id}`, 'PATCH', { statement: observation.statement, active: false })
assert.equal(offline.observations.length, memory.observations.length)
const disabled = offline.observations.find(item => item.id === observation.id)
assert.ok(disabled, '停用后观察仍在列表中')
assert.equal(disabled.active, false)
const restored = await request(`/memory/${observation.id}`, 'PATCH', { statement: observation.statement, active: true })
assert.equal(restored.observations.find(item => item.id === observation.id).active, true)
const added = await request('/memory', 'POST', { kind: 'constraint', statement: '周三晚上有课，不要排任务' })
assert.ok(added.observations.some(item => item.statement === '周三晚上有课，不要排任务'))
console.log(`PASS 教练记忆 ${memory.observations.length} 条观察、${memory.events.length} 条事件，可停用与恢复`)

// 能力画像：等级、样本与证据
const model = await request('/profile/model')
assert.ok(model.samples >= 1)
assert.ok(model.topics.some(topic => topic.samples > 0 && topic.evidence.length > 0))
assert.ok(model.summary.length > 0)
console.log(`PASS 能力画像：${model.samples} 条样本，独立完成率 ${Math.round(model.independentRate * 100)}%`)

// 代码审查：规则检查 + 历史
const source = [
  '#include <bits/stdc++.h>',
  'using namespace std;',
  'int a[1005];',
  'int main() {',
  '  int l = 1, r = 2000000000;',
  '  while (l < r) {',
  '    int mid = (l + r) / 2;',
  '    if (a[mid] < 3) l = mid + 1; else r = mid;',
  '  }',
  '  return 0;',
  '}',
].join('\n')
const review = await request('/code/review', 'POST', { problemId: task.problemId, files: [{ name: 'main.cpp', content: source }] })
assert.equal(review.engine, 'rules')
assert.ok(review.findings.some(item => item.message.includes('溢出')))
const reviews = await request('/code/review')
assert.ok(reviews.length >= 1)
console.log(`PASS 代码审查：${review.findings.length} 处提示（高 ${review.high} / 中 ${review.medium} / 低 ${review.low}）`)

if (process.env.SMOKE_CF === '1') {
  await request('/sync', 'POST')
  let job
  for (let attempt = 0; attempt < 60; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 1500))
    job = await request('/sync')
    if (job.status !== 'running') break
  }
  assert.equal(job.status, 'success', job.message)
  state = await request('/state')
  const before = state.workspace.sync.submissionCount
  assert.ok(before > 0)
  console.log(`PASS CF 实际同步 ${before} 条记录 / ${state.workspace.sync.solvedCount} 道已通过题`)
  await request('/sync', 'POST')
  for (let attempt = 0; attempt < 60; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 1500))
    job = await request('/sync')
    if (job.status !== 'running') break
  }
  assert.equal(job.status, 'success', job.message)
  state = await request('/state')
  assert.equal(state.workspace.sync.submissionCount, before)
  console.log('PASS CF 重复同步去重')
}
