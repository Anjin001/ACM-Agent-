<script setup lang="ts">
import { ref, watch } from 'vue'
import { RefreshCw, Link2, CheckCircle2, Square, ArrowUpRight } from 'lucide-vue-next'
import ProblemLibrary from './ProblemLibrary.vue'
import type { AppState, SyncJob } from '../types'
const props = defineProps<{ state: AppState; job: SyncJob; busy: string }>()
const emit = defineEmits<{ connect: [handle: string]; sync: [mode: string]; cancel: [] }>()
const handle = ref(props.state.workspace.profile.handle)
watch(
  () => props.state.workspace.profile.handle,
  (value) => {
    handle.value = value
  },
)
function date(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(value),
  )
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>把做过的题，接回你的训练。</h1>
      <p>同步公开提交，查看已通过题目，生成新计划时自动排除已知完成题。</p>
    </div>
    <a
      v-if="state.workspace.profile.handle"
      class="button outline"
      :href="`https://codeforces.com/profile/${encodeURIComponent(state.workspace.profile.handle)}`"
      target="_blank"
      rel="noopener noreferrer"
      >查看 CF 主页<ArrowUpRight :size="15"
    /></a>
  </div>
  <section class="panel cf-connect-panel">
    <form class="cf-connect-form" @submit.prevent="emit('connect', handle.trim())">
      <label
        ><span>Codeforces handle</span
        ><input
          v-model="handle"
          required
          pattern="[A-Za-z0-9_.\-]{1,24}"
          maxlength="24"
          placeholder="输入你的 CF 用户名"
          aria-label="CF 用户名" /></label
      ><button class="button primary" :disabled="!!busy" type="submit">
        <Link2 :size="16" />保存并同步
      </button>
    </form>
    <p class="panel-note">
      填写公开用户名即可关联记录。该关联用于读取公开数据，账号归属由本人确认。
    </p>
    <div class="cf-sync-actions">
      <button
        class="button outline"
        :disabled="!state.workspace.profile.handle || job.status === 'running' || !!busy"
        @click="emit('sync', 'incremental')"
      >
        <RefreshCw :size="15" />快速更新</button
      ><button
        class="button outline"
        :disabled="!state.workspace.profile.handle || job.status === 'running' || !!busy"
        @click="emit('sync', 'full')"
      >
        重新同步完整历史</button
      ><button v-if="job.status === 'running'" class="text-button" @click="emit('cancel')">
        <Square :size="14" />取消本次同步</button
      ><span class="filter-help"
        >首次自动读取完整历史，后续快速更新最近记录。完整同步可刷新较早的重判结果。</span
      >
    </div>
    <div
      v-if="job.status !== 'idle'"
      class="notice"
      :class="{ error: job.status === 'error' }"
      role="status"
    >
      <RefreshCw v-if="job.status === 'running'" :size="16" class="spinning" /><CheckCircle2
        v-else
        :size="16"
      /><span
        >{{ job.message
        }}<small v-if="job.status === 'running'">
          已读取 {{ job.pages }} 页 / {{ job.readCount.toLocaleString() }} 条</small
        ></span
      >
    </div>
    <div class="cf-stats">
      <div>
        <small>当前关联</small><strong>{{ state.workspace.profile.handle || '待填写' }}</strong>
      </div>
      <div>
        <small>已通过题目</small
        ><strong>{{ state.cfSolvedIds.length.toLocaleString() }} <em>道</em></strong>
      </div>
      <div>
        <small>已保存提交</small
        ><strong
          >{{
            state.workspace.sync.handle.toLowerCase() ===
            state.workspace.profile.handle.toLowerCase()
              ? state.workspace.sync.submissionCount.toLocaleString()
              : 0
          }}
          <em>条</em></strong
        >
      </div>
      <div>
        <small>覆盖情况</small
        ><strong>{{
          state.workspace.sync.handle.toLowerCase() ===
            state.workspace.profile.handle.toLowerCase() && state.workspace.sync.fullHistory
            ? '完整历史已读取'
            : '等待完整同步'
        }}</strong>
      </div>
    </div>
    <p
      v-if="
        state.workspace.sync.syncedAt &&
        state.workspace.sync.handle.toLowerCase() === state.workspace.profile.handle.toLowerCase()
      "
      class="panel-note"
    >
      上次同步 {{ date(state.workspace.sync.syncedAt) }}。已通过状态来自 CF
      判题，独立完成情况通过复盘记录。
    </p>
  </section>
  <div class="section-heading">
    <h2>已通过题目</h2>
    <span>按最近通过时间排序，支持原题跳转</span>
  </div>
  <p class="panel-note">旧记录缺少题目详情时仍保留题号和原题入口，重新同步完整历史可补齐详情。</p>
  <ProblemLibrary :topics="state.topics" :revision="state.revision" accepted-only />
</template>
