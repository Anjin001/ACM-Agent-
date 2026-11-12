<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import type { CodeReview, Problem, Task } from '../types'

const props = defineProps<{
  tasks: Task[]
  problemMap: Record<string, Problem>
}>()
const emit = defineEmits<{ notify: [text: string, error?: boolean]; refresh: [] }>()

interface PickedFile {
  name: string
  content: string
  selected: boolean
  open: boolean
}

const files = ref<PickedFile[]>([])
const review = ref<CodeReview | null>(null)
const history = ref<CodeReview[]>([])
const busy = ref(false)
const problemId = ref('')
const problemTitle = ref('')

const options = computed(() => {
  const seen = new Set<string>()
  return props.tasks
    .filter((task) => {
      if (seen.has(task.problemId)) return false
      seen.add(task.problemId)
      return true
    })
    .map((task) => {
      const problem = props.problemMap[task.problemId]
      return {
        id: task.problemId,
        label: problem ? `${problem.code} ${problem.title}` : task.problemId,
      }
    })
})
const totalChars = computed(() => files.value.reduce((sum, file) => sum + file.content.length, 0))
const selectedCount = computed(() => files.value.filter((file) => file.selected).length)

async function pick(event: Event) {
  const input = event.target as HTMLInputElement
  const picked = Array.from(input.files || [])
  for (const file of picked) {
    if (files.value.some((item) => item.name === file.name)) continue
    if (files.value.length >= 5) {
      emit('notify', '一次最多审查 5 个文件', true)
      break
    }
    const content = await file.text()
    if (content.length > 20000) {
      emit('notify', `${file.name} 超过 20000 字符，请只选需要审查的片段`, true)
      continue
    }
    files.value.push({ name: file.name, content, selected: true, open: false })
  }
  input.value = ''
}

function removeFile(name: string) {
  files.value = files.value.filter((file) => file.name !== name)
}

function lines(content: string) {
  return content.split('\n')
}

function hasFinding(file: string, line: number) {
  return !!review.value?.findings.some((item) => item.file === file && item.line === line)
}

async function submit() {
  const selected = files.value.filter((file) => file.selected)
  if (!selected.length) {
    emit('notify', '请先勾选要提交的文件', true)
    return
  }
  if (totalChars.value > 60000) {
    emit('notify', '提交内容超过 60000 字符，请缩小范围', true)
    return
  }
  busy.value = true
  try {
    const result = await api<CodeReview>('/code/review', 'POST', {
      taskId: null,
      problemId: problemId.value || null,
      files: selected.map((file) => ({ name: file.name, content: file.content })),
    })
    review.value = result
    problemTitle.value = result.problemTitle || ''
    await loadHistory()
    emit('refresh')
    emit('notify', result.summary)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '审查未完成，请重试', true)
  } finally {
    busy.value = false
  }
}

async function loadHistory() {
  try {
    history.value = await api<CodeReview[]>('/code/review?limit=6')
  } catch {
    history.value = []
  }
}

function openHistory(item: CodeReview) {
  review.value = item
  problemTitle.value = item.problemTitle || ''
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function severityLabel(severity: string) {
  return severity === 'high' ? '高风险' : severity === 'medium' ? '需确认' : '提示'
}

onMounted(loadHistory)
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>把代码交给教练看一遍。</h1>
      <p>先在本机选中 C++ 文件，预览并勾选要提交的片段；源码不会入库，只保存文件名与审查结论。</p>
    </div>
    <span class="cr-engine">规则检查离线可用<span v-if="!tasks.length"> · 先生成周计划可关联题目</span></span>
  </div>

  <div class="cr-layout">
    <section class="panel cr-panel">
      <div class="cr-step">
        <span class="cr-step-no">1</span>
        <div>
          <strong>选择文件并预览</strong>
          <small>支持 .cpp / .cc / .cxx / .h / .hpp，单文件 ≤ 20000 字符，最多 5 个。</small>
        </div>
        <label class="cr-pick">
          选择文件
          <input type="file" multiple accept=".cpp,.cc,.cxx,.c,.h,.hpp,.txt" @change="pick" />
        </label>
      </div>

      <div v-if="files.length" class="cr-files">
        <article v-for="file in files" :key="file.name" class="cr-file">
          <div class="cr-file-head">
            <label class="cr-check">
              <input v-model="file.selected" type="checkbox" />
              <span>{{ file.name }}</span>
            </label>
            <span class="cr-file-meta">{{ lines(file.content).length }} 行 · {{ file.content.length }} 字符</span>
            <button class="cr-link" @click="file.open = !file.open">{{ file.open ? '收起' : '预览' }}</button>
            <button class="cr-link danger" @click="removeFile(file.name)">移除</button>
          </div>
          <pre v-if="file.open" class="cr-code"><span
            v-for="(line, index) in lines(file.content)"
            :key="index"
            class="cr-line"
            :class="{ hit: hasFinding(file.name, index + 1) }"
          ><em>{{ index + 1 }}</em>{{ line || ' ' }}</span></pre>
        </article>
      </div>
      <p v-else class="cr-empty">还没有选择文件。选好后可以先预览行号，再决定提交哪些片段。</p>

      <div v-if="files.length" class="cr-submit">
        <label class="cr-problem">
          关联题目
          <select v-model="problemId">
            <option value="">不关联</option>
            <option v-for="option in options" :key="option.id" :value="option.id">{{ option.label }}</option>
          </select>
        </label>
        <span class="cr-total">已选 {{ selectedCount }} 个文件 · {{ totalChars }} 字符</span>
        <button class="button primary" :disabled="busy || !selectedCount" @click="submit">
          {{ busy ? '正在审查' : '提交审查' }}
        </button>
      </div>
    </section>

    <aside class="cr-side">
      <section v-if="review" class="panel cr-result">
        <div class="cr-result-head">
          <div>
            <strong>{{ problemTitle || review.problemTitle || '未关联题目' }}</strong>
            <small>{{ review.engine === 'model' ? '规则 + 模型审查' : '规则审查' }} · {{ review.files.length }} 个文件</small>
          </div>
          <div class="cr-sev">
            <span class="high">{{ review.high }}</span>
            <span class="medium">{{ review.medium }}</span>
            <span class="low">{{ review.low }}</span>
          </div>
        </div>
        <p class="cr-summary">{{ review.summary }}</p>
        <div v-if="review.findings.length" class="cr-findings">
          <article v-for="(item, index) in review.findings" :key="index" class="cr-finding" :class="item.severity">
            <div class="cr-finding-head">
              <span class="cr-tag">{{ severityLabel(item.severity) }}</span>
              <span class="cr-kind">{{ item.kind }}</span>
              <span class="cr-loc">{{ item.file }}:{{ item.line }}</span>
            </div>
            <p class="cr-message">{{ item.message }}</p>
            <code v-if="item.excerpt" class="cr-excerpt">{{ item.excerpt }}</code>
            <p class="cr-suggestion">建议：{{ item.suggestion }}</p>
          </article>
        </div>
        <p v-else class="cr-empty">这次没有发现明显问题，可以对照数据范围再自查一遍边界。</p>
      </section>

      <section class="panel cr-history">
        <div class="cr-history-head">
          <strong>最近审查</strong>
          <small>{{ history.length }} 次</small>
        </div>
        <p v-if="!history.length" class="cr-empty">还没有审查记录。</p>
        <button v-for="item in history" :key="item.id" class="cr-history-item" @click="openHistory(item)">
          <span>{{ item.problemTitle || '未关联题目' }}</span>
          <small>{{ item.files.length }} 个文件 · {{ item.findings.length }} 处提示 · {{ item.engine === 'model' ? '模型' : '规则' }}</small>
        </button>
      </section>
    </aside>
  </div>
</template>

<style scoped>
.cr-engine {
  font-size: 10px;
  color: #6382ce;
  background: #eef3ff;
  border-radius: 20px;
  padding: 6px 10px;
  white-space: nowrap;
}
.cr-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(300px, 1fr);
  gap: 21px;
  align-items: start;
}
.cr-panel {
  padding: 20px 22px;
}
.cr-step {
  display: flex;
  align-items: center;
  gap: 12px;
}
.cr-step-no {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #eef3ff;
  color: #3159d9;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.cr-step strong {
  display: block;
  font-size: 12px;
  font-weight: 500;
}
.cr-step small {
  display: block;
  font-size: 10px;
  color: #93a0b5;
  margin-top: 2px;
}
.cr-pick {
  margin-left: auto;
  font-size: 11px;
  color: #3159d9;
  border: 1px solid #d8e3f5;
  border-radius: 6px;
  padding: 7px 12px;
  cursor: pointer;
  background: #fff;
}
.cr-pick input {
  display: none;
}
.cr-files {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cr-file {
  border: 1px solid #eef3fb;
  border-radius: 8px;
  overflow: hidden;
}
.cr-file-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 11px;
  background: #fafcff;
}
.cr-check {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  min-width: 0;
  flex: 1 1 auto;
}
.cr-check span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cr-file-meta {
  font-size: 10px;
  color: #93a0b5;
  margin-left: auto;
  white-space: nowrap;
  flex-shrink: 0;
}
.cr-link {
  font-size: 10px;
  color: #3159d9;
  background: #eef3ff;
  border-radius: 4px;
  padding: 4px 7px;
}
.cr-link.danger {
  color: #a95a5a;
  background: #fdf4f4;
}
.cr-code {
  margin: 0;
  padding: 10px 0;
  background: #fbfcfe;
  max-height: 320px;
  overflow: auto;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
  line-height: 1.7;
}
.cr-line {
  display: block;
  padding: 0 12px 0 0;
  white-space: pre;
  color: #33415a;
}
.cr-line em {
  display: inline-block;
  width: 34px;
  padding-right: 10px;
  text-align: right;
  color: #c0cad9;
  font-style: normal;
  user-select: none;
}
.cr-line.hit {
  background: #fff7f2;
}
.cr-empty {
  margin: 14px 0 2px;
  font-size: 12px;
  color: #8793a8;
  line-height: 1.8;
}
.cr-submit {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #f2f6fb;
}
.cr-problem {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: #7a879c;
}
.cr-problem select {
  border: 1px solid #e4eaf4;
  border-radius: 5px;
  padding: 6px 8px;
  font-size: 11px;
  background: #fff;
  max-width: 200px;
}
.cr-total {
  margin-left: auto;
  font-size: 10px;
  color: #93a0b5;
}
.cr-side {
  display: flex;
  flex-direction: column;
  gap: 21px;
  min-width: 0;
}
.cr-result {
  padding: 18px 20px;
}
.cr-result-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.cr-result-head strong {
  font-size: 12px;
  font-weight: 500;
}
.cr-result-head small {
  display: block;
  font-size: 10px;
  color: #93a0b5;
  margin-top: 3px;
}
.cr-sev {
  display: flex;
  gap: 4px;
}
.cr-sev span {
  font-size: 10px;
  border-radius: 4px;
  padding: 3px 6px;
}
.cr-sev .high {
  background: #fdeeee;
  color: #a94b4b;
}
.cr-sev .medium {
  background: #fff6e8;
  color: #9a7534;
}
.cr-sev .low {
  background: #f1f6fd;
  color: #6b83a6;
}
.cr-summary {
  margin: 10px 0 4px;
  font-size: 11px;
  line-height: 1.8;
  color: #3d4a61;
}
.cr-findings {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 520px;
  overflow: auto;
}
.cr-finding {
  border-left: 2px solid #dfe7f4;
  padding: 7px 0 7px 10px;
}
.cr-finding.high {
  border-color: #e5a3a3;
}
.cr-finding.medium {
  border-color: #eccb92;
}
.cr-finding-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
}
.cr-tag {
  color: #61718b;
}
.cr-finding.high .cr-tag {
  color: #a94b4b;
}
.cr-finding.medium .cr-tag {
  color: #9a7534;
}
.cr-kind {
  color: #7a879c;
}
.cr-loc {
  margin-left: auto;
  color: #b3bdcb;
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.cr-message {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.7;
  color: #2a3549;
}
.cr-excerpt {
  display: block;
  margin-top: 4px;
  padding: 5px 7px;
  background: #fbfcfe;
  border-radius: 4px;
  font-size: 10px;
  color: #5c6b82;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cr-suggestion {
  margin: 5px 0 0;
  font-size: 10px;
  line-height: 1.7;
  color: #6d7c93;
}
.cr-history {
  padding: 16px 18px;
}
.cr-history-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.cr-history-head strong {
  font-size: 12px;
  font-weight: 500;
}
.cr-history-head small {
  font-size: 10px;
  color: #93a0b5;
}
.cr-history-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  text-align: left;
  padding: 8px 0;
  border-top: 1px solid #f2f6fb;
  font-size: 11px;
  color: #2a3549;
}
.cr-history-item small {
  font-size: 10px;
  color: #93a0b5;
}
.cr-history-item:hover {
  color: #3159d9;
}
@media (max-width: 1020px) {
  .cr-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 700px) {
  .cr-submit {
    flex-wrap: wrap;
  }
  .cr-total {
    margin-left: 0;
    flex-basis: 100%;
  }
  .cr-file-head {
    flex-wrap: wrap;
  }
  .cr-file-meta {
    margin-left: 0;
  }
}
</style>
