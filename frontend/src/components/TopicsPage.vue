<script setup lang="ts">
import { computed, ref } from 'vue'
import { Search, ChevronRight, BookOpen, ArrowRight, Plus, Check } from 'lucide-vue-next'
import ProblemLibrary from './ProblemLibrary.vue'
import type { AppState } from '../types'
const props = defineProps<{ state: AppState; busy: string }>()
const emit = defineEmits<{
  add: [name: string]
  route: [names: string[]]
  browse: [name: string]
}>()
const group = ref('全部')
const query = ref('')
const selected = ref('二分')
const groups = computed(() => [
  '全部',
  ...new Set(props.state.topics.map((topic) => topic.category)),
])
const topics = computed(() =>
  props.state.topics.filter(
    (topic) =>
      (group.value === '全部' || topic.category === group.value) &&
      `${topic.name} ${topic.summary}`.toLowerCase().includes(query.value.toLowerCase()),
  ),
)
const detail = computed(
  () => props.state.topics.find((topic) => topic.name === selected.value) || props.state.topics[0],
)
const routes = [
  {
    name: '基础起步',
    text: '从复杂度、模拟与排序开始，逐步进入二分和搜索。',
    topics: ['复杂度分析', '模拟', '枚举', '排序', '前缀和与差分', '二分', '搜索'],
  },
  {
    name: '算法进阶',
    text: '在基本建模之上，练习图论、数据结构与动态规划。',
    topics: ['动态规划', '数据结构', '图论', '数学', '字符串'],
  },
  {
    name: '专题突破',
    text: '结合前置知识，选择树、图和字符串的细分算法。',
    topics: ['树链剖分', '强连通分量', '网络流', '数位DP', '后缀数组'],
  },
]
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>把算法专题，连成学习路线。</h1>
      <p>{{ state.topics.length }} 个专题，按前置知识、学习要点与对应练习组织。</p>
    </div>
  </div>
  <div class="learning-routes">
    <article v-for="route in routes" :key="route.name" class="panel route-card">
      <BookOpen :size="21" />
      <h2>{{ route.name }}</h2>
      <p>{{ route.text }}</p>
      <button class="text-button" :disabled="!!busy" @click="emit('route', route.topics)">
        设为训练方向<ArrowRight :size="14" />
      </button>
    </article>
  </div>
  <section class="panel topic-directory">
    <div class="topic-group-tabs">
      <button
        v-for="name in groups"
        :key="name"
        :class="{ active: group === name }"
        @click="group = name"
      >
        {{ name }}
      </button>
    </div>
    <label class="search-box"
      ><Search :size="17" /><input
        v-model="query"
        aria-label="搜索算法专题"
        placeholder="搜索算法名称或学习内容"
    /></label>
    <div class="topic-directory-grid">
      <button
        v-for="topic in topics"
        :key="topic.name"
        :class="{ active: selected === topic.name }"
        @click="selected = topic.name"
      >
        <span
          >{{ topic.name }}<small>{{ topic.category }}</small></span
        ><ChevronRight :size="14" />
      </button>
    </div>
    <div v-if="!topics.length" class="empty-state">没有匹配的专题，请调整关键词。</div>
  </section>
  <section v-if="detail" class="panel topic-detail">
    <div class="panel-heading">
      <div>
        <span class="quiet-badge">{{ detail.category }}</span>
        <h2>{{ detail.name }}</h2>
      </div>
      <div class="heading-actions">
        <button class="button outline" @click="emit('browse', detail.name)">
          查看全部练习<ArrowRight :size="15" /></button
        ><button
          class="button primary"
          :disabled="!!busy || state.workspace.profile.topics.includes(detail.name)"
          @click="emit('add', detail.name)"
        >
          <Check v-if="state.workspace.profile.topics.includes(detail.name)" :size="15" /><Plus
            v-else
            :size="15"
          />{{
            state.workspace.profile.topics.includes(detail.name) ? '已加入训练' : '加入训练专题'
          }}
        </button>
      </div>
    </div>
    <p>{{ detail.summary }}</p>
    <div class="prerequisites">
      <span>建议先学习</span
      ><button
        v-for="name in detail.prerequisites"
        :key="name"
        class="tag"
        @click="selected = name"
      >
        {{ name }}</button
      ><span v-if="!detail.prerequisites.length">可从这里开始</span>
    </div>
    <div class="study-checklist">
      <h3>练习时检查这些问题</h3>
      <ul>
        <li v-for="step in detail.steps" :key="step">{{ step }}</li>
      </ul>
    </div>
  </section>
  <div v-if="detail" class="section-heading">
    <h2>{{ detail.name }} · 对应练习</h2>
    <span>细分算法由人工精选标注，CF 标签按原平台信息映射</span>
  </div>
  <ProblemLibrary
    v-if="detail"
    :topics="state.topics"
    :revision="state.revision"
    :initial-topic="detail.name"
  />
</template>
