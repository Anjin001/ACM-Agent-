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
