<script setup lang="ts">
import { computed, ref } from 'vue'
import type { MemoryEvent, Observation } from '../types'

const props = defineProps<{
  observations: Observation[]
  events: MemoryEvent[]
  busy?: string
}>()
const emit = defineEmits<{
  correct: [id: string, statement: string, active: boolean]
  remove: [id: string]
  add: [kind: string, statement: string]
  refresh: []
}>()

const kindLabels: Record<string, string> = {
  goal: '有效目标',
  constraint: '约束',
  error_pattern: '易错点',
  code_issue: '代码问题',
  strength: '优势',
}
const kindOrder = ['goal', 'constraint', 'error_pattern', 'code_issue', 'strength']
const editing = ref('')
const draft = ref('')
const newKind = ref('error_pattern')
const newStatement = ref('')

const grouped = computed(() =>
  kindOrder
    .map((kind) => ({
      kind,
      label: kindLabels[kind] || kind,
      items: props.observations.filter((item) => item.kind === kind),
    }))
    .filter((group) => group.items.length),
)

function startEdit(item: Observation) {
  editing.value = item.id
  draft.value = item.statement
}
function commit(item: Observation) {
  const text = draft.value.trim()
  if (!text) return
  emit('correct', item.id, text, item.active)
  editing.value = ''
}
function toggle(item: Observation) {
  emit('correct', item.id, item.statement, !item.active)
}
function addObservation() {
  const text = newStatement.value.trim()
  if (!text) return
  emit('add', newKind.value, text)
  newStatement.value = ''
}
function formatDate(value: string) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.slice(0, 10)
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(date)
}
function strength(weight: number) {
  if (weight >= 3) return '反复出现'
  if (weight >= 1.6) return '多次出现'
  return '已记录'
}
</script>

<template>
  <section class="panel mem-panel">
    <div class="mem-head">
      <div>
        <h2>教练记忆<span class="count-label">{{ observations.filter((item) => item.active).length }}</span></h2>
        <p class="mem-sub">每条观察都带来源，可以更正或停用。生成计划时会引用这些依据。</p>
      </div>
      <button class="mem-refresh" :disabled="busy === 'memory'" @click="emit('refresh')">
        刷新
      </button>
    </div>

    <p v-if="!observations.length" class="mem-empty">
      还没有沉淀观察。记录一次复盘，或在下面手动补充一条，教练下次排计划时会用上。
    </p>

    <div v-for="group in grouped" :key="group.kind" class="mem-group">
      <div class="mem-group-title">{{ group.label }}<span>{{ group.items.length }}</span></div>
      <article v-for="item in group.items" :key="item.id" class="mem-item" :class="{ off: !item.active }">
        <div class="mem-item-main">
          <template v-if="editing === item.id">
            <input v-model="draft" class="mem-input" maxlength="200" @keyup.enter="commit(item)" />
          </template>
          <p v-else class="mem-statement">{{ item.statement }}</p>
          <div class="mem-meta">
            <span class="mem-source">{{ item.sourceType === 'manual' ? '手动补充' : item.sourceType === 'feedback' ? '来自复盘' : item.sourceType === 'review' ? '来自代码审查' : item.sourceType === 'code_folder' ? '来自整目录扫描' : item.sourceType === 'code_workspace' ? '来自代码教练对话' : '来自设置' }}</span>
            <span v-if="item.detail" class="mem-detail">{{ item.detail }}</span>
            <span class="mem-weight">{{ strength(item.weight) }}</span>
          </div>
        </div>
        <div class="mem-actions">
          <template v-if="editing === item.id">
            <button @click="commit(item)">保存</button>
            <button @click="editing = ''">取消</button>
          </template>
          <template v-else>
            <button @click="startEdit(item)">更正</button>
            <button @click="toggle(item)">{{ item.active ? '停用' : '恢复' }}</button>
            <button class="danger" @click="emit('remove', item.id)">删除</button>
          </template>
        </div>
      </article>
    </div>

    <form class="mem-add" @submit.prevent="addObservation">
      <select v-model="newKind" aria-label="观察类型">
        <option value="error_pattern">易错点</option>
        <option value="goal">训练目标</option>
        <option value="constraint">约束条件</option>
        <option value="strength">优势</option>
      </select>
      <input v-model="newStatement" maxlength="200" placeholder="例如：遇到 1e9 数据规模要先想 O(n log n)" />
      <button type="submit" :disabled="!newStatement.trim()">补充</button>
    </form>

    <div v-if="events.length" class="mem-events">
      <div class="mem-group-title">训练事件<span>{{ events.length }}</span></div>
      <ol>
        <li v-for="event in events" :key="event.id">
          <span class="mem-event-date">{{ formatDate(event.createdAt) }}</span>
          <span>{{ event.summary }}</span>
        </li>
      </ol>
    </div>
  </section>
</template>

<style scoped>
.mem-panel {
  padding: 20px 22px 18px;
}
.mem-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.mem-head h2 {
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}
.mem-sub {
  margin: 5px 0 0;
  font-size: 11px;
  color: #8793a8;
  line-height: 1.7;
}
.mem-refresh {
  font-size: 10px;
  color: #61718b;
  border: 1px solid #e4eaf4;
  border-radius: 5px;
  padding: 5px 9px;
  background: #fff;
  white-space: nowrap;
  flex: none;
}
.mem-empty {
  margin: 14px 0 6px;
  font-size: 12px;
  color: #8793a8;
  line-height: 1.8;
}
.mem-group {
  margin-top: 14px;
}
.mem-group-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
  letter-spacing: 0.4px;
  color: #93a0b5;
  margin-bottom: 6px;
}
.mem-group-title span {
  color: #c1cad8;
}
.mem-item {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 8px 0;
  border-top: 1px solid #f2f6fb;
}
.mem-item.off .mem-statement {
  color: #a8b2c2;
  text-decoration: line-through;
}
.mem-item-main {
  min-width: 0;
}
.mem-statement {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: #2a3549;
}
.mem-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 3px;
  font-size: 10px;
  color: #98a5b9;
}
.mem-detail {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}
.mem-weight {
  color: #6382ce;
}
.mem-actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}
.mem-actions button {
  font-size: 10px;
  color: #61718b;
  background: #f5f8fd;
  border-radius: 4px;
  padding: 4px 7px;
}
.mem-actions button.danger {
  color: #a95a5a;
  background: #fdf4f4;
}
.mem-input {
  width: 100%;
  min-width: 180px;
  border: 1px solid #d8e3f5;
  border-radius: 5px;
  padding: 5px 8px;
  font-size: 12px;
}
.mem-add {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #f2f6fb;
}
.mem-add select,
.mem-add input {
  border: 1px solid #e4eaf4;
  border-radius: 5px;
  padding: 6px 8px;
  font-size: 11px;
  background: #fff;
  min-width: 0;
}
.mem-add select {
  max-width: 96px;
}
.mem-add input {
  flex: 1 1 160px;
}
.mem-add button {
  flex-shrink: 0;
  font-size: 11px;
  color: #fff;
  background: #3159d9;
  border-radius: 5px;
  padding: 6px 12px;
}
.mem-add button:disabled {
  background: #eef2f9;
  color: #a7b4c9;
}
.mem-events {
  margin-top: 16px;
}
.mem-events ol {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.mem-events li {
  display: flex;
  gap: 8px;
  font-size: 10px;
  color: #7a879c;
  line-height: 1.6;
}
.mem-event-date {
  color: #b3bdcb;
  flex-shrink: 0;
  width: 42px;
}
@media (max-width: 700px) {
  .mem-item {
    flex-direction: column;
  }
  .mem-actions {
    align-self: flex-end;
  }
  .mem-add {
    flex-wrap: wrap;
  }
  .mem-add input {
    flex-basis: 100%;
  }
}
</style>
