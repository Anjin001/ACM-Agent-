<script setup lang="ts">
import { computed, ref, onUnmounted, watch } from 'vue'
import {
  Brain,
  Lightbulb,
  CheckCircle2,
  CircleHelp,
  ArrowRight,
  FlaskConical,
} from 'lucide-vue-next'
import { api } from '../api'
import type { AppState, Assessment, ChallengeResult } from '../types'
interface Question {
  id: string
  route: string
  topic: string
  prompt: string
  options: string[]
  hintCount: number
}
interface Challenge {
  id: string
  title: string
  topic: string
  description: string
  code: string
  fixes: string[]
}
const props = defineProps<{ state: AppState }>()
const emit = defineEmits<{ update: [state: AppState]; route: [names: string[]] }>()
const catalog = ref<{ questions: Question[]; challenges: Challenge[] }>({
  questions: [],
  challenges: [],
})
const route = ref('foundation')
const answers = ref<Record<string, number>>({})
const hints = ref<Record<string, string>>({})
const result = ref<Assessment | null>(null)
const busy = ref('')
const error = ref('')
const challenge = ref('binary')
const numbers = ref('3')
const target = ref(3)
const fix = ref(-1)
const explanation = ref('')
const challengeResult = ref<ChallengeResult | null>(null)
watch([numbers, target, fix, explanation, challenge], () => {
  challengeResult.value = null
})
let mounted = true
onUnmounted(() => {
  mounted = false
})
const questions = computed(() =>
  catalog.value.questions.filter((item) => item.route === route.value),
)
const selectedChallenge = computed(() =>
  catalog.value.challenges.find((item) => item.id === challenge.value),
)
async function run(name: string, action: () => Promise<void>) {
  if (busy.value) return
  busy.value = name
  error.value = ''
  try {
    await action()
  } catch (failure) {
    if (mounted) error.value = failure instanceof Error ? failure.message : '请求失败，请重试'
  } finally {
    if (mounted) busy.value = ''
  }
}
function load() {
  run('load', async () => {
    const data = await api<typeof catalog.value>('/learning')
    if (mounted) catalog.value = data
  })
}
load()
function switchRoute(value: string) {
  route.value = value
  result.value = null
  answers.value = {}
  hints.value = {}
}
function hint(id: string) {
  run(id, async () => {
    const data = await api<{ state: AppState; hint: string }>('/learning/hint', 'POST', {
      revision: props.state.revision,
      questionId: id,
    })
    if (mounted) {
      emit('update', data.state)
      hints.value[id] = data.hint
    }
  })
}
function assess() {
  run('assess', async () => {
    const data = await api<{ state: AppState; result: Assessment }>(
      '/learning/assessment',
      'POST',
      { revision: props.state.revision, route: route.value, answers: answers.value },
    )
    if (mounted) {
      emit('update', data.state)
      result.value = data.result
    }
  })
}
function chooseChallenge(id: string) {
  challenge.value = id
  numbers.value =
    challenge.value === 'overflow'
      ? '1000000000, 1000000000, 1000000000'
      : challenge.value === 'average'
        ? '1, 2'
        : '3'
  fix.value = -1
  explanation.value = ''
  challengeResult.value = null
}
function verify() {
  run('challenge', async () => {
    const parts = numbers.value.trim().split(/[\s,，]+/)
    if (parts.some((part) => !/^-?\d+$/.test(part)))
      throw new Error('数组请填写整数，使用逗号或空格分隔')
    const data = await api<{ state: AppState; result: ChallengeResult }>(
      '/learning/challenge',
      'POST',
      {
        revision: props.state.revision,
        challengeId: challenge.value,
        numbers: parts.map(Number),
        target: target.value,
        fix: fix.value,
        explanation: explanation.value,
      },
    )
    if (mounted) {
      emit('update', data.state)
      challengeResult.value = data.result
    }
  })
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>先理解，再用证据验证。</h1>
      <p>通过理解题寻找学习起点，再用反例检查预设程序。</p>
    </div>
    <span class="quiet-badge"
      >已保存 {{ state.workspace.assessments.length }} 次诊断 /
      {{ state.workspace.challenges.length }} 次验证</span
    >
  </div>
  <div v-if="error" class="notice error" role="alert">
    {{ error
    }}<button v-if="!catalog.questions.length" class="text-button" @click="load">重试</button>
  </div>
  <section class="panel assessment-panel">
    <div class="panel-heading">
      <h2><Brain :size="19" />理解题诊断</h2>
      <span class="small-text muted">用于选择学习起点</span>
    </div>
    <div class="topic-group-tabs">
      <button :class="{ active: route === 'foundation' }" @click="switchRoute('foundation')">
        编程与基础算法</button
      ><button :class="{ active: route === 'algorithms' }" @click="switchRoute('algorithms')">
        算法进阶
      </button>
    </div>
    <p class="learning-description">
      按当前理解作答。需要时可以逐级获取提示，使用情况会随结果保存。
    </p>
    <form @submit.prevent="assess">
      <article
        v-for="(question, index) in questions"
        :key="question.id"
        class="diagnostic-question"
      >
        <div class="diagnostic-title">
          <span>{{ index + 1 }}</span
          ><strong>{{ question.prompt }}</strong
          ><small>{{ question.topic }}</small>
        </div>
        <div class="diagnostic-options">
          <label
            v-for="(option, optionIndex) in question.options"
            :key="optionIndex"
            :class="{ selected: answers[question.id] === optionIndex }"
            ><input
              v-model="answers[question.id]"
              :value="optionIndex"
              :name="question.id"
              type="radio"
              required
              :disabled="!!result"
            />{{ option }}</label
          >
        </div>
        <button
          type="button"
          class="text-button"
          :disabled="
            !!busy ||
            (state.workspace.learningHints[question.id] || 0) >= question.hintCount ||
            !!result
          "
          @click="hint(question.id)"
        >
          <Lightbulb :size="14" />{{
            state.workspace.learningHints[question.id] || 0
              ? `继续提示（已用 ${state.workspace.learningHints[question.id]} 级）`
              : '给我一级提示'
          }}
        </button>
        <p v-if="hints[question.id]" class="knowledge-hint">{{ hints[question.id] }}</p>
        <div v-if="result" class="answer-explanation">
          <span :class="result.items[index].correct ? 'answer-correct' : 'answer-incorrect'"
            >{{ result.items[index].correct ? '回答正确' : '需要再检查' }} · 正确选项
            {{ question.options[result.items[index].answer] }}</span
          >
          <p>{{ result.items[index].explanation }}</p>
        </div>
      </article>
      <button
        class="button primary"
        :disabled="!!busy || !questions.length || !!result"
        type="submit"
      >
        {{ busy === 'assess' ? '正在保存' : '提交诊断并查看解释' }}<ArrowRight :size="15" />
      </button>
    </form>
    <div v-if="result" class="assessment-result">
      <h3>本次理解题答对 {{ result.correct }} / {{ result.total }}</h3>
      <p>
        其中
        {{
          result.items.filter((item) => item.hintLevel > 0).length
        }}
        题使用过站内提示。建议结合实际编码练习和独立再测继续观察。
      </p>
      <div class="tag-list">
        <span v-for="name in result.suggestedTopics" :key="name" class="tag">{{ name }}</span>
      </div>
      <button class="button outline" @click="emit('route', result.suggestedTopics)">
        根据诊断设置训练专题</button
      ><button class="text-button" @click="switchRoute(route)">开始新一轮作答</button>
    </div>
  </section>
  <section class="panel assessment-panel">
    <div class="panel-heading">
      <h2><FlaskConical :size="19" />代码验证挑战</h2>
      <span class="small-text muted">预设程序与参考实现</span>
    </div>
    <div class="topic-group-tabs">
      <button
        v-for="item in catalog.challenges"
        :key="item.id"
        :class="{ active: challenge === item.id }"
        @click="chooseChallenge(item.id)"
      >
        {{ item.title }}
      </button>
    </div>
    <template v-if="selectedChallenge"
      ><p class="learning-description">{{ selectedChallenge.description }}</p>
      <pre class="challenge-code"><code>{{ selectedChallenge.code }}</code></pre>
      <form @submit.prevent="verify">
        <label
          >构造测试数组<input
            v-model="numbers"
            required
            placeholder="用逗号或空格分隔整数" /></label
        ><label v-if="challenge === 'binary'"
          >查找目标<input v-model.number="target" type="number" required /></label
        ><label
          >你认为应该怎样修复<select v-model.number="fix" required>
            <option disabled :value="-1">选择一种修复方式</option>
            <option v-for="(option, index) in selectedChallenge.fixes" :key="index" :value="index">
              {{ option }}
            </option>
          </select></label
        ><label
          >解释为什么这组输入能暴露问题<textarea
            v-model="explanation"
            required
            maxlength="1500"
            rows="3"
            placeholder="写出预期结果、程序结果与导致差异的原因"
          /></label
        ><button class="button primary" :disabled="!!busy" type="submit">
          验证反例并保存<ArrowRight :size="15" />
        </button>
      </form>
      <div v-if="challengeResult" class="assessment-result">
        <h3>
          <CheckCircle2 v-if="challengeResult.counterexample" :size="18" /><CircleHelp
            v-else
            :size="18"
          />{{ challengeResult.counterexample ? '反例已验证' : '这组数据没有暴露差异' }}
        </h3>
        <div class="verification-outputs">
          <div>
            <small>预设缺陷程序</small><strong>{{ challengeResult.buggyOutput }}</strong>
          </div>
          <div>
            <small>参考实现</small><strong>{{ challengeResult.referenceOutput }}</strong>
          </div>
        </div>
        <p>
          {{
            challengeResult.correctFix
              ? '所选修复方式与预设参考方案一致。'
              : '请再次检查修复选项，反例记录和说明已经保存。'
          }}
        </p>
        <p>文字解释作为个人复盘保存，当前验证结果来自预设程序的固定规则。</p>
      </div></template
    >
  </section>
  <section v-if="state.workspace.assessments.length" class="panel assessment-panel">
    <h2>最近的诊断记录</h2>
    <div
      v-for="item in [...state.workspace.assessments].reverse().slice(0, 5)"
      :key="item.id"
      class="assessment-history"
    >
      <span>{{ item.route === 'foundation' ? '基础路线' : '算法进阶' }}</span
      ><strong>{{ item.correct }} / {{ item.total }}</strong
      ><small>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</small>
    </div>
  </section>
</template>
