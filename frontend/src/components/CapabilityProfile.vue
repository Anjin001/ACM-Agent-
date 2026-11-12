<script setup lang="ts">
import { ref } from 'vue'
import type { ProfileModel } from '../types'

const props = defineProps<{ model: ProfileModel | null; loading?: boolean }>()
const open = ref('')

function toggle(name: string) {
  open.value = open.value === name ? '' : name
}
function pct(value: number) {
  return `${Math.round(value * 100)}%`
}
function outcomeLabel(outcome: string) {
  return outcome === 'independent' ? '独立完成' : outcome === 'editorial' ? '参考题解' : '仍在探索'
}
function biasText(bias: number) {
  if (bias >= 8) return `比预估慢 ${bias} 分钟`
  if (bias <= -8) return `比预估快 ${-bias} 分钟`
  return '与预估接近'
}
</script>

<template>
  <section class="panel cap-panel">
    <div class="cap-head">
      <div>
        <h2>能力画像</h2>
        <p class="cap-sub">等级、样本数与证据都来自真实复盘记录，可逐条核对。</p>
      </div>
      <span v-if="model" class="cap-total">{{ model.samples }} 次记录</span>
    </div>

    <p v-if="loading && !model" class="cap-empty">正在计算画像…</p>
    <p v-else-if="!model || !model.topics.length" class="cap-empty">
      还没有可用的训练记录。完成一次练习并写下复盘后，这里会按专题给出等级与证据。
    </p>

    <template v-else>
      <p class="cap-summary">{{ model.summary }}</p>

      <div class="cap-rows">
        <div v-for="topic in model.topics" :key="topic.name" class="cap-row">
          <div class="cap-name">
            <strong>{{ topic.name }}</strong>
            <small v-if="topic.samples">{{ topic.samples }} 次 · 独立 {{ pct(topic.independentRate) }}</small>
            <small v-else>样本不足</small>
          </div>
          <div class="cap-level" :aria-label="`等级 ${topic.level ?? 0} / 5`">
            <i v-for="n in 5" :key="n" :class="{ on: topic.level !== null && n <= topic.level }" />
          </div>
          <div class="cap-meta">
            <span v-if="topic.avgRating">{{ topic.avgRating }} 分</span>
            <span v-if="topic.samples">{{ biasText(topic.bias) }}</span>
            <span v-else>待观察</span>
          </div>
          <button
            v-if="topic.evidence.length"
            class="cap-evidence-toggle"
            :aria-expanded="open === topic.name"
            @click="toggle(topic.name)"
          >
            证据 {{ topic.evidence.length }}
          </button>
          <span v-else class="cap-no-evidence">—</span>
        </div>
      </div>

      <div v-for="topic in model.topics" :key="`e-${topic.name}`" v-show="open === topic.name" class="cap-evidence">
        <div v-for="item in topic.evidence" :key="item.problemId + item.date" class="cap-evidence-row">
          <span class="cap-code">{{ item.code }}</span>
          <span class="cap-title">{{ item.title }}</span>
          <span class="cap-outcome" :class="item.outcome">{{ outcomeLabel(item.outcome) }}</span>
          <span class="cap-minutes">{{ item.minutes }} / 预估 {{ item.estimate }} 分钟</span>
        </div>
      </div>

      <div v-if="model.buckets.length" class="cap-buckets">
        <span class="cap-bucket-label">难度分布</span>
        <span v-for="bucket in model.buckets" :key="bucket.label" class="cap-bucket">
          {{ bucket.label }}<em>{{ bucket.attempts }} 次 · {{ pct(bucket.rate) }}</em>
        </span>
      </div>
    </template>
  </section>
</template>

<style scoped>
.cap-panel {
  padding: 20px 22px 18px;
}
.cap-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.cap-head h2 {
  font-size: 14px;
  font-weight: 600;
}
.cap-sub {
  margin: 5px 0 0;
  font-size: 11px;
  color: #8793a8;
  line-height: 1.7;
}
.cap-total {
  font-size: 10px;
  color: #6382ce;
  background: #eef3ff;
  border-radius: 20px;
  padding: 4px 9px;
  white-space: nowrap;
}
.cap-empty {
  margin: 16px 0 4px;
  font-size: 12px;
  color: #8793a8;
  line-height: 1.8;
}
.cap-summary {
  margin: 14px 0 4px;
  font-size: 12px;
  line-height: 1.8;
  color: #3d4a61;
}
.cap-rows {
  margin-top: 8px;
}
.cap-row {
  display: grid;
  grid-template-columns: minmax(120px, 1.2fr) 74px minmax(110px, 1fr) 62px;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-top: 1px solid #f2f6fb;
}
.cap-name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.cap-name strong {
  font-size: 12px;
  font-weight: 500;
}
.cap-name small {
  font-size: 10px;
  color: #93a0b5;
}
.cap-level {
  display: flex;
  gap: 3px;
}
.cap-level i {
  width: 11px;
  height: 6px;
  border-radius: 2px;
  background: #edf1f8;
}
.cap-level i.on {
  background: #3159d9;
}
.cap-meta {
  display: flex;
  flex-direction: column;
  gap: 1px;
  font-size: 10px;
  color: #8793a8;
}
.cap-evidence-toggle {
  font-size: 10px;
  color: #3159d9;
  background: #f4f7ff;
  border-radius: 4px;
  padding: 4px 6px;
}
.cap-no-evidence {
  font-size: 10px;
  color: #c3ccda;
  text-align: center;
}
.cap-evidence {
  margin: 2px 0 8px;
  padding: 8px 10px;
  background: #f9fbff;
  border: 1px solid #eef3fb;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.cap-evidence-row {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr) 62px 108px;
  gap: 8px;
  align-items: center;
  font-size: 10px;
  color: #5c6b82;
}
.cap-code {
  color: #93a0b5;
}
.cap-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cap-outcome.independent {
  color: #4c8b74;
}
.cap-outcome.editorial {
  color: #9a7b3f;
}
.cap-outcome.stuck {
  color: #a95a5a;
}
.cap-minutes {
  text-align: right;
  color: #93a0b5;
}
.cap-buckets {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f2f6fb;
}
.cap-bucket-label {
  font-size: 10px;
  color: #93a0b5;
  margin-right: 2px;
}
.cap-bucket {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 10px;
  color: #4d5c76;
  background: #f5f8fd;
  border-radius: 4px;
  padding: 4px 7px;
}
.cap-bucket em {
  font-style: normal;
  color: #93a0b5;
}
@media (max-width: 700px) {
  .cap-row {
    grid-template-columns: minmax(0, 1fr) 62px;
    row-gap: 6px;
  }
  .cap-meta {
    grid-column: 1;
    flex-direction: row;
    gap: 8px;
  }
  .cap-evidence-toggle,
  .cap-no-evidence {
    justify-self: end;
  }
  .cap-evidence-row {
    grid-template-columns: 68px minmax(0, 1fr);
    row-gap: 3px;
  }
  .cap-minutes {
    text-align: left;
  }
}
</style>
