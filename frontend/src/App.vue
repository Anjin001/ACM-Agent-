<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import {
  LayoutDashboard,
  ChartNoAxesColumnIncreasing,
  CalendarDays,
  Library,
  NotebookPen,
  Settings2,
  ChevronsLeftRight,
  ChevronRight,
  ChevronLeft,
  ArrowUpRight,
  ArrowRight,
  RefreshCw,
  Sparkles,
  Clock3,
  Target,
  Check,
  X,
  Plus,
  Undo2,
  Search,
  SlidersHorizontal,
  LogIn,
  Menu,
  CheckCircle2,
  CircleHelp,
  Link2,
  BookOpen,
  LoaderCircle,
  Download,
  Code2,
} from 'lucide-vue-next'
import TaskList from './components/TaskList.vue'
import AbilityAssessment from './components/AbilityAssessment.vue'
import MemoryPanel from './components/MemoryPanel.vue'
import CodeWorkspace from './components/CodeWorkspace.vue'
import TimedAssessment from './components/TimedAssessment.vue'
import { api, ApiError } from './api'
import type {
  AppState,
  Profile,
  Task,
  SyncJob,
  Problem,
  ProfileModel,
  Observation,
  MemoryEvent,
} from './types'

type Page = 'dashboard' | 'plan' | 'library' | 'review' | 'ability' | 'code' | 'settings'
const navigation = [
  { id: 'dashboard' as Page, label: '训练概览', icon: LayoutDashboard },
  { id: 'plan' as Page, label: '周训练计划', icon: CalendarDays },
  { id: 'library' as Page, label: '精选题库', icon: Library },
  { id: 'ability' as Page, label: '能力评估', icon: ChartNoAxesColumnIncreasing },
  { id: 'review' as Page, label: '训练复盘', icon: NotebookPen },
  { id: 'code' as Page, label: '代码审查', icon: Code2 },
]
const topics = [
  '二分',
  '搜索',
  '动态规划',
  '贪心',
  '图论',
  '数学',
  '字符串',
  '计算几何',
  '数据结构',
  '双指针',
  '模拟',
]
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const state = ref<AppState | null>(null)
const page = ref<Page>(readPage())
const sidebarOpen = ref(false)
const busy = ref('')
const fatal = ref('')
const toast = ref<{ text: string; error: boolean } | null>(null)
let toastTimer: ReturnType<typeof setTimeout>
let pollTimer: ReturnType<typeof setInterval>
let polling = false
let previousFocus: HTMLElement | null = null
const modal = ref<'feedback' | 'adjust' | 'auth' | null>(null)
const activeTask = ref<Task | null>(null)
const feedback = ref({ outcome: 'independent', minutes: 40, note: '', editorialNotes: '', mistakeNote: '' })
const instruction = ref('针对最近参考题解才完成的题，再安排一次独立练习。')
const authMode = ref<'login' | 'register'>('register')
const credentials = ref({ username: '', password: '' })
const draft = ref<Profile | null>(null)
const aiDraft = ref({ baseUrl: '', model: '', apiKey: '' })
watch(() => state.value?.username, resetAiDraft)
const selectedDay = ref(-1)
const query = ref('')
const platform = ref('all')
const topic = ref('all')
const libraryPage = ref(1)
const profileModel = ref<ProfileModel | null>(null)
const profileError = ref('')
const profileLoading = ref(false)
let profileRequest = 0
watch(() => state.value?.username, () => {
  profileRequest++
  profileModel.value = null
  profileError.value = ''
  profileLoading.value = false
  if (page.value === 'ability') void loadProfileModel()
}, { flush: 'sync' })
const memoryData = ref<{ observations: Observation[]; events: MemoryEvent[] }>({
  observations: [],
  events: [],
})
const memoryBusy = ref('')
const memoryLoaded = ref(false)
const workspace = computed(() => state.value?.workspace)
const plan = computed(() => workspace.value?.plan)
const tasks = computed(() => plan.value?.tasks || [])
const completed = computed(() =>
  tasks.value.filter((task) => ['independent', 'editorial', 'synced'].includes(task.status)),
)
const progress = computed(() =>
  tasks.value.length ? Math.round((completed.value.length / tasks.value.length) * 100) : 0,
)
const problemMap = computed(
  () =>
    Object.fromEntries(
      (state.value?.problems || []).map((problem) => [problem.id, problem]),
    ) as Record<string, Problem>,
)
const todayIndex = (new Date().getDay() + 6) % 7
const nextTask = computed(
  () =>
    tasks.value.find((task) => task.status === 'todo' && task.day >= todayIndex) ||
    tasks.value.find((task) => task.status === 'todo'),
)
const nextProblem = computed(() =>
  nextTask.value ? problemMap.value[nextTask.value.problemId] : null,
)
const trainedMinutes = computed(() =>
  (workspace.value?.feedback || []).reduce((sum, item) => sum + item.minutes, 0),
)
const scheduledMinutes = computed(() => tasks.value.reduce((sum, task) => sum + task.minutes, 0))
const budget = computed(() =>
  (plan.value?.dailyMinutes.length
    ? plan.value.dailyMinutes
    : workspace.value?.profile.dailyMinutes || []
  ).reduce((sum, minutes) => sum + minutes, 0),
)
const syncJob = ref<SyncJob>({ status: 'idle', message: '尚未同步' })
const visibleTasks = computed(() =>
  selectedDay.value < 0
    ? tasks.value
    : tasks.value.filter((task) => task.day === selectedDay.value),
)
const filteredProblems = computed(() =>
  (state.value?.problems || []).filter(
    (problem) =>
      (platform.value === 'all' || problem.platform === platform.value) &&
      (topic.value === 'all' || problem.tags.includes(topic.value)) &&
      `${problem.title} ${problem.code} ${problem.tags.join(' ')}`
        .toLowerCase()
        .includes(query.value.trim().toLowerCase()),
  ),
)
const pagedProblems = computed(() =>
  filteredProblems.value.slice((libraryPage.value - 1) * 12, libraryPage.value * 12),
)
const pageCount = computed(() => Math.max(1, Math.ceil(filteredProblems.value.length / 12)))
const recentFeedback = computed(() => [...(workspace.value?.feedback || [])].reverse())
const topicStats = computed(() =>
  (workspace.value?.profile.topics || []).map((name) => {
    const records = (workspace.value?.feedback || []).filter((item) =>
      problemMap.value[item.problemId]?.tags.includes(name),
    )
    return {
      name,
      attempted: records.length,
      independent: records.filter((item) => item.outcome === 'independent').length,
    }
  }),
)
const heroTitle = computed(() =>
  workspace.value?.profile.topics.length
    ? `这一周，专注${workspace.value.profile.topics.slice(0, 2).join('与')}。`
    : '为下一次进步，留出时间。',
)
const activePageTitle = computed(
  () =>
    [...navigation, { id: 'settings', label: '个人设置' }].find((item) => item.id === page.value)
      ?.label,
)

function readPage(): Page {
  const hash = location.hash.replace('#', '')
  return ['dashboard', 'plan', 'library', 'review', 'ability', 'code', 'settings'].includes(hash)
    ? (hash as Page)
    : 'dashboard'
}
function navigate(value: Page) {
  page.value = value
  sidebarOpen.value = false
  location.hash = value
  window.scrollTo(0, 0)
}
function handleHash() {
  page.value = readPage()
}
watch([query, platform, topic], () => {
  libraryPage.value = 1
})
watch(page, (value) => {
  if (value === 'ability') void loadProfileModel()
  if ((value === 'review' || value === 'code') && !memoryLoaded.value) void loadMemory()
})
watch(modal, async (value, oldValue) => {
  if (value && !oldValue) previousFocus = document.activeElement as HTMLElement
  document.body.style.overflow = value ? 'hidden' : ''
  await nextTick()
  if (value)
    document.querySelector<HTMLElement>('.modal input, .modal textarea, .modal button')?.focus()
  else previousFocus?.focus()
})
function notify(text: string, error = false) {
  toast.value = { text, error }
  clearTimeout(toastTimer)
  toastTimer = setTimeout(
    () => {
      toast.value = null
    },
    error ? 6500 : 3500,
  )
}
function adopt(value: AppState, updateDraft = true) {
  state.value = value
  if (updateDraft) draft.value = structuredClone(value.workspace.profile)
  syncJob.value = value.syncJob
}
async function load() {
  try {
    const value = await api<AppState>('/bootstrap')
    adopt(value)
    fatal.value = ''
  } catch (error) {
    fatal.value = error instanceof Error ? error.message : '服务连接失败'
  }
}
async function loadProfileModel() {
  if (!state.value) return
  const request = ++profileRequest
  profileLoading.value = true
  profileError.value = ''
  try {
    const model = await api<ProfileModel>('/profile/model')
    if (request === profileRequest) profileModel.value = model
  } catch {
    if (request === profileRequest) profileError.value = '能力评估加载失败，请重试'
  } finally {
    if (request === profileRequest) profileLoading.value = false
  }
}
async function loadMemory() {
  if (memoryBusy.value === 'memory') return
  memoryBusy.value = 'memory'
  try {
    memoryData.value = await api<{ observations: Observation[]; events: MemoryEvent[] }>('/memory')
    memoryLoaded.value = true
  } catch (error) {
    notify(error instanceof Error ? error.message : '记忆读取失败', true)
  } finally {
    memoryBusy.value = ''
  }
}
function correctMemory(id: string, statement: string, active: boolean) {
  act('memory', async () => {
    memoryData.value = await api<{ observations: Observation[]; events: MemoryEvent[] }>(
      `/memory/${id}`,
      'PATCH',
      { statement, active },
    )
    await loadProfileModel()
    notify(active ? '这条观察已更新' : '已停用这条观察，不再作为依据')
  })
}
function removeMemory(id: string) {
  act('memory', async () => {
    await api(`/memory/${id}`, 'DELETE')
    await loadMemory()
    await loadProfileModel()
    notify('已删除这条观察')
  })
}
function addMemory(kind: string, statement: string) {
  act('memory', async () => {
    memoryData.value = await api<{ observations: Observation[]; events: MemoryEvent[] }>(
      '/memory',
      'POST',
      { kind, statement },
    )
    await loadProfileModel()
    notify('观察已记录，下次生成计划会引用')
  })
}
async function act(name: string, action: () => Promise<void>) {
  if (busy.value) return
  busy.value = name
  try {
    await action()
  } catch (error) {
    notify(error instanceof Error ? error.message : '操作未完成，请重试', true)
    if (error instanceof ApiError && error.status === 409) await load()
  } finally {
    busy.value = ''
  }
}
function generate() {
  if (!state.value) return
  if (
    tasks.value.some((task) => task.status === 'todo') &&
    !window.confirm('根据当前设置重新安排待做任务？已有训练反馈会保留，也可以撤销这次修改。')
  )
    return
  act('generate', async () => {
    adopt(await api<AppState>('/plan/generate', 'POST', { revision: state.value!.revision }))
    selectedDay.value = -1
    notify(plan.value?.engine === 'model' ? 'AI 已生成本周计划' : (plan.value?.modelMessage || '本周计划已生成'))
  })
}
function openFeedback(task: Task) {
  activeTask.value = task
  const previous = workspace.value?.feedback.find(
    (item) => item.taskId === task.id && item.problemId === task.problemId,
  )
  feedback.value = previous
    ? { outcome: previous.outcome, minutes: previous.minutes, note: previous.note,
        editorialNotes: previous.editorialNotes ?? '', mistakeNote: previous.mistakeNote ?? '' }
    : { outcome: 'independent', minutes: task.minutes, note: '', editorialNotes: '', mistakeNote: '' }
  modal.value = 'feedback'
}
function saveFeedback() {
  act('feedback', async () => {
    adopt(
      await api<AppState>('/feedback', 'POST', {
        revision: state.value!.revision,
        taskId: activeTask.value!.id,
        ...feedback.value,
      }),
    )
    modal.value = null
    notify('复盘已保存，下次调整会参考这次反馈')
  })
}
function propose() {
  act('adjust', async () => {
    adopt(
      await api<AppState>('/plan/proposal', 'POST', {
        revision: state.value!.revision,
        instruction: instruction.value,
      }),
    )
    notify('调整方案已生成，确认后会应用到周计划')
  })
}
function acceptProposal() {
  act('accept', async () => {
    adopt(
      await api<AppState>('/plan/accept', 'POST', {
        revision: state.value!.revision,
        proposalId: workspace.value!.proposal!.id,
      }),
    )
    modal.value = null
    selectedDay.value = -1
    notify('已应用调整，可以撤销这次修改')
  })
}
function undo() {
  act('undo', async () => {
    adopt(await api<AppState>('/plan/undo', 'POST', { revision: state.value!.revision }))
    selectedDay.value = -1
    notify('已撤销上次修改，训练反馈已保留')
  })
}
function saveProfile() {
  act('profile', async () => {
    adopt(
      await api<AppState>('/profile', 'PUT', {
        revision: state.value!.revision,
        profile: draft.value,
      }),
    )
    notify('个人设置已保存，可重新生成计划')
  })
}
function resetAiDraft() {
  const saved = state.value?.aiSettings
  aiDraft.value = { baseUrl: saved?.baseUrl ?? '', model: saved?.model ?? '', apiKey: '' }
}
function saveAiSettings() {
  act('ai-save', async () => {
    const payload = { ...aiDraft.value }
    aiDraft.value.apiKey = ''
    adopt(await api<AppState>('/settings/ai', 'PUT', payload), false)
    resetAiDraft()
    notify('模型配置已保存在本机')
  })
}
function testAiSettings() {
  act('ai-test', async () => {
    const result = await api<{ ok: boolean; message: string }>('/settings/ai/test', 'POST')
    notify(result.message, !result.ok)
  })
}
function clearAiSettings() {
  act('ai-clear', async () => {
    adopt(await api<AppState>('/settings/ai', 'DELETE'), false)
    resetAiDraft()
    notify('已清除本机保存的模型配置')
  })
}
function toggleTopic(name: string) {
  if (!draft.value) return
  draft.value.topics = draft.value.topics.includes(name)
    ? draft.value.topics.filter((item) => item !== name)
    : [...draft.value.topics, name]
}
function synchronize() {
  if (!workspace.value?.profile.handle) {
    navigate('settings')
    notify('填写并保存 CF handle 后，即可同步真实做题记录')
    return
  }
  act('sync', async () => {
    syncJob.value = await api<SyncJob>('/sync', 'POST')
  })
}
async function pollSync() {
  if (syncJob.value.status !== 'running' || polling) return
  polling = true
  try {
    const job = await api<SyncJob>('/sync')
    syncJob.value = job
    if (job.status !== 'running') {
      const value = await api<AppState>('/state')
      adopt(value, page.value !== 'settings')
      notify(job.message, job.status === 'error')
    }
  } catch {
    syncJob.value = { status: 'error', message: '同步状态获取失败，请刷新后重试' }
    notify(syncJob.value.message, true)
  } finally {
    polling = false
  }
}
function submitAuth() {
  act('auth', async () => {
    adopt(await api<AppState>(`/auth/${authMode.value}`, 'POST', credentials.value))
    credentials.value.password = ''
    modal.value = null
    navigate('dashboard')
    notify(
      authMode.value === 'register' ? '账号已创建，先设置你的训练目标吧' : '已进入你的训练空间',
    )
  })
}
function enterDemo() {
  act('auth', async () => {
    adopt(await api<AppState>('/auth/demo', 'POST'))
    modal.value = null
    navigate('dashboard')
    notify('已进入演示空间')
  })
}
function dayDate(day: number) {
  const date = plan.value?.weekStart ? new Date(`${plan.value.weekStart}T12:00:00`) : new Date()
  if (!plan.value) date.setDate(date.getDate() - todayIndex)
  date.setDate(date.getDate() + day)
  return date
}
const weekLabel = computed(
  () =>
    `${dayDate(0).getMonth() + 1} 月 ${dayDate(0).getDate()} 日 — ${dayDate(6).getMonth() + 1} 月 ${dayDate(6).getDate()} 日`,
)
function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
function exportPlan() {
  const payload = {
    exportedAt: new Date().toISOString(),
    demo: state.value?.demo,
    profile: workspace.value?.profile,
    plan: plan.value,
    feedback: workspace.value?.feedback,
    problems: tasks.value.map((task) => problemMap.value[task.problemId]),
  }
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }),
  )
  const link = document.createElement('a')
  link.href = url
  link.download = `循练-训练计划-${plan.value?.weekStart || '草稿'}.json`
  link.click()
  URL.revokeObjectURL(url)
}
function closeModal(event?: MouseEvent) {
  if (!busy.value && (!event || event.target === event.currentTarget)) modal.value = null
}
function escapeModal(event: KeyboardEvent) {
  if (event.key === 'Escape') closeModal()
  if (event.key === 'Tab' && modal.value) {
    const elements = Array.from(
      document.querySelectorAll<HTMLElement>(
        '.modal button:not(:disabled), .modal input, .modal textarea, .modal select, .modal a[href]',
      ),
    )
    const first = elements[0]
    const last = elements[elements.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last?.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first?.focus()
    }
  }
}
onMounted(() => {
  load().then(() => {
    loadProfileModel()
    if (page.value === 'review' || page.value === 'code') loadMemory()
  })
  pollTimer = setInterval(pollSync, 1500)
  window.addEventListener('hashchange', handleHash)
  window.addEventListener('keydown', escapeModal)
})
onUnmounted(() => {
  clearInterval(pollTimer)
  clearTimeout(toastTimer)
  window.removeEventListener('hashchange', handleHash)
  window.removeEventListener('keydown', escapeModal)
})
</script>

<template>
  <div v-if="!state" class="boot-screen">
    <div class="brand-mark"><ChevronsLeftRight :size="25" /></div>
    <h1>循练</h1>
    <p v-if="!fatal">正在打开训练工作台…</p>
    <p v-else role="alert">{{ fatal }}</p>
    <button v-if="fatal" class="button primary" @click="load">重新连接</button>
  </div>
  <div v-else class="app-shell">
    <div v-if="sidebarOpen" class="sidebar-scrim" @click="sidebarOpen = false" />
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <a href="#dashboard" class="brand" @click.prevent="navigate('dashboard')"
        ><span class="brand-mark"><ChevronsLeftRight :size="25" /></span
        ><span class="brand-name">循练<span>ACM 训练工作台</span></span></a
      >
      <div class="space-label">
        <span class="space-dot" />{{ state.demo ? '课程演示空间' : '我的训练空间' }}
      </div>
      <nav aria-label="主导航">
        <button
          v-for="item in navigation"
          :key="item.id"
          :class="{ active: page === item.id }"
          :aria-current="page === item.id ? 'page' : undefined"
          @click="navigate(item.id)"
        >
          <component :is="item.icon" :size="19" /><span>{{ item.label }}</span
          ><span v-if="item.id === 'plan' && tasks.length" class="nav-count">{{
            tasks.length
          }}</span>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <div class="coach-mini">
          <div class="coach-mini-title">
            <Sparkles :size="17" />训练教练<span class="live-dot" />
          </div>
          <p>
            {{
              state.aiConfigured
                ? '模型服务已配置，调整时会检查生成结果。'
                : '规则教练已就绪，随时根据反馈调整计划。'
            }}
          </p>
          <button @click="plan ? (modal = 'adjust') : navigate('settings')">
            {{ plan ? '调整我的计划' : '设置训练目标' }}<ArrowUpRight :size="15" />
          </button>
        </div>
        <button
          class="sidebar-setting"
          :class="{ active: page === 'settings' }"
          @click="navigate('settings')"
        >
          <Settings2 :size="19" />个人设置
        </button>
        <button class="account" @click="modal = 'auth'">
          <span class="avatar">{{ workspace!.profile.name.slice(0, 1) }}</span
          ><span
            ><strong>{{ workspace!.profile.name }}</strong
            ><small>{{ state.demo ? '示例数据 · 可切换账号' : state.username }}</small></span
          ><ChevronRight :size="16" />
        </button>
      </div>
    </aside>

    <div class="main-shell">
      <header class="topbar">
        <div class="breadcrumb">
          <button class="icon-button mobile-menu" aria-label="展开导航" @click="sidebarOpen = true">
            <Menu :size="21" /></button
          ><span>我的工作台</span><ChevronRight :size="14" /><strong>{{ activePageTitle }}</strong>
        </div>
        <div class="topbar-actions">
          <span class="date-text">{{
            new Intl.DateTimeFormat('zh-CN', {
              month: 'long',
              day: 'numeric',
              weekday: 'long',
            }).format(new Date())
          }}</span
          ><button class="connection-pill" @click="navigate('settings')">
            <span class="live-dot" />{{
              workspace!.sync.syncedAt && workspace!.sync.handle === workspace!.profile.handle
                ? 'CF 已连接'
                : '连接 CF 账号'
            }}</button
          ><button class="avatar small" aria-label="切换账号" @click="modal = 'auth'">
            {{ workspace!.profile.name.slice(0, 1) }}
          </button>
        </div>
      </header>
      <main>
        <template v-if="page === 'dashboard'">
          <div class="page-heading">
            <div>
              <div class="greeting">
                {{ workspace!.profile.name }}，欢迎回来 <span class="wave">✦</span>
              </div>
              <h1>让训练，有迹可循。</h1>
              <p>从今天的一道题开始，走好下一步。</p>
            </div>
            <button
              class="button outline"
              :disabled="syncJob.status === 'running' || !!busy"
              @click="synchronize"
            >
              <RefreshCw :size="16" :class="{ spinning: syncJob.status === 'running' }" />{{
                syncJob.status === 'running' ? '正在同步' : '同步做题记录'
              }}
            </button>
          </div>

          <div
            v-if="syncJob.status === 'running' || syncJob.status === 'error'"
            class="notice"
            :class="{ error: syncJob.status === 'error' }"
            role="status"
          >
            <RefreshCw :size="16" :class="{ spinning: syncJob.status === 'running' }" />{{
              syncJob.message
            }}
          </div>
          <section class="overview-grid">
            <div class="week-hero">
              <div class="hero-copy">
                <span class="section-kicker"><span class="blue-dot" />本周训练方向</span>
                <h2>{{ heroTitle }}</h2>
                <p>{{ workspace!.profile.goal }}</p>
                <button class="hero-link" @click="navigate('plan')">
                  查看完整计划<ArrowRight :size="16" />
                </button>
              </div>
              <div class="progress-orbit" :style="{ '--progress': `${progress * 3.6}deg` }">
                <div class="orbit-inner">
                  <span
                    ><strong>{{ completed.length }}</strong
                    ><em>/ {{ tasks.length }}</em></span
                  ><small>本周已完成</small>
                </div>
              </div>
              <div class="hero-footer">
                <span><CalendarDays :size="14" />{{ weekLabel }}</span
                ><span
                  >{{ budget }} 分钟可用 <span class="hero-separator">/</span> 已安排
                  {{ scheduledMinutes }} 分钟</span
                >
              </div>
            </div>
            <div class="coach-card">
              <span class="coach-label"
                ><Sparkles :size="17" />{{
                  state.aiConfigured ? 'AI 训练教练' : '规则训练教练'
                }}</span
              >
              <h2>
                {{
                  recentFeedback.some((item) => item.outcome !== 'independent')
                    ? 'AC 之后，再多问一步。'
                    : '下一步，从反馈里找到。'
                }}
              </h2>
              <p>
                {{
                  recentFeedback.some((item) => item.outcome !== 'independent')
                    ? '参考题解完成，也是有价值的进展。再独立做一道同类题，让思路真正留下来。'
                    : '记录一次卡点、一次独立完成。你的训练计划会因此更贴近你。'
                }}
              </p>
              <button @click="plan ? (modal = 'adjust') : generate()">
                {{ plan ? '根据反馈调整计划' : '生成第一份计划'
                }}<ArrowUpRight :size="17" /></button
              ><span class="coach-card-decoration" aria-hidden="true">↗</span>
            </div>
          </section>

          <section class="metric-strip" aria-label="训练统计">
            <div>
              <span class="metric-icon blue"><CheckCircle2 :size="20" /></span
              ><span
                ><small>本周完成</small
                ><strong
                  >{{ completed.length }}<em> / {{ tasks.length }} 题</em></strong
                ></span
              ><span class="metric-foot">{{ progress }}%</span>
            </div>
            <div>
              <span class="metric-icon purple"><Clock3 :size="20" /></span
              ><span
                ><small>已记录训练</small><strong>{{ trainedMinutes }}<em> 分钟</em></strong></span
              >
            </div>
            <div>
              <span class="metric-icon orange"><Target :size="20" /></span
              ><span
                ><small>当前训练起点</small
                ><strong>{{ workspace!.profile.level }}<em> CF 难度参考</em></strong></span
              >
            </div>
            <div>
              <span class="metric-icon teal"><Link2 :size="20" /></span
              ><span
                ><small>CF 已同步通过</small
                ><strong
                  >{{
                    workspace!.sync.handle === workspace!.profile.handle
                      ? workspace!.sync.solvedCount
                      : 0
                  }}<em> 题</em></strong
                ></span
              ><span v-if="!workspace!.sync.syncedAt" class="metric-foot muted">待连接</span>
            </div>
          </section>

          <div class="content-columns">
            <div class="content-primary">
              <section class="panel week-panel">
                <div class="panel-heading">
                  <h2>一周训练节奏</h2>
                  <button class="text-button" @click="navigate('plan')">
                    管理计划<ChevronRight :size="15" />
                  </button>
                </div>
                <div class="week-days">
                  <button
                    v-for="(day, index) in weekdays"
                    :key="day"
                    class="day-cell"
                    :class="{ today: index === todayIndex, selected: selectedDay === index }"
                    @click="selectedDay = selectedDay === index ? -1 : index"
                  >
                    <span>{{ index === todayIndex ? '今天' : day }}</span
                    ><strong>{{ dayDate(index).getDate() }}</strong>
                    <div class="day-marks">
                      <i
                        v-for="task in tasks.filter((item) => item.day === index)"
                        :key="task.id"
                        :class="{
                          done: ['independent', 'editorial', 'synced'].includes(task.status),
                        }"
                      /><i v-if="!tasks.some((item) => item.day === index)" class="rest" />
                    </div>
                  </button>
                </div>
                <div class="schedule-legend">
                  <span><i />待训练</span><span><i class="done" />已完成</span
                  ><span class="legend-end">点击日期查看当天任务</span>
                </div>
              </section>
              <section class="panel tasks-panel">
                <div class="panel-heading">
                  <h2>
                    {{ selectedDay >= 0 ? `${weekdays[selectedDay]}的训练` : '本周训练清单'
                    }}<span class="count-label">{{ visibleTasks.length }}</span>
                  </h2>
                  <button class="text-button" :disabled="!!busy" @click="generate">
                    <Plus :size="15" />生成计划
                  </button>
                </div>
                <TaskList
                  :tasks="visibleTasks"
                  :problems="problemMap"
                  :show-day="selectedDay < 0"
                  @feedback="openFeedback"
                />
              </section>
            </div>
            <aside class="content-secondary">
              <section class="panel next-panel">
                <span class="section-kicker"><span class="blue-dot" />下一次练习</span
                ><template v-if="nextTask && nextProblem"
                  ><div class="next-problem-meta">
                    <span class="platform-badge" :class="nextProblem.platform">{{
                      nextProblem.platform === 'LG' ? '洛谷' : 'CF'
                    }}</span
                    ><span>{{ nextProblem.code }}</span
                    ><span>{{ nextProblem.difficulty }}</span>
                  </div>
                  <h2>{{ nextProblem.title }}</h2>
                  <p>{{ nextTask.reason }}</p>
                  <div class="next-bottom">
                    <span><Clock3 :size="14" />约 {{ nextTask.minutes }} 分钟</span
                    ><a
                      :href="nextProblem.url"
                      target="_blank"
                      rel="noopener noreferrer"
                      class="button primary"
                      >开始练习<ArrowUpRight :size="15"
                    /></a></div></template
                ><template v-else
                  ><h2>{{ tasks.length ? '这一轮，完成了。' : '从第一份计划开始。' }}</h2>
                  <p>设置可用时间，安排适合自己的练习。</p>
                  <button class="button primary" @click="generate">生成周计划</button></template
                >
              </section>
              <section class="panel topic-panel">
                <div class="panel-heading">
                  <h2>专题训练记录</h2>
                  <Target :size="17" class="muted" />
                </div>
                <div v-for="item in topicStats" :key="item.name" class="topic-stat">
                  <div>
                    <span>{{ item.name }}</span
                    ><small>{{ item.independent }} 独立 / {{ item.attempted }} 次练习</small>
                  </div>
                  <div class="topic-track">
                    <i
                      :style="{
                        width: `${item.attempted ? (item.independent / item.attempted) * 100 : 0}%`,
                      }"
                    />
                  </div>
                </div>
                <p class="panel-note">依据已记录的复盘统计，持续训练后会更完整。</p>
              </section>
              <div class="source-note">
                <BookOpen :size="16" />
                <p v-if="state.demo">当前学员与复盘为演示数据。题目链接指向 CF 和洛谷原题。</p>
                <p v-else>训练记录来自 CF 公开数据与个人复盘。洛谷任务通过手动反馈更新。</p>
              </div>
            </aside>
          </div>
        </template>

        <template v-if="page === 'plan'">
          <p v-if="workspace?.profile.competitionDate" class="notice">
            <Target :size="16" />目标比赛日期 {{ workspace.profile.competitionDate }}
          </p>
          <section v-if="plan?.basis?.some(item => item.startsWith('训练前检查'))" class="notice">
            <div><strong>训练前检查</strong>
              <ul class="basis-list">
                <li v-for="item in plan.basis.filter(item => item.startsWith('训练前检查'))" :key="item">
                  {{ item.replace('训练前检查 · ', '') }}
                </li>
              </ul>
            </div>
          </section>
          <TimedAssessment v-if="!state.demo" :key="state.username" :problems="problemMap" :sync-job="syncJob" @sync="synchronize" />
          <div class="page-heading">
            <div>
              <h1>把目标，安排进这一周。</h1>
              <p>
                {{ weekLabel }}<span v-if="plan">　计划版本 v{{ plan.version }}</span>
              </p>
            </div>
            <div class="heading-actions">
              <button class="button outline" :disabled="!plan" @click="exportPlan">
                <Download :size="16" />导出</button
              ><button class="button primary" :disabled="!!busy" @click="generate">
                <Plus :size="16" />{{ busy === 'generate' ? '正在生成' : '生成周计划' }}
              </button>
            </div>
          </div>
          <div class="plan-banner">
            <div>
              <Sparkles :size="21" /><span>{{
                plan?.explanation || '先在个人设置填写训练目标和可用时间，生成你的第一份周计划。'
              }}</span>
            </div>
            <div class="heading-actions">
              <button
                v-if="workspace!.history.length"
                class="text-button"
                :disabled="!!busy"
                @click="undo"
              >
                <Undo2 :size="15" />撤销修改</button
              ><button v-if="plan" class="button outline" @click="modal = 'adjust'">
                调整计划<ArrowUpRight :size="15" />
              </button>
            </div>
          </div>
          <p v-if="plan?.modelMessage" class="notice" role="status">
            <strong>{{ plan.engine === 'model' ? '模型生成' : '规则生成' }}</strong> {{ plan.modelMessage }}
          </p>
          <div class="plan-layout">
            <section
              v-for="(day, index) in weekdays"
              :key="day"
              class="panel plan-day"
              :class="{ 'is-today': index === todayIndex }"
            >
              <div class="plan-day-heading">
                <div>
                  <span>{{ day }}</span>
                  <h2>
                    {{ dayDate(index).getMonth() + 1 }}.{{
                      String(dayDate(index).getDate()).padStart(2, '0')
                    }}<small v-if="index === todayIndex">今天</small>
                  </h2>
                </div>
                <span
                  >{{
                    tasks
                      .filter((task) => task.day === index)
                      .reduce((sum, task) => sum + task.minutes, 0)
                  }}
                  /
                  {{
                    (plan?.dailyMinutes.length
                      ? plan.dailyMinutes
                      : workspace!.profile.dailyMinutes)[index]
                  }}
                  分钟</span
                >
              </div>
              <TaskList
                :tasks="tasks.filter((task) => task.day === index)"
                :problems="problemMap"
                @feedback="openFeedback"
              />
            </section>
          </div>
        </template>

        <template v-if="page === 'library'">
          <div class="page-heading">
            <div>
              <h1>为每一步，找到合适的题。</h1>
              <p>{{ state.problems.length }} 道精选题目，保留来源与原平台难度。</p>
            </div>
            <span class="quiet-badge">CF 公开快照 + 洛谷人工精选</span>
          </div>
          <section class="panel library-panel">
            <div class="library-filters">
              <label class="search-box"
                ><Search :size="18" /><input
                  v-model="query"
                  placeholder="搜索题号、题目或专题"
                  aria-label="搜索题目" /></label
              ><label class="select-box"
                ><SlidersHorizontal :size="15" /><select v-model="platform" aria-label="筛选平台">
                  <option value="all">全部平台</option>
                  <option value="CF">Codeforces</option>
                  <option value="LG">洛谷</option>
                </select></label
              ><select v-model="topic" aria-label="筛选专题">
                <option value="all">全部专题</option>
                <option v-for="item in topics" :key="item">{{ item }}</option>
              </select>
            </div>
            <div class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>题目</th>
                    <th>算法专题</th>
                    <th>原平台难度</th>
                    <th>参考用时</th>
                    <th>状态</th>
                    <th><span class="sr-only">查看原题</span></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="problem in pagedProblems" :key="problem.id">
                    <td>
                      <div class="problem-name">
                        <span class="platform-badge" :class="problem.platform">{{
                          problem.platform === 'LG' ? '洛谷' : 'CF'
                        }}</span
                        ><a :href="problem.url" target="_blank" rel="noopener noreferrer"
                          ><small>{{ problem.code }}</small
                          ><strong>{{ problem.title }}</strong></a
                        >
                      </div>
                    </td>
                    <td>
                      <div class="tag-list">
                        <span v-for="tag in problem.tags.slice(0, 2)" :key="tag" class="tag">{{
                          tag
                        }}</span>
                      </div>
                    </td>
                    <td>
                      <span
                        class="rating"
                        :class="{ beginner: !problem.rating || problem.rating <= 1000 }"
                        >{{ problem.difficulty }}</span
                      >
                    </td>
                    <td class="muted">{{ problem.minutes }} 分钟</td>
                    <td>
                      <span v-if="state.solvedIds.includes(problem.id)" class="solved-text"
                        ><Check :size="14" />已完成</span
                      ><span v-else class="muted">待探索</span>
                    </td>
                    <td>
                      <a
                        class="icon-button"
                        :href="problem.url"
                        target="_blank"
                        rel="noopener noreferrer"
                        :aria-label="`打开 ${problem.title}`"
                        ><ArrowUpRight :size="17"
                      /></a>
                    </td>
                  </tr>
                </tbody>
              </table>
              <div v-if="!filteredProblems.length" class="empty-state">
                没有找到匹配的题目，可以换个关键词或专题。
              </div>
            </div>
            <div class="pagination">
              <span>共 {{ filteredProblems.length }} 道题目</span>
              <div>
                <button
                  class="icon-button"
                  aria-label="上一页"
                  :disabled="libraryPage <= 1"
                  @click="libraryPage--"
                >
                  <ChevronLeft :size="17" /></button
                ><span>{{ libraryPage }} / {{ pageCount }}</span
                ><button
                  class="icon-button"
                  aria-label="下一页"
                  :disabled="libraryPage >= pageCount"
                  @click="libraryPage++"
                >
                  <ChevronRight :size="17" />
                </button>
              </div>
            </div>
          </section>
          <p class="below-note">预计用时用于排期。洛谷阶段标签由人工整理，题目详情以原平台为准。</p>
        </template>

        <template v-if="page === 'review'">
          <div class="page-heading">
            <div>
              <h1>把做过的题，变成自己的经验。</h1>
              <p>记录思路、卡点与实际用时，让下一次训练更有方向。</p>
            </div>
            <button v-if="plan" class="button primary" @click="modal = 'adjust'">
              <Sparkles :size="16" />根据复盘调整
            </button>
          </div>
          <div class="review-layout">
            <section class="panel review-panel">
              <div class="panel-heading">
                <h2>
                  训练复盘<span class="count-label">{{ recentFeedback.length }}</span>
                </h2>
                <span class="muted small-text">最近记录在前</span>
              </div>
              <article v-for="item in recentFeedback" :key="item.id" class="review-entry">
                <div class="review-status" :class="item.outcome">
                  <CheckCircle2 v-if="item.outcome === 'independent'" :size="19" /><BookOpen
                    v-else-if="item.outcome === 'editorial'"
                    :size="19"
                  /><CircleHelp v-else :size="19" />
                </div>
                <div>
                  <div class="review-title">
                    <h3>{{ problemMap[item.problemId]?.title || item.problemId }}</h3>
                    <span class="status-label" :class="item.outcome">{{
                      item.outcome === 'independent'
                        ? '独立完成'
                        : item.outcome === 'editorial'
                          ? '参考题解'
                          : '仍在探索'
                    }}</span>
                  </div>
                  <p>{{ item.note || '这次记录了用时和完成情况。下次可以补充思路与卡点。' }}</p>
                  <div v-if="item.causes && item.causes.length" class="cause-tags">
                    <span v-for="cause in item.causes" :key="cause">{{ cause }}</span>
                  </div>
                  <div class="task-meta">
                    <span>{{ problemMap[item.problemId]?.code }}</span
                    ><span class="icon-text"><Clock3 :size="12" />{{ item.minutes }} 分钟</span
                    ><time>{{ formatDate(item.createdAt) }}</time>
                  </div>
                </div>
              </article>
              <div v-if="!recentFeedback.length" class="empty-state">
                完成一次练习后，在周计划点击“写复盘”，留下第一条记录。
              </div>
            </section>
            <aside class="panel review-summary">
              <h2>这段训练留下了什么</h2>
              <div class="review-number">
                <strong>{{ trainedMinutes }}</strong
                ><span>分钟训练记录</span>
              </div>
              <div class="summary-line">
                <span>独立完成</span
                ><strong
                  >{{
                    recentFeedback.filter((item) => item.outcome === 'independent').length
                  }}
                  次</strong
                >
              </div>
              <div class="summary-line">
                <span>参考题解</span
                ><strong
                  >{{
                    recentFeedback.filter((item) => item.outcome === 'editorial').length
                  }}
                  次</strong
                >
              </div>
              <div class="summary-line">
                <span>还需探索</span
                ><strong
                  >{{ recentFeedback.filter((item) => item.outcome === 'stuck').length }} 次</strong
                >
              </div>
              <p class="panel-note">每次任务保留最新复盘。撤销计划调整时，已有复盘仍会保留。</p>
            </aside>
          </div>
          <div>
            <MemoryPanel
              :observations="memoryData.observations"
              :events="memoryData.events"
              :busy="memoryBusy"
              @correct="correctMemory"
              @remove="removeMemory"
              @add="addMemory"
              @refresh="loadMemory"
            />
          </div>
        </template>

        <template v-if="page === 'ability'">
          <AbilityAssessment :model="profileModel" :loading="profileLoading" :error="profileError" :demo="state.demo"
            @refresh="loadProfileModel" @open-plan="navigate('plan')" />
        </template>

        <template v-if="page === 'code'">
          <CodeWorkspace v-if="!state.demo" :key="state.username" :ai-configured="state.aiConfigured"
            @notify="notify" @refresh="loadMemory" />
          <p v-else class="notice">登录个人账号后可选择本地目录，使用私人代码工作区和教练对话。</p>
        </template>

        <template v-if="page === 'settings' && draft">
          <div class="page-heading">
            <div>
              <h1>让计划，贴合你的节奏。</h1>
              <p>从实际能投入的时间开始，留一点空间给复盘。</p>
            </div>
            <button class="button primary" :disabled="!!busy" form="profile-form" type="submit">
              <Check :size="16" />{{ busy === 'profile' ? '正在保存' : '保存设置' }}
            </button>
          </div>
          <form id="profile-form" class="settings-layout" @submit.prevent="saveProfile">
            <div>
              <section class="panel form-panel">
                <h2>训练目标</h2>
                <label>昵称<input v-model="draft.name" required maxlength="30" /></label
                ><label
                  >这段时间想达到什么目标<textarea
                    v-model="draft.goal"
                    maxlength="300"
                    rows="3"
                    placeholder="例如：巩固二分，能独立完成 1200 分左右的题目"
                  /></label
                ><label
                  >训练起点 <span class="field-hint">CF 难度参考</span
                  ><select v-model.number="draft.level">
                    <option
                      v-for="level in [800, 1000, 1200, 1400, 1600]"
                      :key="level"
                      :value="level"
                    >
                      {{ level }}
                    </option>
                  </select></label
                >
                <div class="field-label">想练习的专题</div>
                <div class="topic-options">
                  <button
                    v-for="name in topics"
                    :key="name"
                    type="button"
                    :class="{ chosen: draft.topics.includes(name) }"
                    :aria-pressed="draft.topics.includes(name)"
                    @click="toggleTopic(name)"
                  >
                    <Check v-if="draft.topics.includes(name)" :size="13" />{{ name }}
                  </button>
                </div>
                <label>目标比赛日期<input v-model="draft.competitionDate" type="date" /></label>
                <label>荣誉与参赛经历<textarea v-model="draft.achievements" rows="3" maxlength="800"
                  placeholder="可填写比赛名称、名次，以及你在个人赛或团队赛中的经历" />
                  <span class="field-hint">自填背景供教练参考，能力等级仍依据训练记录。</span>
                </label>
              </section>
              <section class="panel form-panel">
                <h2>每周可用时间</h2>
                <p class="panel-note">
                  每天填写 0～480 分钟，0 表示当天休息。排期会从本周剩余日期开始。
                </p>
                <div class="time-inputs">
                  <label v-for="(day, index) in weekdays" :key="day"
                    >{{ day
                    }}<input
                      v-model.number="draft.dailyMinutes[index]"
                      type="number"
                      min="0"
                      max="480"
                      required
                    /><span>分钟</span></label
                  >
                </div>
              </section>
            </div>
            <aside>
              <section class="panel form-panel connection-panel">
                <div class="panel-heading">
                  <h2>连接 Codeforces</h2>
                  <Link2 :size="18" />
                </div>
                <label
                  >CF handle<input
                    v-model.trim="draft.handle"
                    maxlength="24"
                    placeholder="填写你的公开用户名"
                    autocomplete="off"
                /></label>
                <p class="panel-note">
                  保存后同步公开记录。填写 handle 用于读取数据，账号归属尚需自行确认。
                </p>
                <button
                  class="button outline full-width"
                  type="button"
                  :disabled="!!busy || syncJob.status === 'running'"
                  @click="synchronize"
                >
                  <RefreshCw :size="15" :class="{ spinning: syncJob.status === 'running' }" />{{
                    syncJob.status === 'running' ? '正在同步' : '同步已保存的 handle'
                  }}
                </button>
                <p v-if="syncJob.status !== 'idle'" class="sync-message" role="status">
                  {{ syncJob.message }}
                </p>
                <div v-if="workspace!.sync.syncedAt" class="sync-details">
                  <span>上次同步 {{ workspace!.sync.handle }}</span
                  ><strong
                    >{{ workspace!.sync.submissionCount }} 条提交 /
                    {{ workspace!.sync.solvedCount }} 道通过</strong
                  ><small
                    >{{ formatDate(workspace!.sync.syncedAt)
                    }}{{ workspace!.sync.truncated ? ' · 最近 10000 条范围' : '' }}</small
                  >
                </div>
              </section>
              <section class="panel form-panel">
                <h2>训练教练</h2>
                <div class="engine-status">
                  <span class="live-dot" />{{
                    state.aiConfigured ? '模型服务已配置' : '规则教练已启用'
                  }}
                </div>
                <p class="panel-note">
                  {{
                    state.aiConfigured
                      ? '模型生成后，会检查题目来源与时间预算。调用失败时使用规则方案。'
                      : '当前按专题、难度、可用时间与反馈安排任务。'
                  }}
                </p>
                <p v-if="state.demo" class="panel-note">登录个人账号后可配置模型服务。</p>
                <template v-else>
                  <label>
                    服务地址
                    <input v-model.trim="aiDraft.baseUrl" maxlength="500"
                      placeholder="https://api.example.com/v1" autocomplete="off" spellcheck="false"
                      @keydown.enter.prevent="saveAiSettings" />
                  </label>
                  <label>
                    模型名称
                    <input v-model.trim="aiDraft.model" maxlength="160"
                      placeholder="填写服务商提供的模型 ID" autocomplete="off" spellcheck="false"
                      @keydown.enter.prevent="saveAiSettings" />
                  </label>
                  <label>
                    API Key
                    <input v-model.trim="aiDraft.apiKey" type="password" maxlength="1024"
                      :placeholder="state.aiSettings.hasLocalKey ? '已保存，留空保留原 Key' : '输入 API Key'"
                      autocomplete="new-password" spellcheck="false" @keydown.enter.prevent="saveAiSettings" />
                  </label>
                  <p v-if="state.aiSettings.unreadable" class="panel-note">本机配置无法读取，请重新填写或清除。</p>
                  <p class="panel-note">
                    Key 按账号加密保存在本机，保存后无法在页面查看。生成或调整周计划时，相关训练上下文会发给该服务，Key 用于认证。目录分析开启 AI 后会发送统计和代表证据。
                  </p>
                  <div class="ai-settings-actions">
                    <button class="button primary" type="button" :disabled="!!busy" @click="saveAiSettings">
                      {{ busy === 'ai-save' ? '正在保存' : '保存模型配置' }}
                    </button>
                    <button v-if="state.aiConfigured" class="button outline" type="button"
                      :disabled="!!busy" @click="testAiSettings">
                      {{ busy === 'ai-test' ? '正在测试' : '测试连接' }}
                    </button>
                    <button v-if="state.aiSettings.hasLocalKey || state.aiSettings.unreadable" class="button outline"
                      type="button" :disabled="!!busy" @click="clearAiSettings">
                      清除本机配置
                    </button>
                  </div>
                </template>
              </section>
              <div class="source-note">
                <Library :size="17" />
                <p>洛谷使用精选题链接，完成情况由你手动记录。</p>
              </div>
            </aside>
          </form>
        </template>
        <footer class="page-footer">
          <span>循练 · 每一次练习，都有下一步</span
          ><span>课程项目 v0.1 <span v-if="state.demo"> / 演示数据</span></span>
        </footer>
      </main>
    </div>

    <div v-if="modal" class="modal-backdrop" @click="closeModal">
      <section
        class="modal"
        :class="{ 'wide-modal': modal === 'adjust' }"
        role="dialog"
        aria-modal="true"
        :aria-label="
          modal === 'feedback'
            ? '记录训练复盘'
            : modal === 'adjust'
              ? '调整训练计划'
              : '进入训练空间'
        "
      >
        <button
          class="modal-close icon-button"
          :disabled="!!busy"
          aria-label="关闭弹窗"
          @click="closeModal()"
        >
          <X :size="20" />
        </button>
        <template v-if="modal === 'feedback' && activeTask"
          ><span class="modal-symbol"><NotebookPen :size="24" /></span>
          <h2>这次练习，留下了什么？</h2>
          <p class="modal-subtitle">{{ problemMap[activeTask.problemId]?.title }}</p>
          <form @submit.prevent="saveFeedback">
            <div class="field-label">完成情况</div>
            <div class="outcome-options">
              <label
                v-for="option in [
                  { id: 'independent', label: '独立完成' },
                  { id: 'editorial', label: '参考题解' },
                  { id: 'stuck', label: '仍在探索' },
                ]"
                :key="option.id"
                :class="{ chosen: feedback.outcome === option.id }"
                ><input
                  v-model="feedback.outcome"
                  type="radio"
                  :value="option.id"
                  name="outcome"
                />{{ option.label }}</label
              >
            </div>
            <label
              >实际训练用时（分钟）<input
                v-model.number="feedback.minutes"
                type="number"
                min="1"
                max="600"
                required /></label
            ><label
              >思路、卡点或下一次要注意的事<textarea
                v-model="feedback.note"
                rows="4"
                maxlength="1500"
                placeholder="例如：二分方向能判断，但左右边界还需要再练习。"
              /></label
            >
            <label>本次实际失误<textarea v-model="feedback.mistakeNote" rows="2" maxlength="400"
              placeholder="例如：多组测试忘了清空数组。没有失误可以留空。" /></label>
            <label>题解要点（选填）<textarea v-model="feedback.editorialNotes" rows="2" maxlength="1200"
              placeholder="题目标签太宽时，可粘贴题解里的算法名称或做法。" /></label>
            <button class="button primary full-width" :disabled="!!busy" type="submit">
              {{ busy === 'feedback' ? '正在保存' : '保存这次复盘' }}<Check :size="16" />
            </button></form
        ></template>
        <template v-if="modal === 'adjust'"
          ><span class="modal-symbol"><Sparkles :size="24" /></span>
          <h2>让下一步，更适合现在的你。</h2>
          <p class="modal-subtitle">
            {{
              state.aiConfigured
                ? '结合训练反馈生成方案，确认后应用。'
                : '当前使用规则教练，根据反馈与时间条件生成方案。'
            }}
          </p>
          <form @submit.prevent="propose">
            <label
              >这次想怎么调整<textarea
                v-model="instruction"
                rows="3"
                maxlength="1500"
                placeholder="例如：周六只有30分钟。最近二分参考了题解，想再独立练习。"
              />
            </label>
            <p class="field-hint">
              规则模式支持“周六30分钟”“周四没时间”“每天30分钟”等时间条件，专题巩固依据已保存的复盘。
            </p>
            <button class="button outline full-width" :disabled="!!busy" type="submit">
              <LoaderCircle v-if="busy === 'adjust'" :size="16" class="spinning" /><Sparkles
                v-else
                :size="16"
              />{{ busy === 'adjust' ? '正在生成方案' : '生成调整方案' }}
            </button>
          </form>
          <div v-if="workspace!.proposal" class="proposal">
            <div class="proposal-heading">
              <strong>调整方案</strong
              ><span class="quiet-badge">{{
                workspace!.proposal.engine === 'model' ? '模型生成' : '规则生成'
              }}</span>
            </div>
            <p>{{ workspace!.proposal.reason }}</p>
            <p v-if="workspace!.proposal.plan.timeEcho" class="proposal-time">
              时间解析：{{ workspace!.proposal.plan.timeEcho }}
            </p>
            <ul
              v-if="workspace!.proposal.basis && workspace!.proposal.basis.length"
              class="basis-list"
            >
              <li v-for="(item, index) in workspace!.proposal.basis" :key="index">
                <span>依据</span>{{ item }}
              </li>
            </ul>
            <div class="proposal-comparison">
              <div>
                <small>当前计划</small
                ><strong
                  >{{ tasks.filter((task) => task.status === 'todo').length }} 道待做题</strong
                ><span>{{ scheduledMinutes }} 分钟总安排</span>
              </div>
              <ArrowRight :size="20" />
              <div>
                <small>调整之后</small
                ><strong
                  >{{
                    workspace!.proposal.plan.tasks.filter((task) => task.status === 'todo').length
                  }}
                  道待做题</strong
                ><span
                  >{{
                    workspace!.proposal.plan.tasks.reduce((sum, task) => sum + task.minutes, 0)
                  }}
                  分钟总安排</span
                >
              </div>
            </div>
            <ul class="proposal-tasks">
              <li
                v-for="task in workspace!.proposal.plan.tasks.filter(
                  (task) => task.status === 'todo',
                )"
                :key="task.id"
              >
                <span>{{ weekdays[task.day] }}</span
                ><strong>{{ problemMap[task.problemId]?.title }}</strong
                ><small>{{ task.minutes }} 分钟</small>
              </li>
            </ul>
            <p class="panel-note">{{ workspace!.proposal.modelMessage }}</p>
            <button class="button primary full-width" :disabled="!!busy" @click="acceptProposal">
              应用这个方案<Check :size="16" />
            </button></div
        ></template>
        <template v-if="modal === 'auth'"
          ><span class="modal-symbol"><LogIn :size="24" /></span>
          <h2>进入你的训练空间。</h2>
          <p class="modal-subtitle">创建独立账号，保存自己的目标、计划与复盘。</p>
          <div class="auth-tabs">
            <button :class="{ active: authMode === 'register' }" @click="authMode = 'register'">
              创建账号</button
            ><button :class="{ active: authMode === 'login' }" @click="authMode = 'login'">
              已有账号
            </button>
          </div>
          <form @submit.prevent="submitAuth">
            <label
              >用户名<input
                v-model.trim="credentials.username"
                required
                pattern="[A-Za-z0-9_]{3,24}"
                placeholder="3～24 位字母、数字或下划线"
                autocomplete="username" /></label
            ><label
              >密码<input
                v-model="credentials.password"
                required
                minlength="8"
                maxlength="72"
                type="password"
                placeholder="至少 8 个字符"
                :autocomplete="
                  authMode === 'register' ? 'new-password' : 'current-password'
                " /></label
            ><button class="button primary full-width" :disabled="!!busy" type="submit">
              {{ authMode === 'register' ? '创建我的空间' : '登录训练空间'
              }}<ArrowRight :size="16" />
            </button>
          </form>
          <button class="text-button demo-entry" :disabled="!!busy" @click="enterDemo">
            进入课程演示空间<ArrowUpRight :size="14" /></button
        ></template>
      </section>
    </div>
    <div v-if="toast" class="toast" :class="{ 'toast-error': toast.error }" role="status">
      <CircleHelp v-if="toast.error" :size="19" /><CheckCircle2 v-else :size="19" /><span>{{
        toast.text
      }}</span
      ><button aria-label="关闭提示" @click="toast = null"><X :size="16" /></button>
    </div>
  </div>
</template>
