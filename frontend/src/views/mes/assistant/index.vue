<template>
  <ContentWrap>
    <div class="assistant-heading">
      <div><h2>AI助手与知识库</h2><p>基于已发布文档回答，让每条依据都能回到原文</p></div>
      <el-tag :type="modelStatus?.configured ? 'success' : 'info'">
        {{
          modelStatus?.configured
            ? `${modelStatus.provider} · ${modelStatus.model}`
            : '模型尚未配置'
        }}
      </el-tag>
    </div>
    <el-button v-hasPermi="['mes:insight:query', 'mes:insight:chat']" class="mb-16px" @click="$router.push({ path: '/mes/insight', query: { tab: 'assistant' } })">查询实时生产业务与交期风险</el-button>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      class="assistant-alert"
    />
    <el-tabs v-model="tab">
      <el-tab-pane v-if="canChat || canKnowledge" label="知识助手" name="chat">
        <div class="assistant-layout" :class="{ 'assistant-without-sidebar': !canChat }">
          <aside class="assistant-sidebar" v-if="canChat">
            <el-button type="primary" plain :disabled="sending" @click="newSession"
              >新建会话</el-button
            >
            <div class="assistant-session-list">
              <button
                v-for="item in sessions"
                :key="item.id"
                class="assistant-session"
                :class="{ active: sessionId === item.id }"
                :disabled="sending"
                @click="openSession(item.id)"
              >
                <span>{{ item.title }}</span
                ><small>{{ time(item.updateTime) }}</small>
              </button>
              <el-empty v-if="!sessions.length" description="暂无会话" :image-size="50" />
            </div>
          </aside>
          <main class="assistant-chat">
            <div class="assistant-chat-toolbar">
              <el-select
                v-model="selectedBases"
                multiple
                collapse-tags
                collapse-tags-tooltip
                placeholder="全部已发布知识库"
                class="assistant-base-select"
              >
                <el-option
                  v-for="base in bases"
                  :key="base.id"
                  :label="base.name"
                  :value="base.id"
                />
              </el-select>
              <span class="assistant-method">中文 BM25 · 词法检索</span>
              <el-button v-if="sessionId" text :disabled="sending" @click="renameSession"
                >重命名</el-button
              >
              <el-button
                v-if="sessionId"
                text
                type="danger"
                :disabled="sending"
                @click="removeSession"
                >删除会话</el-button
              >
            </div>
            <el-alert
              v-if="!modelStatus?.configured"
              title="模型未配置：可以检索真实文档和查看引用，不会生成或伪造模型答复。管理员可在“模型配置”接入服务。"
              type="info"
              :closable="false"
              show-icon
            />
            <div class="assistant-messages" ref="messagesElement" v-loading="historyLoading">
              <div v-if="!messages.length && !searchResult" class="assistant-welcome"
                ><h3>从一份真实文档开始</h3
                ><p>上传并发布工艺规范、设备手册或质量文件，再提问并核对来源。</p
                ><p>支持检索与多轮对话；会话仅对当前用户可见。</p></div
              >
              <article
                v-for="item in messages"
                :key="item.id"
                class="assistant-message"
                :class="item.role"
              >
                <div class="assistant-message-label"
                  >{{ item.role === 'user' ? '我的提问' : `AI助手 · ${item.modelName}`
                  }}<small>{{ time(item.createTime) }}</small></div
                >
                <div class="assistant-message-content">{{ item.content }}</div>
                <el-alert
                  v-if="item.role === 'user' && item.status !== 'ANSWERED'"
                  :title="
                    item.status === 'MODEL_NOT_CONFIGURED'
                      ? '模型未配置，本次没有模型答复。以下为真实检索结果。'
                      : '模型服务未能返回答复；本次检索依据仍可查看。'
                  "
                  type="info"
                  :closable="false"
                />
                <div
                  v-if="
                    item.citations?.length &&
                    (item.role === 'assistant' || item.status !== 'ANSWERED')
                  "
                  class="assistant-citations"
                >
                  <button
                    v-for="(citation, index) in item.citations"
                    :key="citation.chunkId"
                    @click="viewCitation(citation)"
                    ><b>[{{ index + 1 }}] {{ citation.title }}</b
                    ><span
                      >{{ citation.heading
                      }}{{ citation.pageNumber ? ` · 第${citation.pageNumber}页` : '' }} · v{{
                        citation.version
                      }}</span
                    ></button
                  >
                </div>
              </article>
              <section v-if="searchResult" class="assistant-search-result">
                <h3>检索结果 · {{ searchResult.items.length }} 段</h3
                ><p
                  >在
                  {{ searchResult.eligibleChunkCount }}
                  个已发布片段中检索；以下为原文片段，没有生成模型答复。</p
                >
                <el-empty
                  v-if="!searchResult.items.length"
                  description="未找到匹配内容，请调整关键词或检查文档是否发布"
                  :image-size="60"
                />
                <button
                  v-for="(citation, index) in searchResult.items"
                  :key="citation.chunkId"
                  class="assistant-search-card"
                  @click="viewCitation(citation)"
                  ><b>[{{ index + 1 }}] {{ citation.title }} · {{ citation.heading }}</b
                  ><small
                    >{{ citation.pageNumber ? `第${citation.pageNumber}页 · ` : '' }}第{{
                      citation.chunkIndex + 1
                    }}段 · BM25 {{ citation.score }}</small
                  ><span>{{ citation.content }}</span></button
                >
              </section>
            </div>
            <div class="assistant-composer">
              <el-input
                v-model="question"
                type="textarea"
                :rows="3"
                maxlength="4000"
                show-word-limit
                placeholder="例如：设备点检发现异常时，应如何处理？Ctrl + Enter 发送"
                :disabled="sending"
                @keydown.ctrl.enter.prevent="send"
              />
              <div class="assistant-composer-actions"
                ><span>{{ chatNotice }}</span
                ><el-button
                  :loading="searching"
                  :disabled="sending || !question.trim() || question.length > 1000"
                  @click="search"
                  >仅检索文档</el-button
                ><el-button
                  v-if="canChat && canKnowledge"
                  type="primary"
                  :loading="sending"
                  :disabled="!question.trim()"
                  @click="send"
                  >发送提问</el-button
                ></div
              >
            </div>
          </main>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canKnowledge || canManage" label="RAG知识库" name="knowledge">
        <div class="assistant-knowledge-layout">
          <aside class="assistant-sidebar"
            ><el-button v-if="canManage" type="primary" plain @click="editBase()"
              >新建知识库</el-button
            ><button
              v-for="base in bases"
              :key="base.id"
              class="assistant-session"
              :class="{ active: knowledgeBaseId === base.id }"
              @click="selectBase(base.id)"
              ><span>{{ base.name }}</span
              ><small>{{ base.documentCount }} 份文档</small></button
            ><el-empty v-if="!bases.length" description="暂无知识库" :image-size="60"
          /></aside>
          <section class="assistant-documents">
            <template v-if="currentBase">
              <div class="assistant-document-heading"
                ><div
                  ><h3>{{ currentBase.name }}</h3
                  ><p>{{ currentBase.description || '暂无知识库描述' }}</p></div
                ><div v-if="canManage"
                  ><el-button @click="editBase(currentBase)">编辑</el-button
                  ><el-button type="danger" plain @click="removeBase">删除知识库</el-button
                  ><el-button type="primary" @click="uploadVisible = true">上传文档</el-button></div
                ></div
              >
              <el-alert
                title="上传后为草稿，审核原文并发布后才参与检索。支持UTF-8 TXT / MD / CSV、DOCX和文本型PDF，单文件最多5MB；扫描PDF需先OCR。"
                type="info"
                show-icon
                :closable="false"
              />
              <el-table
                :data="documents"
                v-loading="documentsLoading"
                class="assistant-document-table"
                empty-text="当前知识库没有可见文档"
                row-key="id"
              >
                <el-table-column
                  prop="title"
                  label="文档标题"
                  min-width="180"
                  show-overflow-tooltip
                />
                <el-table-column prop="fileType" label="格式" width="80" />
                <el-table-column prop="chunkCount" label="分块" width="75" />
                <el-table-column label="状态" width="90"
                  ><template #default="{ row }"
                    ><el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'">{{
                      row.status === 'PUBLISHED' ? '已发布' : '草稿'
                    }}</el-tag></template
                  ></el-table-column
                >
                <el-table-column label="操作" min-width="240"
                  ><template #default="{ row }"
                    ><el-button link type="primary" @click="preview(row.id)">原文与分块</el-button
                    ><el-button link @click="downloadDocument(row)">下载</el-button
                    ><el-button
                      v-if="canManage"
                      link
                      type="primary"
                      @click="publishDocument(row)"
                      >{{ row.status === 'PUBLISHED' ? '撤回' : '发布' }}</el-button
                    ><el-button v-if="canManage" link type="danger" @click="removeDocument(row)"
                      >删除</el-button
                    ></template
                  ></el-table-column
                >
              </el-table> </template
            ><el-empty v-else description="选择或新建知识库" />
          </section>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canConfig" label="模型配置" name="config">
        <div class="assistant-config">
          <el-alert
            title="API Key仅提交到本地后端并加密保存。页面不会读取已保存的密钥；聊天和检索不会接收服务地址或密钥。"
            type="info"
            show-icon
            :closable="false"
          />
          <el-form
            label-width="120px"
            :model="config"
            v-loading="configLoading"
            class="assistant-config-form"
            @submit.prevent
          >
            <el-form-item label="服务类型"
              ><el-select v-model="config.provider" @change="changeProvider"
                ><el-option label="OpenAI兼容接口" value="openai" /><el-option
                  label="本地Ollama"
                  value="ollama" /></el-select
            ></el-form-item>
            <el-form-item label="服务地址"
              ><el-input
                v-model="config.baseUrl"
                maxlength="500"
                placeholder="https://api.openai.com/v1"
              /><p class="assistant-field-help"
                >外部服务必须使用HTTPS；本地开发允许
                http://127.0.0.1:11434。禁止私网、metadata、URL凭据和重定向。</p
              ></el-form-item
            >
            <el-form-item label="模型名称"
              ><el-input
                v-model="config.model"
                maxlength="200"
                placeholder="填写服务中实际可用的模型名称"
            /></el-form-item>
            <el-form-item label="API Key"
              ><el-input
                v-model="apiKey"
                type="password"
                show-password
                autocomplete="new-password"
                maxlength="4096"
                :placeholder="
                  config.apiKeyConfigured ? '已配置；留空保留原密钥' : '尚未配置；Ollama可留空'
                "
              /><p class="assistant-field-help"
                >{{
                  config.apiKeyConfigured ? '后端已保存加密密钥。' : '后端未保存密钥。'
                }}
                留空不覆盖；更换服务主机时需重新输入或清除。</p
              ><el-checkbox v-model="clearApiKey"
                >明确清除已保存的API Key</el-checkbox
              ></el-form-item
            >
            <el-form-item label="温度"
              ><el-input-number
                v-model="config.temperature"
                :min="0"
                :max="1.5"
                :step="0.1"
                :precision="2"
            /></el-form-item>
            <el-form-item label="最大输出长度"
              ><el-input-number v-model="config.maxTokens" :min="128" :max="4096" :step="128"
            /></el-form-item>
            <el-form-item label="模型上下文长度">
              <el-input-number
                v-model="config.contextTokens"
                :min="2048"
                :max="131072"
                :step="1024"
              />
              <p class="assistant-field-help"
                >填写服务实际支持的上下文token上限。系统按UTF-8字节数作保守估算，预留输出空间；自动减少引用并成对淘汰旧问答，必要资料仍超限时明确拒绝。</p
              >
            </el-form-item>
            <el-form-item label="启用模型"><el-switch v-model="config.enabled" /></el-form-item>
            <el-form-item
              ><el-button type="primary" :loading="configSaving" @click="saveConfig"
                >保存配置</el-button
              ><el-button :loading="testing" :disabled="configSaving" @click="testConfig"
                >测试已保存连接</el-button
              ></el-form-item
            >
          </el-form>
          <el-alert
            v-if="connectionMessage"
            :title="connectionMessage"
            :type="connectionSuccess ? 'success' : 'error'"
            show-icon
            :closable="false"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </ContentWrap>

  <el-dialog v-model="baseVisible" :title="baseForm.id ? '编辑知识库' : '新建知识库'" width="520px"
    ><el-form label-width="80px" @submit.prevent
      ><el-form-item label="名称" required
        ><el-input v-model="baseForm.name" maxlength="100" /></el-form-item
      ><el-form-item label="描述"
        ><el-input
          v-model="baseForm.description"
          type="textarea"
          :rows="3"
          maxlength="1000" /></el-form-item></el-form
    ><template #footer
      ><el-button @click="baseVisible = false">取消</el-button
      ><el-button type="primary" :loading="baseSaving" @click="saveBase">保存</el-button></template
    ></el-dialog
  >
  <el-dialog
    v-model="uploadVisible"
    title="上传并解析知识文档"
    width="560px"
    :close-on-click-modal="false"
    ><el-input
      v-model="uploadTitle"
      placeholder="文档标题（留空使用文件名）"
      maxlength="200"
      class="assistant-upload-title"
    /><el-upload
      ref="uploadElement"
      drag
      :auto-upload="false"
      :limit="1"
      accept=".txt,.md,.csv,.docx,.pdf"
      :on-change="fileChanged"
      :on-remove="() => (uploadFile = undefined)"
      ><div>点击或拖入文档</div
      ><template #tip><div>单个文件最多5MB。解析后的草稿需审核发布。</div></template></el-upload
    ><template #footer
      ><el-button :disabled="uploading" @click="uploadVisible = false">取消</el-button
      ><el-button
        type="primary"
        :loading="uploading"
        :disabled="!uploadFile"
        @click="uploadDocument"
        >上传并分块</el-button
      ></template
    ></el-dialog
  >
  <el-dialog v-model="citationVisible" title="可追溯引用" width="760px"
    ><template v-if="activeCitation"
      ><h3>{{ activeCitation.title }}</h3
      ><p
        >{{ activeCitation.fileName }} · v{{ activeCitation.version }} · {{ activeCitation.heading
        }}{{ activeCitation.pageNumber ? ` · 第${activeCitation.pageNumber}页` : '' }} · 第{{
          activeCitation.chunkIndex + 1
        }}段</p
      ><pre class="assistant-source-text">{{ activeCitation.content }}</pre
      ><p class="assistant-hash">片段SHA-256：{{ activeCitation.contentHash }}</p
      ><p>引用保留提问时的原文快照。文档撤回或删除后，历史引用快照仍用于核对当时依据。</p
      ><el-button @click="preview(activeCitation.documentId)"
        >查看当前原文与全部分块</el-button
      ></template
    ></el-dialog
  >
  <el-dialog v-model="previewVisible" title="原文与分块" width="85%"
    ><div v-loading="previewLoading" v-if="previewDocument"
      ><h3>{{ previewDocument.title }} · v{{ previewDocument.version }}</h3
      ><p
        >{{ previewDocument.fileName }} · {{ previewDocument.chunkCount }} 段 ·
        {{ previewDocument.status === 'PUBLISHED' ? '已发布' : '草稿' }}</p
      ><el-tabs
        ><el-tab-pane label="解析原文">
          <pre class="assistant-source-text assistant-full-text">{{
            previewDocument.contentText
          }}</pre></el-tab-pane
        ><el-tab-pane label="分块索引"
          ><article
            v-for="chunk in previewDocument.chunks"
            :key="chunk.chunkId"
            class="assistant-chunk"
            ><b
              >第{{ chunk.chunkIndex + 1 }}段 · {{ chunk.heading
              }}{{ chunk.pageNumber ? ` · 第${chunk.pageNumber}页` : '' }}</b
            ><pre class="assistant-source-text">{{ chunk.content }}</pre
            ><small class="assistant-hash"
              >字符位置 {{ chunk.startOffset }}—{{ chunk.endOffset }} · SHA-256
              {{ chunk.contentHash }}</small
            ></article
          ></el-tab-pane
        ></el-tabs
      ></div
    ></el-dialog
  >
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile, UploadInstance } from 'element-plus'
import {
  AssistantApi,
  type AssistantMessage,
  type AssistantSession,
  type Citation,
  type KnowledgeBase,
  type KnowledgeDocument,
  type ModelConfig,
  type ModelStatus,
  type SearchResult
} from '@/api/mes/assistant'
import { checkPermi } from '@/utils/permission'

defineOptions({ name: 'MesAssistant' })
const canChat = checkPermi(['mes:assistant:chat'])
const canKnowledge = checkPermi(['mes:assistant:knowledge'])
const canManage = checkPermi(['mes:assistant:manage'])
const canConfig = checkPermi(['mes:assistant:config'])
const tab = ref(canChat || canKnowledge ? 'chat' : canManage ? 'knowledge' : 'config')
const error = ref('')
const modelStatus = ref<ModelStatus>()
const bases = ref<KnowledgeBase[]>([])
const selectedBases = ref<number[]>([])
const knowledgeBaseId = ref<number>()
const currentBase = computed(() => bases.value.find((base) => base.id === knowledgeBaseId.value))
const sessions = ref<AssistantSession[]>([])
const sessionId = ref<number>()
const messages = ref<AssistantMessage[]>([])
const question = ref('')
const chatNotice = ref('')
const sending = ref(false)
const searching = ref(false)
const historyLoading = ref(false)
const searchResult = ref<SearchResult>()
const messagesElement = ref<HTMLElement>()
const documents = ref<KnowledgeDocument[]>([])
const documentsLoading = ref(false)
const baseVisible = ref(false)
const baseSaving = ref(false)
const baseForm = reactive<{ id?: number; name: string; description: string }>({
  name: '',
  description: ''
})
const uploadVisible = ref(false)
const uploadTitle = ref('')
const uploadFile = ref<File>()
const uploadElement = ref<UploadInstance>()
const uploading = ref(false)
const citationVisible = ref(false)
const activeCitation = ref<Citation>()
const previewVisible = ref(false)
const previewLoading = ref(false)
const previewDocument = ref<Awaited<ReturnType<typeof AssistantApi.document>>>()
const config = reactive<ModelConfig>({
  provider: 'openai',
  baseUrl: 'https://api.openai.com/v1',
  model: '',
  apiKeyConfigured: false,
  temperature: 0.2,
  maxTokens: 1024,
  contextTokens: 8192,
  enabled: false
})
const apiKey = ref('')
const clearApiKey = ref(false)
const configLoading = ref(false)
const configSaving = ref(false)
const testing = ref(false)
const connectionMessage = ref('')
const connectionSuccess = ref(false)
const time = (value: string) => (value ? value.replace('T', ' ').slice(0, 16) : '')
const failure = (exception: unknown, fallback: string) => {
  error.value = exception instanceof Error ? exception.message : fallback
}
const cancelled = (exception: unknown) => exception === 'cancel' || exception === 'close'
async function scrollMessages() {
  await nextTick()
  messagesElement.value?.scrollTo({ top: messagesElement.value.scrollHeight, behavior: 'smooth' })
}
async function loadBases() {
  if (!canKnowledge && !canManage) return
  bases.value = await AssistantApi.bases()
  if (!bases.value.some((base) => base.id === knowledgeBaseId.value))
    knowledgeBaseId.value = bases.value[0]?.id
  selectedBases.value = selectedBases.value.filter((id) =>
    bases.value.some((base) => base.id === id)
  )
}
async function loadSessions() {
  if (canChat) sessions.value = await AssistantApi.sessions()
}
async function newSession() {
  if (sending.value) return
  sessionId.value = undefined
  messages.value = []
  searchResult.value = undefined
  chatNotice.value = ''
  question.value = ''
}
async function openSession(id: number) {
  historyLoading.value = true
  error.value = ''
  try {
    const session = await AssistantApi.session(id)
    sessionId.value = id
    messages.value = session.messages || []
    searchResult.value = undefined
    chatNotice.value = ''
    await scrollMessages()
  } catch (exception) {
    failure(exception, '会话加载失败')
  } finally {
    historyLoading.value = false
  }
}
async function renameSession() {
  if (!sessionId.value) return
  try {
    const result = await ElMessageBox.prompt('输入会话标题', '重命名', {
      inputValue: sessions.value.find((item) => item.id === sessionId.value)?.title || '',
      inputValidator: (value) => (!!value?.trim() && value.length <= 100) || '请输入1至100字标题'
    })
    await AssistantApi.renameSession(sessionId.value, result.value.trim())
    await loadSessions()
  } catch (exception) {
    if (!cancelled(exception)) failure(exception, '重命名失败')
  }
}
async function removeSession() {
  if (!sessionId.value) return
  try {
    await ElMessageBox.confirm('删除当前会话及消息记录？', '删除会话', { type: 'warning' })
    await AssistantApi.deleteSession(sessionId.value)
    await newSession()
    await loadSessions()
  } catch (exception) {
    if (!cancelled(exception)) failure(exception, '删除失败')
  }
}
async function send() {
  if (!canChat || !canKnowledge || sending.value || !question.value.trim()) return
  sending.value = true
  error.value = ''
  searchResult.value = undefined
  try {
    if (!sessionId.value) sessionId.value = await AssistantApi.createSession()
    const result = await AssistantApi.send(
      sessionId.value,
      question.value.trim(),
      selectedBases.value
    )
    chatNotice.value = result.message
    question.value = ''
    const session = await AssistantApi.session(sessionId.value)
    messages.value = session.messages || []
    await loadSessions()
    await scrollMessages()
  } catch (exception) {
    failure(exception, '发送失败，请重试')
  } finally {
    sending.value = false
  }
}
async function search() {
  if (!canKnowledge || searching.value || !question.value.trim()) return
  if (question.value.length > 1000) return ElMessage.warning('独立检索问题最多1000字')
  searching.value = true
  error.value = ''
  try {
    searchResult.value = await AssistantApi.search(question.value.trim(), selectedBases.value)
    await scrollMessages()
  } catch (exception) {
    failure(exception, '检索失败')
  } finally {
    searching.value = false
  }
}
async function selectBase(id: number) {
  knowledgeBaseId.value = id
  await loadDocuments()
}
async function loadDocuments() {
  if (!knowledgeBaseId.value) {
    documents.value = []
    return
  }
  documentsLoading.value = true
  try {
    documents.value = await AssistantApi.documents(knowledgeBaseId.value)
  } catch (exception) {
    failure(exception, '文档加载失败')
  } finally {
    documentsLoading.value = false
  }
}
function editBase(base?: KnowledgeBase) {
  baseForm.id = base?.id
  baseForm.name = base?.name || ''
  baseForm.description = base?.description || ''
  baseVisible.value = true
}
async function saveBase() {
  if (!baseForm.name.trim()) return ElMessage.warning('请输入知识库名称')
  baseSaving.value = true
  try {
    knowledgeBaseId.value = await AssistantApi.saveBase({ ...baseForm, name: baseForm.name.trim() })
    baseVisible.value = false
    await loadBases()
    await loadDocuments()
  } catch (exception) {
    failure(exception, '知识库保存失败')
  } finally {
    baseSaving.value = false
  }
}
async function removeBase() {
  if (!knowledgeBaseId.value) return
  try {
    await ElMessageBox.confirm('知识库为空时才能删除，确认继续？', '删除知识库', {
      type: 'warning'
    })
    await AssistantApi.deleteBase(knowledgeBaseId.value)
    await loadBases()
    await loadDocuments()
  } catch (exception) {
    if (!cancelled(exception)) failure(exception, '删除失败')
  }
}
function fileChanged(file: UploadFile) {
  if (file.size && file.size > 5 * 1024 * 1024) {
    ElMessage.warning('文件不能超过5MB')
    uploadElement.value?.clearFiles()
    uploadFile.value = undefined
    return
  }
  uploadFile.value = file.raw
}
async function uploadDocument() {
  if (!knowledgeBaseId.value || !uploadFile.value) return
  uploading.value = true
  try {
    const result = await AssistantApi.upload(
      knowledgeBaseId.value,
      uploadTitle.value,
      uploadFile.value
    )
    ElMessage.success(`解析完成，生成${result.chunkCount}个片段，请审核后发布`)
    uploadVisible.value = false
    uploadTitle.value = ''
    uploadFile.value = undefined
    uploadElement.value?.clearFiles()
    await loadBases()
    await loadDocuments()
  } catch (exception) {
    failure(exception, '文档解析失败')
  } finally {
    uploading.value = false
  }
}
async function publishDocument(document: KnowledgeDocument) {
  try {
    await ElMessageBox.confirm(
      document.status === 'PUBLISHED'
        ? '撤回后不再参与新提问检索，历史引用快照保留。'
        : '确认已审核文档原文，并发布供当前租户知识检索？',
      document.status === 'PUBLISHED' ? '撤回文档' : '发布文档'
    )
    await AssistantApi.publish(document.id, document.status !== 'PUBLISHED')
    await loadBases()
    await loadDocuments()
  } catch (exception) {
    if (!cancelled(exception)) failure(exception, '文档状态更新失败')
  }
}
async function removeDocument(document: KnowledgeDocument) {
  try {
    await ElMessageBox.confirm(
      `删除“${document.title}”及检索分块？历史会话引用快照保留。`,
      '删除文档',
      { type: 'warning' }
    )
    await AssistantApi.deleteDocument(document.id)
    await loadBases()
    await loadDocuments()
  } catch (exception) {
    if (!cancelled(exception)) failure(exception, '删除文档失败')
  }
}
async function preview(id: number) {
  previewLoading.value = true
  error.value = ''
  try {
    previewDocument.value = await AssistantApi.document(id)
    previewVisible.value = true
  } catch (exception) {
    failure(exception, '原文不存在、已撤回或无权访问')
    ElMessage.warning('当前原文不可访问；历史引用快照仍可核对')
  } finally {
    previewLoading.value = false
  }
}
function viewCitation(citation: Citation) {
  activeCitation.value = citation
  citationVisible.value = true
}
async function downloadDocument(document: KnowledgeDocument) {
  try {
    const blob = await AssistantApi.download(document.id)
    const objectUrl = URL.createObjectURL(blob)
    const link = window.document.createElement('a')
    link.href = objectUrl
    link.download = document.fileName
    link.click()
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
  } catch (exception) {
    failure(exception, '文档下载失败')
  }
}
function changeProvider() {
  if (config.provider === 'ollama' && config.baseUrl === 'https://api.openai.com/v1')
    config.baseUrl = 'http://127.0.0.1:11434'
  else if (config.provider === 'openai' && config.baseUrl === 'http://127.0.0.1:11434')
    config.baseUrl = 'https://api.openai.com/v1'
}
async function saveConfig() {
  if (!config.model.trim() || !config.baseUrl.trim())
    return ElMessage.warning('请填写服务地址和实际模型名称')
  configSaving.value = true
  error.value = ''
  connectionMessage.value = ''
  try {
    const { apiKeyConfigured: _configured, ...settings } = config
    Object.assign(
      config,
      await AssistantApi.saveModelConfig({
        ...settings,
        apiKey: apiKey.value,
        clearApiKey: clearApiKey.value
      })
    )
    apiKey.value = ''
    clearApiKey.value = false
    modelStatus.value = await AssistantApi.status()
    ElMessage.success('配置已安全保存')
  } catch (exception) {
    failure(exception, '配置保存失败')
  } finally {
    configSaving.value = false
  }
}
async function testConfig() {
  testing.value = true
  connectionMessage.value = ''
  try {
    const result = await AssistantApi.testModelConfig()
    connectionSuccess.value = true
    connectionMessage.value = result.message
  } catch (exception) {
    connectionSuccess.value = false
    connectionMessage.value =
      exception instanceof Error ? exception.message : '连接测试失败，请检查服务、模型名称和密钥'
  } finally {
    testing.value = false
  }
}
onMounted(async () => {
  try {
    modelStatus.value = await AssistantApi.status()
    await Promise.all([loadBases(), loadSessions()])
    await loadDocuments()
    if (canConfig) {
      configLoading.value = true
      Object.assign(config, await AssistantApi.modelConfig())
    }
  } catch (exception) {
    failure(exception, '助手初始化失败')
  } finally {
    configLoading.value = false
  }
})
</script>

<style scoped>
.assistant-heading,
.assistant-chat-toolbar,
.assistant-document-heading,
.assistant-composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.assistant-heading h2,
.assistant-document-heading h3 {
  margin: 0 0 6px;
}

.assistant-heading p,
.assistant-document-heading p,
.assistant-welcome p,
.assistant-method,
.assistant-search-result p,
.assistant-field-help {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.assistant-alert {
  margin: 12px 0;
}

.assistant-layout,
.assistant-knowledge-layout {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  min-height: 580px;
  gap: 20px;
}

.assistant-without-sidebar {
  grid-template-columns: minmax(0, 1fr);
}

.assistant-sidebar {
  min-width: 0;
  padding-right: 16px;
  border-right: 1px solid var(--el-border-color-lighter);
}

.assistant-sidebar > .el-button {
  width: 100%;
  margin-bottom: 16px;
}

.assistant-session-list {
  max-height: 600px;
  overflow: auto;
}

.assistant-session {
  display: flex;
  width: 100%;
  padding: 12px;
  margin-bottom: 7px;
  color: var(--el-text-color-primary);
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 8px;
  flex-direction: column;
  gap: 7px;
}

.assistant-session span {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assistant-session small {
  color: var(--el-text-color-secondary);
}

.assistant-session.active,
.assistant-session:hover {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.assistant-chat {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
}

.assistant-chat-toolbar {
  justify-content: flex-start;
  flex-wrap: wrap;
}

.assistant-base-select {
  width: 280px;
}

.assistant-messages {
  height: 480px;
  padding: 8px 12px 16px 0;
  overflow: auto;
}

.assistant-welcome {
  padding: 80px 24px;
  text-align: center;
}

.assistant-message {
  padding: 16px;
  margin: 12px 0;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}

.assistant-message.user {
  margin-left: 25px;
  background: var(--el-color-primary-light-9);
}

.assistant-message-label {
  display: flex;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  justify-content: space-between;
  gap: 8px;
}

.assistant-message-label small {
  font-weight: 400;
}

.assistant-message-content {
  padding: 10px 0;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.assistant-citations {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.assistant-citations button,
.assistant-search-card {
  display: flex;
  padding: 10px 12px;
  color: var(--el-text-color-primary);
  text-align: left;
  cursor: pointer;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  flex-direction: column;
  gap: 7px;
}

.assistant-citations span,
.assistant-search-card small {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.assistant-search-card {
  width: 100%;
  margin: 10px 0;
}

.assistant-search-card span {
  max-height: 180px;
  overflow: hidden;
  line-height: 1.7;
  white-space: pre-wrap;
}

.assistant-composer-actions {
  margin-top: 10px;
  justify-content: flex-end;
  flex-wrap: wrap;
}

.assistant-composer-actions > span {
  min-width: 180px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex: 1;
}

.assistant-documents {
  min-width: 0;
}

.assistant-document-heading {
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.assistant-document-table {
  margin-top: 16px;
}

.assistant-config {
  max-width: 850px;
  padding: 10px 0 20px;
}

.assistant-config-form {
  margin-top: 24px;
}

.assistant-field-help {
  width: 100%;
  margin: 6px 0;
  line-height: 1.7;
}

.assistant-upload-title {
  margin-bottom: 16px;
}

.assistant-source-text {
  padding: 16px;
  font-family: inherit;
  line-height: 1.8;
  white-space: pre-wrap;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  overflow-wrap: anywhere;
}

.assistant-full-text {
  max-height: 62vh;
  overflow: auto;
}

.assistant-hash {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  overflow-wrap: anywhere;
}

.assistant-chunk {
  padding: 16px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

@media (width <= 960px) {
  .assistant-layout,
  .assistant-knowledge-layout {
    grid-template-columns: 1fr;
  }

  .assistant-sidebar {
    max-height: 200px;
    padding: 0 0 12px;
    overflow: auto;
    border-right: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  .assistant-heading {
    flex-wrap: wrap;
  }

  .assistant-messages {
    height: 450px;
  }
}
</style>
