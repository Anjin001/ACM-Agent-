<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api'
import type { Problem, SyncJob } from '../types'

defineProps<{ problems: Record<string, Problem>; syncJob: SyncJob }>()
const emit = defineEmits<{ sync: [] }>()
type Result = { problemId: string; status: string; submissions: number; firstAcceptedSeconds: number | null }
type Assessment = { id: string; handle: string; startedAt: number; deadline: number; endedAt: number | null; cancelled: boolean; problemIds: string[]; results: Result[] }
type State = { current: Assessment | null; history: Assessment[]; serverTime: number }

const state = ref<State | null>(null)
const busy = ref(false)
const error = ref('')
const time = ref(Date.now())
let serverOffset = 0
let timer: ReturnType<typeof setInterval>
const current = computed(() => state.value?.current)
const canStart = computed(() => !current.value || current.value.cancelled || !!current.value.results.length)
const seconds = computed(() => current.value && !current.value.endedAt
  ? Math.max(0, current.value.deadline - Math.floor(time.value / 1000 + serverOffset)) : 0)
const labels: Record<string, string> = { accepted: '已通过', unsubmitted: '未提交', attempted: '已提交，未通过' }

function duration(value: number) {
  return Math.floor(value / 60) + ':' + String(value % 60).padStart(2, '0')
}
async function update(path = '/assessment', method = 'GET') {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    state.value = await api<State>(path, method)
    serverOffset = state.value.serverTime - Date.now() / 1000
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '短测请求失败'
  } finally {
    busy.value = false
  }
}
onMounted(() => {
  void update()
  timer = setInterval(() => { time.value = Date.now() }, 1000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <section class="panel assessment">
    <div class="assessment-title">
      <div><h2>限时短测</h2><p>60 分钟，3 道 Codeforces 题。按提交时间对账。</p></div>
      <strong v-if="current && seconds > 0">{{ duration(seconds) }}</strong>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <ol v-if="current">
      <li v-for="id in current.problemIds" :key="id">
        <a v-if="problems[id]" :href="problems[id].url" target="_blank" rel="noopener noreferrer">
          {{ problems[id].code }} · {{ problems[id].title }}
        </a>
        <span v-else>{{ id }}</span>
      </li>
    </ol>
    <div v-if="current?.results.length" class="assessment-results">
      <p v-for="result in current.results" :key="result.problemId">
        {{ problems[result.problemId]?.code ?? result.problemId }} · {{ labels[result.status] }} · {{ result.submissions }} 次提交
        <template v-if="result.firstAcceptedSeconds !== null"> · 首次通过 {{ duration(result.firstAcceptedSeconds) }}</template>
      </p>
    </div>
    <button v-if="state && canStart" class="button primary" :disabled="busy"
      @click="update('/assessment', 'POST')">开始短测</button>
    <button v-else-if="current && seconds > 0" class="button outline" :disabled="busy"
      @click="update('/assessment/' + current.id + '/end', 'POST')">提前结束</button>
    <template v-else-if="current">
      <p>请同步本场使用的 CF 账号 {{ current.handle }}，同步完成后结算。</p>
      <button class="button outline" :disabled="busy || syncJob.status === 'running'" @click="emit('sync')">
        {{ syncJob.status === 'running' ? '正在同步' : '同步 CF 提交' }}
      </button>
      <button class="button primary" :disabled="busy || syncJob.status === 'running'"
        @click="update('/assessment/' + current.id + '/settle', 'POST')">结算短测</button>
    </template>
    <button v-if="current && !canStart" class="button outline" :disabled="busy"
      @click="update('/assessment/' + current.id + '/cancel', 'POST')">放弃本场</button>
    <details v-if="state?.history.length">
      <summary>历次短测</summary>
      <p v-for="test in state.history" :key="test.id">
        {{ new Date(test.startedAt * 1000).toLocaleDateString() }} · {{ test.cancelled ? '已放弃' : test.results.filter(r => r.status === 'accepted').length + '/3 道通过' }}
      </p>
    </details>
  </section>
</template>

<style scoped>
.assessment { padding: 20px; margin-bottom: 20px; display: grid; gap: 12px; }
.assessment-title { display: flex; justify-content: space-between; align-items: start; gap: 12px; }
.assessment-title p { color: #68788e; margin-top: 5px; }
.assessment ol { margin: 0; padding-left: 22px; }
.assessment a { color: #3159d9; }
.assessment .button { justify-self: start; }
.assessment [role="alert"] { color: #a34b2f; }
</style>
