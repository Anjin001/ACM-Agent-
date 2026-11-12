<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check } from 'lucide-vue-next'
import type { Topic } from '../types'
const props = defineProps<{ topics: Topic[]; modelValue: string[] }>()
const emit = defineEmits<{ 'update:modelValue': [names: string[]] }>()
const group = ref('全部')
const query = ref('')
const groups = computed(() => ['全部', ...new Set(props.topics.map((topic) => topic.category))])
const visible = computed(() =>
  props.topics.filter(
    (topic) =>
      (group.value === '全部' || topic.category === group.value) &&
      topic.name.toLowerCase().includes(query.value.toLowerCase()),
  ),
)
function toggle(name: string) {
  emit(
    'update:modelValue',
    props.modelValue.includes(name)
      ? props.modelValue.filter((item) => item !== name)
      : [...props.modelValue, name],
  )
}
</script>
<template>
  <div class="topic-picker">
    <div class="topic-group-tabs">
      <button
        v-for="name in groups"
        :key="name"
        type="button"
        :class="{ active: group === name }"
        @click="group = name"
      >
        {{ name }}
      </button>
    </div>
    <input
      v-model="query"
      aria-label="搜索训练专题"
      placeholder="搜索算法，例如 Dijkstra、数位DP、KMP"
    />
    <div class="topic-options">
      <button
        v-for="topic in visible"
        :key="topic.name"
        type="button"
        :class="{ chosen: modelValue.includes(topic.name) }"
        :aria-pressed="modelValue.includes(topic.name)"
        @click="toggle(topic.name)"
      >
        <Check v-if="modelValue.includes(topic.name)" :size="13" />{{ topic.name }}
      </button>
    </div>
    <p class="panel-note">
      已选 {{ modelValue.length }} 个专题，最多 30 个。分类总专题会同时覆盖所属的细分算法。
    </p>
  </div>
</template>
