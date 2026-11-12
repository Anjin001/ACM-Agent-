<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import { Search, ArrowUpRight, ChevronLeft, ChevronRight, RefreshCw, Check } from 'lucide-vue-next'
import { api } from '../api'
import type { Topic, ProblemPage } from '../types'

const props = defineProps<{
  topics: Topic[]
  revision: number
  acceptedOnly?: boolean
  initialTopic?: string
}>()
const query = ref('')
const platform = ref('all')
const topic = ref(props.initialTopic || 'all')
const completion = ref('all')
const minRating = ref(0)
const maxRating = ref(0)
const page = ref(1)
const result = ref<ProblemPage>({ items: [], total: 0, page: 1, pageSize: 20, pages: 1 })
const loading = ref(false)
const error = ref('')
const groups = computed(() => [...new Set(props.topics.map((item) => item.category))])
let serial = 0
let timer: ReturnType<typeof setTimeout>
const ratings = Array.from({ length: 28 }, (_, i) => 800 + i * 100)

async function fetchProblems() {
  const request = ++serial
  loading.value = true
  error.value = ''
  const params = new URLSearchParams({
    q: query.value,
    topic: topic.value,
    page: String(page.value),
    pageSize: '20',
  })
  if (!props.acceptedOnly) {
    params.set('platform', platform.value)
    params.set('completion', completion.value)
    params.set('minRating', String(minRating.value))
    params.set('maxRating', String(maxRating.value))
  }
  try {
    const data = await api<ProblemPage>(
      `${props.acceptedOnly ? '/cf/solved' : '/problems'}?${params}`,
    )
    if (request === serial) result.value = data
  } catch (failure) {
    if (request === serial)
      error.value = failure instanceof Error ? failure.message : '题目读取失败，请重试'
  } finally {
    if (request === serial) loading.value = false
  }
}
watch([query, platform, topic, completion, minRating, maxRating], () => {
  page.value = 1
  clearTimeout(timer)
  timer = setTimeout(fetchProblems, 200)
})
watch(
  () => props.initialTopic,
  (value) => {
    topic.value = value || 'all'
  },
)
watch(() => props.revision, fetchProblems)
watch(page, fetchProblems)
fetchProblems()
onUnmounted(() => {
  clearTimeout(timer)
  serial++
})
const labels: Record<string, string> = {
  cf: 'CF 已通过',
  manual: '手动完成',
  attempted: '有尝试记录',
  todo: '未完成',
}
function acceptedDate(seconds: number) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(seconds * 1000))
}
</script>

<template>
  <section class="panel library-panel">
    <div class="library-filters extended-filters">
      <label class="search-box"
        ><Search :size="18" /><input
          v-model="query"
          placeholder="搜索题号、名称、英文标签"
          aria-label="搜索题目"
      /></label>
      <select v-if="!acceptedOnly" v-model="platform" aria-label="筛选平台">
        <option value="all">全部平台</option>
        <option value="CF">Codeforces</option>
        <option value="LG">洛谷</option>
      </select>
      <select v-model="topic" aria-label="筛选专题">
        <option value="all">全部专题</option>
        <optgroup v-for="group in groups" :key="group" :label="group">
          <option
            v-for="item in topics.filter((t) => t.category === group)"
            :key="item.name"
            :value="item.name"
          >
            {{ item.name }}
          </option>
        </optgroup>
      </select>
      <select v-if="!acceptedOnly" v-model="completion" aria-label="筛选完成状态">
        <option value="all">全部状态</option>
        <option value="unsolved">未完成</option>
        <option value="solved">已完成</option>
        <option value="attempted">尝试中</option>
      </select>
    </div>
    <div v-if="!acceptedOnly" class="rating-filters">
      <span>CF 难度</span
      ><select v-model.number="minRating" aria-label="最低难度">
        <option :value="0">不限下限</option>
        <option v-for="rating in ratings" :key="rating" :value="rating">
          {{ rating }}
        </option></select
      ><span>至</span
      ><select v-model.number="maxRating" aria-label="最高难度">
        <option :value="0">不限上限</option>
        <option v-for="rating in ratings" :key="rating" :value="rating">
          {{ rating }}
        </option></select
      ><span class="filter-help">选择难度范围后仅展示有评分的 CF 题目</span>
    </div>
    <div v-if="error" class="notice error" role="alert">
      {{ error }}<button class="text-button" @click="fetchProblems">重试</button>
    </div>
    <div class="table-scroll" :aria-busy="loading">
      <table>
        <thead>
          <tr>
            <th>题目</th>
            <th>算法专题</th>
            <th>{{ acceptedOnly ? '最近通过' : '原平台难度' }}</th>
            <th>状态</th>
            <th>前往练习</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in result.items" :key="row.problem.id">
            <td>
              <div class="problem-name">
                <span class="platform-badge" :class="row.problem.platform">{{
                  row.problem.platform === 'LG' ? '洛谷' : 'CF'
                }}</span
                ><a :href="row.problem.url" target="_blank" rel="noopener noreferrer"
                  ><small>{{ row.problem.code }}</small
                  ><strong>{{ row.problem.title }}</strong></a
                >
              </div>
            </td>
            <td>
              <div class="tag-list">
                <span v-for="tag in row.problem.tags.slice(0, 3)" :key="tag" class="tag">{{
                  tag
                }}</span
                ><span v-if="!row.problem.tags.length" class="muted">原平台标签待补充</span>
              </div>
            </td>
            <td :data-label="acceptedOnly ? '最近通过' : '原平台难度'">
              <span v-if="acceptedOnly && row.lastAcceptedAt" class="muted">{{
                acceptedDate(row.lastAcceptedAt)
              }}</span
              ><span
                v-else
                class="rating"
                :class="{ beginner: !row.problem.rating || row.problem.rating <= 1200 }"
                >{{ row.problem.difficulty }}</span
              >
            </td>
            <td>
              <span class="completion-state" :class="row.completion"
                ><Check v-if="['cf', 'manual'].includes(row.completion)" :size="13" />{{
                  labels[row.completion]
                }}</span
              >
            </td>
            <td>
              <a
                class="button outline problem-open"
                :href="row.problem.url"
                target="_blank"
                rel="noopener noreferrer"
                :aria-label="`打开原题 ${row.problem.code}`"
                >打开原题<ArrowUpRight :size="14"
              /></a>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!result.items.length && !loading && !error" class="empty-state">
        {{
          acceptedOnly
            ? '还没有匹配的已通过题目。先同步 CF 记录，或调整筛选条件。'
            : '没有匹配的题目，可以调整关键词、专题或难度范围。'
        }}
      </div>
    </div>
    <div class="pagination">
      <span
        ><RefreshCw v-if="loading" :size="12" class="spinning" />{{
          loading ? '正在读取…' : `共 ${result.total.toLocaleString()} 道题目`
        }}</span
      >
      <div>
        <button
          class="icon-button"
          aria-label="上一页"
          :disabled="loading || result.page <= 1"
          @click="page = result.page - 1"
        >
          <ChevronLeft :size="17" /></button
        ><span>{{ result.page }} / {{ result.pages }}</span
        ><button
          class="icon-button"
          aria-label="下一页"
          :disabled="loading || result.page >= result.pages"
          @click="page = result.page + 1"
        >
          <ChevronRight :size="17" />
        </button>
      </div>
    </div>
  </section>
</template>
