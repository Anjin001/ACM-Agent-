<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { FolderOpen, LoaderCircle, Send } from 'lucide-vue-next'
import { api, ApiError } from '../api'

const props = defineProps<{ aiConfigured: boolean }>()
const emit = defineEmits<{ notify: [text: string, error?: boolean]; refresh: [] }>()

interface WorkspaceReport {
  id: string
  revision: number
  folder: string
  engine: string
  summary: string
  level: string
  nextQuestion: string
  files: { name: string; lines: number; chars: number }[]
  scannedFiles: number
  skippedFiles: number
  topics: { name: string; files: number }[]
  recurringIssues: { name: string; files: number }[]
  evidence: { topic: string; file: string; line: number; observation: string }[]
  gaps: string[]
  messages: { role: string; text: string; engine: string; remembered: boolean }[]
}

interface Folder { path: string; files: number }
interface Hit { file: string; line: number; text: string }
interface Source { file: string; firstLine: number; lines: string[]; more: boolean }
interface Job { status: string; processed: number; total: number; message: string; report: WorkspaceReport | null }

const path = ref('')
const folder = ref<Folder | null>(null)
const query = ref('')
const contentSearch = ref(false)
const hits = ref<Hit[]>([])
const offset = ref(0)
const source = ref<Source | null>(null)
const job = ref<Job | null>(null)
let mounted = true
const report = ref<WorkspaceReport | null>(null)
const busy = ref('')
const question = ref('')
const useModel = ref(false)
const remember = ref(false)
const engineName: Record<string, string> = { model: '模型分析', rules: '本地规则', fallback: '模型未返回可用结果，已使用本地规则' }

async function run(action: string, work: () => Promise<void>) {
  if (busy.value) return
  busy.value = action
  try {
    await work()
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '操作未完成，请稍后重试', true)
    if (error instanceof ApiError && error.status === 409) {
      try { report.value = (await api<{ report: WorkspaceReport | null }>('/code/workspace')).report } catch { /* retry on reload */ }
    }
  } finally {
    busy.value = ''
  }
}

async function loadHits(page: number) {
  const params = new URLSearchParams({ q: query.value, content: String(contentSearch.value), offset: String(page) })
  hits.value = await api<Hit[]>('/code/local/search?' + params)
  offset.value = page
}

function openFolder() {
  void run('open', async () => {
    folder.value = await api<Folder>('/code/local', 'POST', { path: path.value.trim() })
    path.value = folder.value.path
    source.value = null
    query.value = ''
    job.value = null
    await loadHits(0)
  })
}

function find(page = 0) {
  void run('search', () => loadHits(page))
}

function view(file: string, line: number) {
  void run('view', async () => {
    source.value = await api<Source>('/code/local/file?' + new URLSearchParams({ file, line: String(line) }))
    await nextTick()
    document.getElementById('workspace-source-preview')?.scrollIntoView({ block: 'nearest' })
  })
}

async function followScan() {
  while (mounted && job.value?.status === 'running') {
    await new Promise((resolve) => setTimeout(resolve, 600))
    if (!mounted) return
    job.value = await api<Job>('/code/local/scan')
  }
  if (!mounted) return
  if (job.value?.status === 'error') throw new Error(job.value.message)
  if (job.value?.report) {
    report.value = job.value.report
    question.value = ''
    remember.value = false
    emit('refresh')
    emit('notify', '扫描完成，已更新报告和教练记忆')
  }
}

function analyze() {
  void run('analyze', async () => {
    if (!folder.value) return
    if (report.value && !window.confirm('重新扫描会替换当前报告和对话。继续？')) return
    job.value = await api<Job>('/code/local/scan?useModel=' + (props.aiConfigured && useModel.value), 'POST')
    await followScan()
  })
}

function ask() {
  void run('chat', async () => {
    if (!report.value || !question.value.trim()) return
    const note = question.value.trim()
    if (remember.value && note.length > 300) throw new Error('加入教练记忆的自述最多 300 字')
    report.value = await api<WorkspaceReport>('/code/workspace/chat', 'POST', {
      workspaceId: report.value.id,
      revision: report.value.revision,
      question: note,
      useModel: props.aiConfigured && useModel.value,
      remember: remember.value,
    })
    question.value = ''
    if (remember.value) emit('refresh')
    remember.value = false
  })
}

function clear() {
  void run('clear', async () => {
    if (!report.value || !window.confirm('清除当前报告和对话？已明确加入教练记忆的自述可以在记忆面板管理。')) return
    await api('/code/workspace/' + report.value.id + '?revision=' + report.value.revision, 'DELETE')
    report.value = null
    question.value = ''
    remember.value = false
  })
}

onBeforeUnmount(() => { mounted = false })
onMounted(() => run('load', async () => {
  const saved = await api<{ report: WorkspaceReport | null }>('/code/workspace')
  report.value = saved.report
  folder.value = (await api<{ folder: Folder | null }>('/code/local')).folder
  if (folder.value) {
    path.value = folder.value.path
    await loadHits(0)
    job.value = await api<Job | null>('/code/local/scan')
    if (job.value?.status === 'running') await followScan()
  }
}))
</script>

<template>
  <section class="panel workspace">
    <div class="workspace-header">
      <div>
        <h2>代码工作区</h2>
        <p>选择刷题目录，查看写过的专题、实现证据和下一步练习。</p>
      </div>
    </div>
    <form class="workspace-actions" @submit.prevent="openFolder">
      <input v-model="path" class="workspace-path" aria-label="本机目录路径" placeholder="例如 D:\\ACM\\solutions" />
      <button class="button outline" type="submit" :disabled="!!busy || !path.trim()">
        <FolderOpen :size="17" />打开本机目录
      </button>
    </form>
    <p class="workspace-hint">在资源管理器中复制目录路径，粘贴到这里。本机后端直接读取目录，刷新文件清单可重新打开同一路径。</p>
    <div v-if="folder" class="workspace-selection">
      <p><strong>{{ folder.path }}</strong> · {{ folder.files }} 个 C/C++ 文件</p>
      <form class="workspace-actions" @submit.prevent="find()">
        <input v-model="query" class="workspace-path" maxlength="160" aria-label="搜索代码" placeholder="搜索文件名或代码内容" />
        <label><input v-model="contentSearch" type="checkbox" />搜索内容</label>
        <button type="submit" class="button outline" :disabled="!!busy">搜索</button>
      </form>
      <ul class="workspace-files">
        <li v-for="hit in hits" :key="hit.file + ':' + hit.line">
          <button type="button" class="workspace-link" :disabled="!!busy" @click="view(hit.file, hit.line)">
            {{ hit.file }}<template v-if="hit.text">:{{ hit.line }} · {{ hit.text }}</template>
          </button>
        </li>
      </ul>
      <p v-if="!hits.length">当前页没有匹配文件。</p>
      <div class="workspace-actions">
        <button v-if="offset" class="button outline" type="button" :disabled="!!busy" @click="find(offset - 60)">上一页</button>
        <button v-if="hits.length === 60" class="button outline" type="button" :disabled="!!busy" @click="find(offset + 60)">下一页</button>
      </div>
    </div>
    <section v-if="source" id="workspace-source-preview" class="workspace-selection">
      <div class="workspace-header">
        <strong>{{ source.file }}</strong>
        <button class="button outline" type="button" @click="source = null">关闭预览</button>
      </div>
      <pre class="workspace-source"><code><span v-for="(line, index) in source.lines" :key="index">{{ source.firstLine + index }}  {{ line }}{{ '\n' }}</span></code></pre>
      <div class="workspace-actions">
        <button v-if="source.firstLine > 1" class="button outline" type="button" :disabled="!!busy"
          @click="view(source.file, Math.max(1, source.firstLine - 120))">前 120 行</button>
        <button v-if="source.more" class="button outline" type="button" :disabled="!!busy"
          @click="view(source.file, source.firstLine + source.lines.length)">后 120 行</button>
      </div>
    </section>
    <div class="workspace-actions">
      <label v-if="aiConfigured">
        <input v-model="useModel" type="checkbox" :disabled="!!busy" />使用 AI 汇总和回复
      </label>
      <button v-if="folder" class="button primary" type="button" :disabled="!!busy" @click="analyze">扫描整个目录</button>
    </div>
    <div v-if="job?.status === 'running'" class="workspace-progress" role="status">
      <progress :value="job.processed" :max="job.total" aria-label="目录扫描进度" />
      <p><LoaderCircle class="spinning" :size="14" />{{ job.processed === job.total ? '正在生成报告'
        : '正在扫描 ' + job.processed + ' / ' + job.total + ' 个文件' }}</p>
    </div>
    <p class="workspace-hint">扫描在本机后台运行。启用 AI 时，模型服务接收统计、代表证据和教练对话。</p>

    <div v-if="report" class="workspace-report">
      <div class="workspace-header">
        <div>
          <h3>{{ report.folder }} · 分析报告</h3>
          <p>{{ engineName[report.engine] }} · {{ report.scannedFiles || report.files.length }} 个文件
            <template v-if="report.skippedFiles"> · 跳过 {{ report.skippedFiles }} 个</template>
          </p>
        </div>
        <button class="button outline" type="button" :disabled="!!busy" @click="clear">清除报告</button>
      </div>
      <p>{{ report.summary }}</p>
      <div v-if="report.topics?.length" class="workspace-topics">
        <span v-for="topic in report.topics" :key="topic.name">{{ topic.name }} · {{ topic.files }} 个文件</span>
      </div>
      <div class="workspace-assessment">
        <div>
          <h4>当前水平线索</h4>
          <p>{{ report.level }}</p>
        </div>
        <div>
          <h4>查漏补缺</h4>
          <ul><li v-for="(gap, index) in report.gaps" :key="index">{{ gap }}</li></ul>
        </div>
      </div>
      <details v-if="report.evidence.length" open>
        <summary>源码证据（{{ report.evidence.length }}）</summary>
        <ul class="workspace-evidence">
          <li v-for="(item, index) in report.evidence" :key="index">
            <strong>{{ item.topic }}</strong>
            <button v-if="folder" type="button" class="workspace-link" :disabled="!!busy"
              title="在当前打开的目录查看对应位置" @click="view(item.file, item.line)">
              <code>{{ item.file }}:{{ item.line }}</code>
            </button>
            <code v-else>{{ item.file }}:{{ item.line }}</code>
            <p>{{ item.observation }}</p>
          </li>
        </ul>
      </details>

      <div class="workspace-chat">
        <h3>和教练继续聊</h3>
        <p>{{ report.nextQuestion }}</p>
        <ol v-if="report.messages.length" class="workspace-messages" aria-live="polite">
          <li v-for="(message, index) in report.messages" :key="index" :class="message.role">
            <strong>{{ message.role === 'user' ? '你' : '教练' }}</strong>
            <small v-if="message.remembered"> · 已加入记忆</small>
            <small v-else-if="message.engine && message.engine !== 'model'"> · {{ engineName[message.engine] }}</small>
            <p>{{ message.text }}</p>
          </li>
        </ol>
        <form class="workspace-compose" @submit.prevent="ask">
          <textarea v-model="question" rows="2" maxlength="1000" aria-label="回复教练"
            placeholder="补充哪些代码是你写的，或问下一步该练什么" />
          <div class="workspace-actions">
            <label><input v-model="remember" type="checkbox" :disabled="!!busy" />把这条自述加入教练记忆</label>
            <button class="button primary" :disabled="!!busy || !question.trim()" type="submit">
              <Send :size="15" />{{ busy === 'chat' ? '教练正在回复' : '发送' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </section>
</template>

<style scoped>
.workspace-path { flex: 1 1 220px; width: auto; min-width: 0; }
.workspace-link { text-align: left; color: #3159d9; overflow-wrap: anywhere; padding: 4px 0; }
.workspace-source { max-height: 420px; overflow: auto; padding: 12px; background: #fff; }
.workspace { margin-bottom: 22px; padding: clamp(16px, 3vw, 26px); display: grid; gap: 15px; min-width: 0; }
.workspace-header, .workspace-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.workspace h2 { font-size: 19px; }
.workspace h3 { font-size: 15px; }
.workspace h4 { font-size: 13px; margin-bottom: 8px; }
.workspace-header p, .workspace-hint, .workspace-actions p { color: #68788e; font-size: 12px; margin-top: 5px; }
.workspace-picker { cursor: pointer; position: relative; overflow: hidden; }
.workspace-picker input { position: absolute; inset: 0; cursor: pointer; opacity: 0; }
.workspace-picker:focus-within { outline: 2px solid #3159d9; }
.workspace-selection { background: #f6f8fc; border: 1px solid #e8eef7; border-radius: 8px; padding: 12px; }
.workspace-selection p { margin-bottom: 6px; overflow-wrap: anywhere; }
.workspace summary { cursor: pointer; font-weight: 600; color: #405985; }
.workspace-files { max-height: 210px; overflow: auto; margin: 8px 0 0; padding-left: 20px; overflow-wrap: anywhere; }
.workspace-progress progress { width: 100%; accent-color: #3159d9; }
.workspace-progress p { display: flex; align-items: center; gap: 8px; color: #53688c; font-size: 12px; }
.workspace-topics { display: flex; flex-wrap: wrap; gap: 6px; }
.workspace-topics span { padding: 3px 9px; border-radius: 5px; background: #eef3ff; color: #405985; font-size: 12px; }
.workspace-actions label { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 13px; color: #465570; }
.workspace input[type='checkbox'] { flex: 0 0 auto; width: auto; padding: 0; }
.workspace-report { display: grid; gap: 14px; border-top: 1px solid #e9edf5; padding-top: 16px; line-height: 1.55; overflow-wrap: anywhere; }
.workspace-assessment { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 1fr)); gap: 12px; }
.workspace-assessment > div { padding: 13px; background: #f6f8fc; border-radius: 8px; }
.workspace-assessment ul { padding-left: 18px; margin: 0; }
.workspace-evidence, .workspace-messages { list-style: none; padding: 0; margin: 8px 0 0; display: grid; gap: 6px; }
.workspace-evidence li { border-bottom: 1px solid #edf0f6; padding: 6px 0; }
.workspace-evidence code { color: #53688c; font-size: 12px; }
.workspace-chat { display: grid; gap: 10px; }
.workspace-messages { max-height: 360px; overflow: auto; }
.workspace-messages li { background: #f5f7fc; padding: 8px 12px; border-radius: 8px; }
.workspace-messages li.user { background: #eef3ff; }
.workspace-messages p { white-space: pre-wrap; }
.workspace-compose { display: grid; gap: 8px; }
.workspace-compose textarea { min-height: 62px; }
</style>
