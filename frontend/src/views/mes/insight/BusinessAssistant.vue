<template>
  <section class="business-assistant">
    <p class="note">
      可查询工单、设备、库存、单据、质量、成本与生产风险，生成工单 Excel 报表。
      可创建和推进异常闭环：核对执行预览并确认后真实写入业务，保留操作记录。
    </p>
    <div class="conversation-controls">
      <el-select
        :model-value="conversation?.id"
        placeholder="选择我的业务会话"
        :loading="listLoading"
        @change="openConversation"
      >
        <el-option
          v-for="item in conversations"
          :key="item.id"
          :value="item.id"
          :label="item.title"
        />
      </el-select>
      <el-button :disabled="!canChat || creating" @click="newConversation">新建会话</el-button>
      <el-button :disabled="!canChat || listLoading" @click="refresh">刷新会话</el-button>
      <el-button
        v-if="conversation"
        type="danger"
        plain
        :disabled="sending || historyLoading"
        @click="deleteCurrent"
        >删除本会话</el-button
      >
    </div>
    <el-pagination
      v-if="conversationTotal > 20"
      small
      layout="prev, pager, next"
      :total="conversationTotal"
      :page-size="20"
      :current-page="conversationPage"
      :disabled="listLoading"
      @current-change="loadList"
    />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-button v-if="recoverId" :disabled="historyLoading" @click="openConversation(recoverId)">
      重新载入会话后继续
    </el-button>
    <div v-if="conversation" class="note">
      {{ conversation.title }} · {{ conversation.turnCount }} 轮
      <span v-if="historyLoading"> · 正在载入</span>
    </div>
    <el-button v-if="hasMore" :loading="olderLoading" @click="loadOlder">加载更早记录</el-button>
    <article v-for="turn in turns" :key="turn.id" class="turn">
      <p class="question">{{ turn.question || '已选择澄清选项' }}</p>
      <el-tag size="small" :type="turn.answer.status === 'CLARIFY' ? 'warning' : 'info'">
        {{
          turn.answer.status === 'CLARIFY'
            ? '需要澄清'
            : turn.answer.status === 'OUT_OF_SCOPE'
              ? '查询范围说明'
              : turn.answer.mode === 'MODEL'
                ? '模型分析 · 请核对依据'
                : '业务规则答复'
        }}
      </el-tag>
      <p class="answer-text">{{
        turn.answer.executionReceipt
          ? `业务操作已执行，异常编号 #${turn.answer.executionReceipt.businessId}。`
          : turn.answer.answer
      }}</p>
      <p v-if="turn.answer.modelWarning" class="note">{{ turn.answer.modelWarning }}</p>
      <small class="note">查询时间 {{ formatTime(turn.answer.generatedAt) }}</small>
      <div v-if="turn.answer.status === 'CLARIFY'" class="clarification">
        <p>{{ turn.answer.clarification?.prompt }}</p>
        <el-button
          v-for="choice in turn.answer.clarification?.choices || []"
          :key="choice.id"
          :disabled="sending || !isActiveClarification(turn)"
          @click="choose(turn, choice.id)"
          >{{ choice.label }}</el-button
        >
        <small class="note">
          {{
            isActiveClarification(turn)
              ? '也可以在下方补充年份或筛选条件。'
              : '此轮选项已失效，请查看最新答复。'
          }}
        </small>
      </div>
      <AgentResult
        v-if="turn.answer.topic === 'agent_data' || turn.answer.topic === 'agent_action'"
        :answer="turn.answer"
        @failure="handleFailure"
        @executed="turn.answer.executionReceipt = $event"
      />
      <InventoryEvidence
        v-else-if="isStockEvidence(turn.answer.evidence)"
        :turn-id="turn.id"
        :evidence="turn.answer.evidence"
        @failure="handleFailure"
      />
      <LegacyBusinessEvidence
        v-else-if="turn.answer.status === 'ANSWERED'"
        :answer="turn.answer"
        @draft="emit('draft', $event)"
        @navigate="emit('navigate', $event)"
      />
    </article>
    <div class="presets">
      <el-button
        v-for="preset in presets"
        :key="preset"
        plain
        :disabled="sending || !canChat"
        @click="askPreset(preset)"
      >
        {{ preset }}
      </el-button>
    </div>
    <el-input
      v-model="question"
      type="textarea"
      :rows="3"
      maxlength="1000"
      show-word-limit
      :disabled="!canChat || sending || historyLoading || olderLoading || !!recoverId"
      placeholder="例如：生成本月未完成工单报表；查询设备状态；新建异常：工单缺料，需要跟进。"
    />
    <div class="conversation-controls">
      <el-checkbox v-model="useModel" :disabled="!canModel || sending">
        使用已配置模型理解问题（业务执行仍需确认）
      </el-checkbox>
      <el-button type="primary" :loading="sending" :disabled="!canSend" @click="send()"
        >发送给业务 Agent</el-button
      >
      <el-button @click="router.push('/mes/assistant')">知识库与模型配置</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { InsightApi, type Draft } from '@/api/mes/insight'
import {
  isStockEvidence,
  type BusinessAskInput,
  type BusinessConversation,
  type BusinessTurn
} from '@/api/mes/insight/business'
import { checkPermi } from '@/utils/permission'
import InventoryEvidence from './InventoryEvidence.vue'
import LegacyBusinessEvidence from './LegacyBusinessEvidence.vue'
import AgentResult from './AgentResult.vue'

const props = defineProps<{ active: boolean }>()
const emit = defineEmits<{ draft: [value: Draft]; navigate: [topic: string] }>()
const router = useRouter()
const conversation = ref<BusinessConversation>()
const conversations = ref<BusinessConversation[]>([])
const conversationTotal = ref(0)
const conversationPage = ref(1)
const turns = ref<BusinessTurn[]>([])
const hasMore = ref(false)
const beforeTurnId = ref<number | null>(null)
const recoverId = ref<number | null>(null)
const question = ref('')
const useModel = ref(false)
const error = ref('')
const sending = ref(false)
const historyLoading = ref(false)
const olderLoading = ref(false)
const listLoading = ref(false)
const creating = ref(false)
let contextEpoch = 0
let listEpoch = 0
const canChat = computed(
  () => checkPermi(['mes:insight:query']) && checkPermi(['mes:insight:chat'])
)
const canModel = computed(() => checkPermi(['mes:assistant:chat']))
const canSend = computed(
  () =>
    canChat.value &&
    !!question.value.trim() &&
    !sending.value &&
    !historyLoading.value &&
    !olderLoading.value &&
    !creating.value &&
    !recoverId.value
)
const presets = computed(() => [
  ...(checkPermi(['mes:pro-work-order:query']) ? ['查询全部工单进度'] : []),
  ...(checkPermi(['mes:pro-work-order:export']) ? ['生成本月未完成工单报表'] : []),
  ...(checkPermi(['mes:dv-machinery:query']) ? ['查询设备状态'] : []),
  ...(checkPermi(['mes:insight:manage']) ? ['新建异常：物料供应需要跟进'] : []),
  ...(checkPermi(['mes:wm-material-stock:query']) ? ['查上个月原材料收发存，按批次'] : []),
  '今天哪些工单有交付风险？',
  '有哪些待处理异常？',
  ...(checkPermi(['mes:cost:query']) ? ['BROKY-FG-V2 的 BOM 标准成本是多少？'] : [])
])
const formatTime = (value: string) => value.replace('T', ' ').replace(/([+-]\d\d:\d\d|Z)$/, '')

function resetContext() {
  contextEpoch++
  conversation.value = undefined
  turns.value = []
  hasMore.value = false
  beforeTurnId.value = null
  recoverId.value = null
  error.value = ''
  question.value = ''
  sending.value = false
  historyLoading.value = false
  olderLoading.value = false
  creating.value = false
  return contextEpoch
}
function handleFailure(_cause?: unknown) {
  const id = conversation.value?.id || recoverId.value
  resetContext()
  recoverId.value = id || null
  listEpoch++
  conversations.value = []
  conversationTotal.value = 0
  listLoading.value = false
  error.value = '查询失败或会话、权限已变更。请重新载入会话；也可以新建会话。'
}
async function loadList(page = conversationPage.value) {
  if (!canChat.value) return handleFailure()
  const request = ++listEpoch
  listLoading.value = true
  try {
    const data = await InsightApi.conversationPage(page, 20)
    if (request !== listEpoch) return
    if (!canChat.value) return handleFailure()
    conversations.value = data.list
    conversationTotal.value = data.total
    conversationPage.value = page
    const updated = data.list.find((item) => item.id === conversation.value?.id)
    if (updated && updated.version === conversation.value?.version) conversation.value = updated
  } catch (cause) {
    if (request === listEpoch) handleFailure(cause)
  } finally {
    if (request === listEpoch) listLoading.value = false
  }
}
async function openConversation(id: number) {
  if (!canChat.value || !Number.isSafeInteger(id) || id <= 0) return
  const epoch = resetContext()
  recoverId.value = id
  historyLoading.value = true
  try {
    const data = await InsightApi.conversation(id)
    if (epoch !== contextEpoch) return
    if (!canChat.value) return handleFailure()
    conversation.value = data.conversation
    turns.value = data.turns
    hasMore.value = data.hasMore
    beforeTurnId.value = data.nextBeforeTurnId
    recoverId.value = null
  } catch (cause) {
    if (epoch === contextEpoch) handleFailure(cause)
  } finally {
    if (epoch === contextEpoch) historyLoading.value = false
  }
}
async function newConversation() {
  if (!canChat.value || creating.value) return
  const epoch = resetContext()
  creating.value = true
  try {
    const data = await InsightApi.createConversation()
    if (epoch !== contextEpoch) return
    if (!canChat.value) return handleFailure()
    conversation.value = data
    void loadList(1)
  } catch (cause) {
    if (epoch === contextEpoch) handleFailure(cause)
  } finally {
    if (epoch === contextEpoch) creating.value = false
  }
}
async function deleteCurrent() {
  const current = conversation.value
  if (!current || sending.value || historyLoading.value || !canChat.value) return
  const epoch = contextEpoch
  historyLoading.value = true
  try {
    await InsightApi.deleteConversation(current.id, current.version)
    if (epoch !== contextEpoch) return
    resetContext()
    void loadList(1)
  } catch (cause) {
    if (epoch === contextEpoch) handleFailure(cause)
  } finally {
    if (epoch === contextEpoch) historyLoading.value = false
  }
}
async function loadOlder() {
  const current = conversation.value
  if (!current || !beforeTurnId.value || olderLoading.value || sending.value || !canChat.value)
    return
  const epoch = contextEpoch
  olderLoading.value = true
  try {
    const data = await InsightApi.conversation(current.id, beforeTurnId.value)
    if (epoch !== contextEpoch) return
    if (data.conversation.version !== current.version) return openConversation(current.id)
    const known = new Set(turns.value.map((item) => item.id))
    turns.value = [...data.turns.filter((item) => !known.has(item.id)), ...turns.value]
    hasMore.value = data.hasMore
    beforeTurnId.value = data.nextBeforeTurnId
  } catch (cause) {
    if (epoch === contextEpoch) handleFailure(cause)
  } finally {
    if (epoch === contextEpoch) olderLoading.value = false
  }
}
function isActiveClarification(turn: BusinessTurn) {
  return (
    !!conversation.value &&
    turn.version === conversation.value.version &&
    turns.value.at(-1)?.id === turn.id &&
    !!turn.answer.clarification &&
    Date.parse(turn.answer.clarification.expiresAt) > Date.now() &&
    !recoverId.value
  )
}
async function choose(turn: BusinessTurn, choiceId: string) {
  if (
    !isActiveClarification(turn) ||
    !turn.answer.clarification?.choices.some((choice) => choice.id === choiceId)
  )
    return
  await send({ token: turn.answer.clarification.token, choiceId })
}
async function send(clarification?: BusinessAskInput['clarification']) {
  if (
    !canChat.value ||
    sending.value ||
    historyLoading.value ||
    olderLoading.value ||
    creating.value ||
    recoverId.value
  )
    return
  if (!clarification && !question.value.trim()) return
  const epoch = contextEpoch
  const previous = conversation.value
  const text = clarification ? '' : question.value.trim()
  const data: BusinessAskInput = { question: text, useModel: useModel.value && canModel.value }
  if (previous) {
    data.conversationId = previous.id
    data.version = previous.version
  }
  if (clarification) data.clarification = clarification
  sending.value = true
  error.value = ''
  try {
    const answer = await InsightApi.ask(data)
    if (epoch !== contextEpoch) return
    if (!canChat.value) return handleFailure()
    if (
      previous &&
      (answer.conversationId !== previous.id || answer.version !== previous.version + 1)
    ) {
      return handleFailure()
    }
    conversation.value = {
      id: answer.conversationId,
      title: previous?.title || text.slice(0, 160) || '业务会话',
      version: answer.version,
      turnCount: (previous?.turnCount || 0) + 1,
      createdAt: previous?.createdAt || answer.generatedAt,
      updatedAt: answer.generatedAt
    }
    turns.value = [
      ...turns.value,
      {
        id: answer.turnId,
        question: text,
        createdAt: answer.generatedAt,
        version: answer.version,
        answer
      }
    ]
    question.value = ''
    void loadList(1)
  } catch (cause) {
    if (epoch === contextEpoch) handleFailure(cause)
  } finally {
    if (epoch === contextEpoch) sending.value = false
  }
}
function askPreset(value: string) {
  if (sending.value || historyLoading.value || recoverId.value) return
  question.value = value
  void send()
}
async function refresh() {
  if (conversation.value && !sending.value && !historyLoading.value)
    await openConversation(conversation.value.id)
  await loadList()
}
watch(
  () => props.active,
  (active) => {
    if (active) void refresh()
  },
  { immediate: true }
)
watch(
  () => [
    canChat.value,
    checkPermi(['mes:wm-material-stock:query']),
    checkPermi(['mes:cost:query'])
  ],
  (next, previous) => {
    if (previous?.some((allowed, index) => allowed && !next[index])) handleFailure()
  }
)
defineExpose({ refresh })
</script>

<style scoped>
.business-assistant {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.conversation-controls,
.presets {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.conversation-controls .el-select {
  width: min(340px, 100%);
}

.presets .el-button,
.clarification .el-button {
  margin-left: 0;
}

.turn {
  padding: 18px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.question {
  font-weight: 600;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.answer-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.note {
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}

.clarification {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-block: 12px;
}

.clarification p,
.clarification small {
  width: 100%;
}
</style>
