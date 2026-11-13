<script setup lang="ts">
import { computed } from 'vue'
import CapabilityProfile from './CapabilityProfile.vue'
import type { ProfileModel } from '../types'

const props = defineProps<{ model: ProfileModel | null; loading: boolean; error: string; demo: boolean }>()
defineEmits<{ refresh: []; openPlan: [] }>()
const areas = computed(() => props.model?.abilityAreas ?? [])
const rated = computed(() => areas.value.filter(area => area.level !== null).length)
</script>

<template>
  <div class="page-heading">
    <div><h1>能力评估</h1><p>查看各算法大类的训练水平、刷题覆盖和限时表现。</p></div>
    <button class="button outline" type="button" :disabled="loading" @click="$emit('refresh')">
      {{ loading ? '正在更新' : '刷新评估' }}
    </button>
  </div>
  <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  <p v-if="loading && !model" class="notice" role="status">正在读取训练记录…</p>

  <template v-if="model">
    <p v-if="demo" class="notice">当前展示演示空间的数据。</p>
    <section class="panel ability-panel" aria-labelledby="ability-heading">
      <div class="ability-heading">
        <h2 id="ability-heading">算法大类</h2>
        <span>{{ rated }} / {{ areas.length }} 类已有等级</span>
      </div>
      <p class="ability-hint">满分 5 级。每类至少需要 3 道不同题的站内复盘。多标签题可计入多个大类，同一类内只计一次。</p>
      <ul class="ability-list">
        <li v-for="area in areas" :key="area.name" class="ability-row">
          <div>
            <strong>{{ area.name }}</strong>
            <p class="ability-hint">{{ area.topics.join('、') }}</p>
          </div>
          <div class="ability-score">
            <div v-if="area.level !== null" class="ability-bar" role="meter" :aria-label="area.name"
              aria-valuemin="0" aria-valuemax="5" :aria-valuenow="area.level">
              <span :style="{ width: area.level * 20 + '%' }" />
            </div>
            <div v-else class="ability-unrated" aria-hidden="true" />
            <span>{{ area.level === null ? '待评估' : area.level + ' / 5' }}</span>
          </div>
          <div class="ability-counts">
            <span>独立完成 {{ area.independent }} / {{ area.samples }} 道复盘题</span>
            <span v-if="area.codeforcesAttempted">CF 通过 {{ area.codeforcesAccepted }} / {{ area.codeforcesAttempted }} 道已尝试题</span>
            <span v-else>暂无可匹配的 CF 记录</span>
          </div>
        </li>
      </ul>
      <p v-if="model.codeforcesCoverageTruncated" class="ability-hint">CF 同步范围有限，较早提交可能未计入。</p>
      <details class="ability-method">
        <summary>等级怎么算？</summary>
        <p>每道题采用最新一次站内复盘。独立完成比例占 70%，独立完成题的 CF 难度贡献占 30%。难度按 800～1600 归一化，无难度数据的题仍计入独立完成比例。CF 公开提交与短测判题作为可核对的旁证。</p>
      </details>
      <button v-if="rated < areas.length" class="button outline" type="button" @click="$emit('openPlan')">去周计划训练</button>
    </section>

    <section class="panel ability-panel">
      <h2>算法细项与变化</h2>
      <p class="ability-hint">对比近 30 天与再往前 30 天的独立完成情况。每道题在各时期采用该时期最新的复盘；样本不足时保留原始题数。</p>
      <details v-for="group in model.skillGroups" :key="group.name">
        <summary>{{ group.name }} · {{ group.skills.length }} 项</summary>
        <ul class="ability-list">
          <li v-for="skill in group.skills" :key="skill.name" class="skill-row">
            <strong>{{ skill.name }}</strong>
            <span>前期 {{ skill.previousIndependent }}/{{ skill.previous }} → 近期 {{ skill.recentIndependent }}/{{ skill.recent }}</span>
            <span v-if="skill.recent >= 3 && skill.previous >= 3">
              {{ Math.round(100 * (skill.recentIndependent / skill.recent - skill.previousIndependent / skill.previous)) }} 个百分点
            </span>
            <span v-else>待积累</span>
            <small>CF 通过 {{ skill.accepted }} 题<template v-if="skill.source"> · {{ skill.source }}</template></small>
          </li>
        </ul>
      </details>
    </section>
    <section class="panel ability-panel">
      <h2>高频易错点</h2>
      <p class="ability-hint">按最近 30 条复盘统计。同一次复盘中的同类问题只计一次。</p>
      <p v-if="!model.recurringMistakes.length">暂无明确的失误记录。可以在训练复盘中填写「本次实际失误」。</p>
      <ul v-else class="ability-list">
        <li v-for="item in model.recurringMistakes" :key="item.name" class="mistake-row">
          <div><strong>{{ item.name }}</strong> · {{ item.count }} 次</div>
          <meter :aria-label="item.name" :value="item.count" min="0" :max="model.recurringMistakes[0]?.count || 1" />
          <p>最近一题 {{ item.lastProblem }} · {{ item.lastNote }}</p>
          <p>下次开题前：{{ item.reminder }}</p>
        </li>
      </ul>
    </section>
    <CapabilityProfile :model="model" />
  </template>
</template>

<style scoped>
.ability-panel { padding: clamp(16px, 3vw, 26px); margin-bottom: 20px; display: grid; gap: 12px; }
.ability-heading { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: baseline; gap: 8px; }
.ability-heading h2 { font-size: 17px; }
.ability-heading span, .ability-hint { color: #566681; font-size: 12px; line-height: 1.6; }
.ability-list { list-style: none; margin: 0; padding: 0; }
.ability-row { display: grid; grid-template-columns: minmax(110px, .8fr) minmax(150px, 1.3fr) minmax(140px, 1fr); gap: 12px; align-items: center; border-bottom: 1px solid #e7edf5; padding: 12px 0; }
.ability-score { display: grid; grid-template-columns: minmax(0, 1fr) 56px; align-items: center; gap: 10px; font-size: 12px; color: #405779; }
.ability-bar, .ability-unrated { height: 9px; border-radius: 5px; background: #e8edf6; overflow: hidden; }
.ability-bar span { display: block; height: 100%; border-radius: inherit; background: #3159d9; }
.ability-unrated { border: 1px dashed #a6b3ca; background: transparent; }
.ability-counts { display: grid; gap: 3px; font-size: 12px; color: #566681; }
.ability-method { color: #405779; font-size: 12px; line-height: 1.7; }
.ability-method summary { cursor: pointer; }
.ability-method p { margin-top: 5px; }
.skill-row { display: flex; flex-wrap: wrap; gap: 6px 14px; padding: 8px 0; border-bottom: 1px solid #e7edf5; font-size: 12px; }
.skill-row strong { min-width: 110px; }
.mistake-row { display: grid; gap: 4px; padding: 10px 0; border-bottom: 1px solid #e7edf5; overflow-wrap: anywhere; }
.mistake-row meter { width: min(100%, 280px); }
.mistake-row p { font-size: 12px; color: #566681; }
.ability-panel > button { justify-self: start; }
@media (max-width: 760px) {
  .ability-row { grid-template-columns: 1fr; gap: 6px; }
}
</style>
