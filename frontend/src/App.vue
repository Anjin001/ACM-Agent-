<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import {
  LayoutDashboard,
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
} from 'lucide-vue-next'
import TaskList from './components/TaskList.vue'
import ProblemLibrary from './components/ProblemLibrary.vue'
import TopicsPage from './components/TopicsPage.vue'
import CfSyncPage from './components/CfSyncPage.vue'
import TopicPicker from './components/TopicPicker.vue'
import LearningPage from './components/LearningPage.vue'
import { api, ApiError } from './api'
import type { AppState, Profile, Task, SyncJob, Problem } from './types'

type Page = 'dashboard' | 'plan' | 'library' | 'review' | 'settings' | 'topics' | 'cf' | 'learn'
const navigation = [
  { id: 'dashboard' as Page, label: '训练概览', icon: LayoutDashboard },
  { id: 'plan' as Page, label: '周训练计划', icon: CalendarDays },
  { id: 'learn' as Page, label: '诊断与验证', icon: Target },
  { id: 'topics' as Page, label: '专题与路线', icon: BookOpen },
  { id: 'library' as Page, label: '训练题库', icon: Library },
  { id: 'cf' as Page, label: 'CF 已做题', icon: Link2 },
  { id: 'review' as Page, label: '训练复盘', icon: NotebookPen },
]
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const state = ref<AppState | null>(null)
const topics = computed(() => state.value?.topics || [])
const libraryTopic = ref('all')
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
const feedback = ref({ outcome: 'independent', minutes: 40, note: '' })
const instruction = ref('针对最近参考题解才完成的题，再安排一次独立练习。')
const authMode = ref<'login' | 'register'>('register')
const credentials = ref({ username: '', password: '' })
const draft = ref<Profile | null>(null)
const selectedDay = ref(-1)
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
const currentPlanWeek = computed(() => {
  if (!plan.value) return true
  const monday = new Date()
  monday.setDate(monday.getDate() - todayIndex)
  return (
    plan.value.weekStart ===
    [
      monday.getFullYear(),
      String(monday.getMonth() + 1).padStart(2, '0'),
      String(monday.getDate()).padStart(2, '0'),
    ].join('-')
  )
})
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
const syncJob = ref<SyncJob>({
  status: 'idle',
  message: '尚未同步',
  handle: '',
  mode: 'full',
  pages: 0,
  readCount: 0,
})
const visibleTasks = computed(() =>
  selectedDay.value < 0
    ? tasks.value
    : tasks.value.filter((task) => task.day === selectedDay.value),
)
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
  return ['dashboard', 'plan', 'library', 'review', 'settings', 'topics', 'cf', 'learn'].includes(
    hash,
  )
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
    notify('本周计划已生成')
  })
}
function openFeedback(task: Task) {
  activeTask.value = task
  const previous = workspace.value?.feedback.find(
    (item) => item.taskId === task.id && item.problemId === task.problemId,
  )
  feedback.value = previous
    ? { outcome: previous.outcome, minutes: previous.minutes, note: previous.note }
    : { outcome: 'independent', minutes: task.minutes, note: '' }
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
function synchronize() {
  syncCf('incremental')
}
function syncCf(mode: string) {
  if (!workspace.value?.profile.handle) {
    navigate('cf')
    notify('填写 CF handle 后即可同步已通过题目')
    return
  }
  act('sync', async () => {
    syncJob.value = await api<SyncJob>('/sync', 'POST', { mode })
  })
}
function connectCf(handle: string) {
  act('sync', async () => {
    const profile = { ...workspace.value!.profile, handle }
    adopt(await api<AppState>('/profile', 'PUT', { revision: state.value!.revision, profile }))
    syncJob.value = await api<SyncJob>('/sync', 'POST', { mode: 'incremental' })
  })
}
function cancelCf() {
  act('cancel', async () => {
    syncJob.value = await api<SyncJob>('/sync/cancel', 'POST')
    if (syncJob.value.status === 'success') adopt(await api<AppState>('/state'))
  })
}
function setTopics(names: string[]) {
  act('topics', async () => {
    const profile = { ...workspace.value!.profile, topics: [...new Set(names)] }
    adopt(await api<AppState>('/profile', 'PUT', { revision: state.value!.revision, profile }))
    notify('训练方向已保存，可在周计划中生成新的安排')
  })
}
function adoptLearning(value: AppState) {
  if (value.username === state.value?.username) adopt(value)
}
function addTopic(name: string) {
  setTopics([...workspace.value!.profile.topics, name])
}
function browseTopic(name: string) {
  libraryTopic.value = name
  navigate('library')
}
async function pollSync() {
  const username = state.value?.username
  const handle = workspace.value?.profile.handle
  if (syncJob.value.status !== 'running' || polling) return
  polling = true
  try {
    const job = await api<SyncJob>('/sync')
    if (state.value?.username !== username || workspace.value?.profile.handle !== handle) return
    syncJob.value = job
    if (job.status !== 'running') {
      const value = await api<AppState>('/state')
      if (value.username === state.value?.username) adopt(value, page.value !== 'settings')
      notify(job.message, job.status === 'error')
    }
  } catch {
    syncJob.value = { ...syncJob.value, status: 'error', message: '同步状态获取失败，请刷新后重试' }
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
  load()
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
        ><span class="brand-name">循练<span>编程与算法成长</span></span></a
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
          <span class="avatar">{{ workspace!.profile.name.slice(0, 1) }}</span><span
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
          }}</span><button class="connection-pill" @click="navigate('settings')">
            <span class="live-dot" />{{
              workspace!.sync.syncedAt &&
              workspace!.sync.handle.toLowerCase() === workspace!.profile.handle.toLowerCase()
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
          <div v-if="plan && !currentPlanWeek" class="notice">
            当前展示的是 {{ weekLabel }} 的计划。<button
              class="text-button"
              :disabled="!!busy"
              @click="generate"
            >
              生成本周安排
            </button>
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
                  ><small>计划已完成</small>
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
                ><small>当前计划完成</small
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
                    workspace!.sync.handle.toLowerCase() === workspace!.profile.handle.toLowerCase()
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
                    :class="{
                      today: index === todayIndex && currentPlanWeek,
                      selected: selectedDay === index,
                    }"
                    @click="selectedDay = selectedDay === index ? -1 : index"
                  >
                    <span>{{
                      index === todayIndex && currentPlanWeek ? '今天' : day
                    }}</span><strong>{{ dayDate(index).getDate() }}</strong>
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
                    }}</span><span>{{ nextProblem.code }}</span
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
                    <span>{{ item.name }}</span><small
                      >{{ item.independent }} 独立 / {{ item.attempted }} 次练习</small
                    >
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
          <div class="plan-layout">
            <section
              v-for="(day, index) in weekdays"
              :key="day"
              class="panel plan-day"
              :class="{ 'is-today': index === todayIndex && currentPlanWeek }"
            >
              <div class="plan-day-heading">
                <div>
                  <span>{{ day }}</span>
                  <h2>
                    {{ dayDate(index).getMonth() + 1 }}.{{
                      String(dayDate(index).getDate()).padStart(2, '0')
                    }}<small v-if="index === todayIndex && currentPlanWeek">今天</small>
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
              <h1>找到适合下一步的题目。</h1>
              <p>
                {{ state.catalogTotal.toLocaleString() }} 道题目，支持专题、难度与完成状态筛选。
              </p>
            </div>
            <button class="button outline" @click="navigate('cf')">
              <RefreshCw :size="15" />同步 CF 已做题
            </button>
          </div>
          <ProblemLibrary
            :topics="topics"
            :revision="state.revision"
            :initial-topic="libraryTopic"
          />
          <p class="below-note">
            CF 题目来自官方题库快照。洛谷题目为人工精选，名称为训练主题，题目难度与内容以原题为准。
          </p>
        </template>
        <LearningPage
          v-if="page === 'learn'"
          :state="state"
          @update="adoptLearning"
          @route="setTopics"
        />
        <TopicsPage
          v-if="page === 'topics'"
          :state="state"
          :busy="busy"
          @add="addTopic"
          @route="setTopics"
          @browse="browseTopic"
        />
        <CfSyncPage
          v-if="page === 'cf'"
          :state="state"
          :job="syncJob"
          :busy="busy"
          @connect="connectCf"
          @sync="syncCf"
          @cancel="cancelCf"
        />
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
                  <div class="task-meta">
                    <span>{{ problemMap[item.problemId]?.code }}</span><span class="icon-text"
                      ><Clock3 :size="12" />{{ item.minutes }} 分钟</span
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
                <strong>{{ trainedMinutes }}</strong><span>分钟训练记录</span>
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
                      v-for="level in Array.from({ length: 28 }, (_, i) => 800 + i * 100)"
                      :key="level"
                      :value="level"
                    >
                      {{ level }}
                    </option>
                  </select></label
                >
                <div class="field-label">想练习的专题</div>
                <TopicPicker v-model="draft.topics" :topics="topics" />
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
                    }}{{
                      workspace!.sync.fullHistory
                        ? ' · 完整历史已同步'
                        : ' · 可到 CF 已做题页面完整同步'
                    }}</small
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
                      : '当前按专题、难度、可用时间与反馈安排任务。模型服务需由项目维护者在后端配置。'
                  }}
                </p>
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
            ><button class="button primary full-width" :disabled="!!busy" type="submit">
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
                <span>{{ weekdays[task.day] }}</span><strong>{{
                  problemMap[task.problemId]?.title
                }}</strong
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
