import request from '@/config/axios'

export interface KnowledgeBase {
  id: number
  name: string
  description: string
  documentCount: number
}
export interface KnowledgeDocument {
  id: number
  knowledgeBaseId: number
  title: string
  fileName: string
  fileType: string
  fileSize: number
  fileSha256: string
  chunkCount: number
  version: number
  status: 'DRAFT' | 'PUBLISHED'
  createTime: number | string
}
export interface Citation {
  chunkId: number
  documentId: number
  knowledgeBaseId: number
  title: string
  fileName: string
  chunkIndex: number
  heading: string
  pageNumber?: number
  startOffset: number
  endOffset: number
  content: string
  contentHash: string
  version: number
  score: number
}
export interface AssistantMessage {
  id: number
  role: 'user' | 'assistant'
  content: string
  status: 'PROCESSING' | 'ANSWERED' | 'MODEL_NOT_CONFIGURED' | 'MODEL_ERROR'
  citations: Citation[]
  modelName: string
  createTime: number | string
}
export interface AssistantSession {
  id: number
  title: string
  updateTime: number | string
  messages?: AssistantMessage[]
}
export interface ModelStatus {
  configured: boolean
  provider: string
  model: string
  retrievalMode: 'lexical_bm25'
  message: string
}
export interface ModelConfig {
  provider: 'openai' | 'ollama'
  baseUrl: string
  model: string
  apiKeyConfigured: boolean
  temperature: number
  maxTokens: number
  contextTokens: number
  enabled: boolean
}
export interface SearchResult {
  mode: 'lexical_bm25'
  query: string
  eligibleChunkCount: number
  items: Citation[]
}
const url = '/mes/assistant'
export const AssistantApi = {
  status: () => request.get<ModelStatus>({ url: `${url}/status` }),
  bases: () => request.get<KnowledgeBase[]>({ url: `${url}/bases` }),
  saveBase: (data: { id?: number; name: string; description: string }) =>
    request.post<number>({ url: `${url}/base`, data }),
  deleteBase: (id: number) => request.delete({ url: `${url}/base`, params: { id } }),
  documents: (knowledgeBaseId: number) =>
    request.get<KnowledgeDocument[]>({ url: `${url}/documents`, params: { knowledgeBaseId } }),
  document: (id: number) =>
    request.get<KnowledgeDocument & { contentText: string; chunks: Citation[] }>({
      url: `${url}/document`,
      params: { id }
    }),
  upload: (knowledgeBaseId: number, title: string, file: File) => {
    const data = new FormData()
    data.append('knowledgeBaseId', String(knowledgeBaseId))
    data.append('title', title)
    data.append('file', file)
    return request.post<{ id: number; chunkCount: number }>({
      url: `${url}/document/upload`,
      data,
      headersType: 'multipart/form-data'
    })
  },
  publish: (id: number, published: boolean) =>
    request.put({ url: `${url}/document/publish`, params: { id, published } }),
  deleteDocument: (id: number) => request.delete({ url: `${url}/document`, params: { id } }),
  download: (id: number) =>
    request.download<Blob>({ url: `${url}/document/download`, params: { id } }),
  search: (query: string, knowledgeBaseIds: number[]) =>
    request.post<SearchResult>({
      url: `${url}/search`,
      data: { query, knowledgeBaseIds, limit: 5 }
    }),
  sessions: () => request.get<AssistantSession[]>({ url: `${url}/sessions` }),
  createSession: (title = '新会话') =>
    request.post<number>({ url: `${url}/session`, data: { title } }),
  session: (id: number) => request.get<AssistantSession>({ url: `${url}/session`, params: { id } }),
  renameSession: (id: number, title: string) =>
    request.put({ url: `${url}/session`, params: { id }, data: { title } }),
  deleteSession: (id: number) => request.delete({ url: `${url}/session`, params: { id } }),
  send: (sessionId: number, question: string, knowledgeBaseIds: number[]) =>
    request.post<{ status: string; message: string; answer: string | null; citations: Citation[] }>(
      {
        url: `${url}/message`,
        data: { sessionId, question, knowledgeBaseIds },
        timeout: 90000
      }
    ),
  modelConfig: () => request.get<ModelConfig>({ url: `${url}/model-config` }),
  saveModelConfig: (
    data: Omit<ModelConfig, 'apiKeyConfigured'> & { apiKey: string; clearApiKey: boolean }
  ) => request.put<ModelConfig>({ url: `${url}/model-config`, data }),
  testModelConfig: () =>
    request.post<{ connected: boolean; message: string }>({
      url: `${url}/model-config/test`,
      timeout: 90000
    })
}
