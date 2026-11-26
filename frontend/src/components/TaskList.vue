<script setup lang="ts">
import { Check, Clock3, ArrowUpRight, MessageSquare, CircleHelp } from 'lucide-vue-next'
import type { Problem, Task } from '../types'

defineProps<{ tasks: Task[]; problems: Record<string, Problem>; showDay?: boolean }>()
defineEmits<{ feedback: [task: Task] }>()
const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const labels: Record<string, string> = {
  independent: '独立完成',
  editorial: '参考题解',
  stuck: '仍在探索',
  synced: 'CF 已通过',
  todo: '待训练',
}
</script>

<template>
  <div class="task-list">
    <article v-for="task in tasks" :key="task.id" class="task-row">
      <div class="task-check" :class="task.status">
        <Check v-if="['independent', 'editorial', 'synced'].includes(task.status)" :size="16" />
        <CircleHelp v-else-if="task.status === 'stuck'" :size="17" />
        <span v-else class="empty-check" />
      </div>
      <div class="task-content">
        <div class="task-title-line">
          <a
            :href="problems[task.problemId]?.url"
            target="_blank"
            rel="noopener noreferrer"
            class="task-title"
            >{{ problems[task.problemId]?.title || task.problemId }}<ArrowUpRight :size="14"
          /></a>
          <span v-if="task.status !== 'todo'" class="status-label" :class="task.status">{{
            labels[task.status]
          }}</span>
        </div>
        <div class="task-meta">
          <span class="platform-text" :class="problems[task.problemId]?.platform"
            >{{ problems[task.problemId]?.platform === 'LG' ? '洛谷' : 'Codeforces' }}
            {{ problems[task.problemId]?.code }}</span
          >
          <span>{{ problems[task.problemId]?.tags.slice(0, 2).join(' / ') }}</span>
          <span v-if="showDay">{{ days[task.day] }}</span>
          <span class="icon-text"><Clock3 :size="12" />{{ task.minutes }} 分钟</span>
        </div>
      </div>
      <button
        class="task-feedback"
        :aria-label="`${task.status === 'todo' || task.status === 'synced' ? '写复盘' : '更新复盘'} ${problems[task.problemId]?.title || task.problemId}`"
        @click="$emit('feedback', task)"
      >
        <MessageSquare :size="15" /><span>{{
          task.status === 'todo' || task.status === 'synced' ? '写复盘' : '更新复盘'
        }}</span>
      </button>
    </article>
    <div v-if="tasks.length === 0" class="empty-state">
      这个时段留给休息或复盘，也可以到个人设置调整可用时间。
    </div>
  </div>
</template>
