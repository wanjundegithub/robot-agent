import type {
  AnalyticsDashboard,
  ApiGroupSummary,
  ApiItemSummary,
  ApiTestResult,
  ApiValidationResult,
  CostAlert,
  ExecutionDetail,
  FunctionFragmentTestRunResult,
  FunctionFragmentValidationResult,
  KnowledgeDocument,
  KnowledgeSearchResult,
  KnowledgeSearchStreamEvent,
  KnowledgeSpace,
  KnowledgeTask,
  Message,
  RagEvaluationResponse,
  ReplayResponse,
  RobotBinding,
  RobotConfig,
  SubflowRecommendationResponse,
  OperationalReadiness,
  ModelRecordConfig,
  ModelProviderConfig,
  PagedModelRecordResponse,
  ProviderValidationResult,
  SessionSummary,
  WorkflowDraftValidationResponse,
  WorkflowSpace,
  WorkflowSummary,
  WorkflowVersionSummary,
} from '../types'
import { apiFetch } from './callLogger'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'
const ADMIN_USER_ID = 'demo-admin'

export type ApiItemPayload = {
  id?: number
  apiName: string
  description?: string
  enabled?: boolean
  requestUrl: string
  requestMethod: string
  authMode?: ApiAuthMode
  authConfig?: ApiAuthConfigPayload
  headers?: Array<{ key: string; value: string; enabled: boolean }>
  inputSchema: string
  outputSchema: string
  urlVariables?: Record<string, string>
  body?: Record<string, unknown>
}

export type ApiAuthType = 'NO_AUTH' | 'API_KEY' | 'BEARER' | 'BASIC' | 'DIGEST'
export type ApiAuthMode = 'INHERIT' | 'NONE' | 'CUSTOM'
export type ApiKeyAddTo = 'HEADER' | 'QUERY'

export type ApiAuthConfigPayload = {
  authType: ApiAuthType
  key?: string
  value?: string
  addTo?: ApiKeyAddTo
  token?: string
  username?: string
  password?: string
  realm?: string
  nonce?: string
  algorithm?: string
  qop?: string
}

export type RobotConfigPayload = {
  workspace_id?: number
  robot_code: string
  name: string
  description?: string
  avatar?: string
  opening_message?: string
  status?: string
  default_model_code?: string
  route_strategy?: string
  created_by?: string
}

export async function getRobots(): Promise<RobotConfig[]> {
  const response = await apiFetch(`${API_BASE_URL}/robots`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveRobot(payload: RobotConfigPayload): Promise<RobotConfig> {
  const response = await apiFetch(`${API_BASE_URL}/robots`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function publishRobot(robotCode: string): Promise<RobotConfig> {
  const response = await apiFetch(`${API_BASE_URL}/robots/${encodeURIComponent(robotCode)}/publish`, {
    method: 'POST',
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getRobotBindings(robotCode: string): Promise<RobotBinding[]> {
  const response = await apiFetch(`${API_BASE_URL}/robots/${encodeURIComponent(robotCode)}/bindings`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function updateRobotBindings(
  robotCode: string,
  payload: { workspace_id?: number; workflow_space_codes: string[]; kb_codes: string[] }
): Promise<RobotBinding[]> {
  const response = await apiFetch(`${API_BASE_URL}/robots/${encodeURIComponent(robotCode)}/bindings`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getWorkflowSpaces(): Promise<WorkflowSpace[]> {
  const response = await apiFetch(`${API_BASE_URL}/workflow-spaces`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveWorkflowSpace(
  payload: { workspace_id?: number; space_code: string; name: string; description?: string; created_by?: string },
  currentUserId: string
): Promise<WorkflowSpace> {
  const response = await apiFetch(`${API_BASE_URL}/workflow-spaces`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

async function parseApiError(response: Response): Promise<never> {
  const fallback = `HTTP error! status: ${response.status}`
  try {
    const text = await response.text()
    if (!text.trim()) {
      throw new Error(fallback)
    }
    try {
      const data = JSON.parse(text) as { message?: string; error?: string }
      const message = data.message || data.error
      throw new Error(message && message.trim() ? message : text)
    } catch (parseError) {
      if (parseError instanceof Error && parseError.message !== text) {
        throw parseError
      }
      throw new Error(text || fallback)
    }
  } catch (error) {
    throw error instanceof Error ? error : new Error(fallback)
  }
}

export async function getSessionMessages(sessionId: string): Promise<Message[]> {
  const response = await apiFetch(`${API_BASE_URL}/sessions/${encodeURIComponent(sessionId)}/messages`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getExecution(executionId: string): Promise<ExecutionDetail> {
  const response = await apiFetch(`${API_BASE_URL}/executions/${executionId}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getSessionExecutions(sessionId: string): Promise<ExecutionDetail[]> {
  const response = await apiFetch(`${API_BASE_URL}/executions?sessionId=${encodeURIComponent(sessionId)}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function createSession(payload: {
  userId: string
  workspaceId?: number
  variables?: string
}): Promise<SessionSummary> {
  const response = await apiFetch(`${API_BASE_URL}/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      userId: payload.userId,
      workspaceId: payload.workspaceId ?? 1,
      variables: payload.variables ?? null,
    }),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getSession(sessionId: string): Promise<SessionSummary> {
  const response = await apiFetch(`${API_BASE_URL}/sessions/${encodeURIComponent(sessionId)}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteSession(sessionId: string): Promise<void> {
  const response = await apiFetch(`${API_BASE_URL}/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function getSessionsByUserId(userId: string): Promise<SessionSummary[]> {
  const response = await apiFetch(`${API_BASE_URL}/sessions?userId=${encodeURIComponent(userId)}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getAnalyticsDashboard(sessionId?: string): Promise<AnalyticsDashboard> {
  const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
  const response = await apiFetch(`${API_BASE_URL}/analytics/dashboard${query}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getCostAlerts(sessionId?: string): Promise<CostAlert[]> {
  const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
  const response = await apiFetch(`${API_BASE_URL}/analytics/cost-alerts${query}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getExecutionReplay(executionId: string): Promise<ReplayResponse> {
  const response = await apiFetch(`${API_BASE_URL}/executions/${executionId}/replay`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getSubflowRecommendations(
  workflowCode: string,
  message: string
): Promise<SubflowRecommendationResponse> {
  const response = await apiFetch(
    `${API_BASE_URL}/workflows/${workflowCode}/subflow-recommendations?message=${encodeURIComponent(message)}`
  )
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function runRagEvaluation(dataset?: Array<Record<string, unknown>>): Promise<RagEvaluationResponse> {
  const response = await apiFetch(`${API_BASE_URL}/evaluations/rag`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ dataset: dataset ?? null }),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getKnowledgeSpaces(workspaceId?: number): Promise<KnowledgeSpace[]> {
  const query = workspaceId ? `?workspaceId=${encodeURIComponent(String(workspaceId))}` : ''
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases${query}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function createKnowledgeSpace(
  payload: {
    name: string
    description?: string
  },
  currentUserId: string
): Promise<KnowledgeSpace> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function updateKnowledgeSpace(
  kbCode: string,
  payload: {
    name: string
    description?: string
  },
  currentUserId: string
): Promise<KnowledgeSpace> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/${encodeURIComponent(kbCode)}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteKnowledgeSpace(kbCode: string, currentUserId: string): Promise<void> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/${encodeURIComponent(kbCode)}`, {
    method: 'DELETE',
    headers: {
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function getKnowledgeDocuments(kbCode: string): Promise<KnowledgeDocument[]> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/${encodeURIComponent(kbCode)}/documents`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function createTextKnowledgeDocument(
  kbCode: string,
  payload: { title: string; description?: string; content: string },
  currentUserId: string
): Promise<KnowledgeDocument> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/${encodeURIComponent(kbCode)}/documents/text`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function updateKnowledgeDocument(
  docId: string,
  payload: { title: string; description?: string; content?: string },
  currentUserId: string
): Promise<KnowledgeDocument> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/documents/${encodeURIComponent(docId)}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteKnowledgeDocument(docId: string, currentUserId: string): Promise<void> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/documents/${encodeURIComponent(docId)}`, {
    method: 'DELETE',
    headers: {
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function uploadKnowledgeDocument(
  kbCode: string,
  file: File,
  currentUserId: string
): Promise<KnowledgeDocument> {
  const formData = new FormData()
  formData.append('file', file)
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/${encodeURIComponent(kbCode)}/documents/files`, {
    method: 'POST',
    headers: {
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: formData,
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getKnowledgeTask(taskId: string): Promise<KnowledgeTask> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/tasks/${encodeURIComponent(taskId)}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getKnowledgeDocumentTasks(docId: string): Promise<KnowledgeTask[]> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/documents/${encodeURIComponent(docId)}/tasks`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function retryKnowledgeTask(taskId: string, currentUserId: string): Promise<KnowledgeTask> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/tasks/${encodeURIComponent(taskId)}/retry`, {
    method: 'POST',
    headers: {
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteKnowledgeTask(taskId: string, currentUserId: string): Promise<void> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/tasks/${encodeURIComponent(taskId)}`, {
    method: 'DELETE',
    headers: {
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export type KnowledgeSearchPayload = {
  query: string
  kbCodes: string[]
  retrievalMode?: 'hybrid' | 'keyword' | 'vector' | string
  topK?: number
  scoreThreshold?: number
  generateAnswer?: boolean
  currentUserId?: string
}

export async function searchKnowledge(payload: KnowledgeSearchPayload): Promise<KnowledgeSearchResult> {
  const { currentUserId, ...requestPayload } = payload
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/search`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify({
      retrievalMode: 'hybrid',
      topK: 5,
      generateAnswer: true,
      ...requestPayload,
    }),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function searchKnowledgeStream(
  payload: KnowledgeSearchPayload,
  onEvent?: (event: KnowledgeSearchStreamEvent) => void
): Promise<KnowledgeSearchResult> {
  const { currentUserId, ...requestPayload } = payload
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/search/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify({
      retrievalMode: 'hybrid',
      topK: 5,
      generateAnswer: true,
      ...requestPayload,
    }),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await readKnowledgeSearchStream(response, onEvent)
}

async function readKnowledgeSearchStream(
  response: Response,
  onEvent?: (event: KnowledgeSearchStreamEvent) => void
): Promise<KnowledgeSearchResult> {
  const reader = response.body?.getReader()
  if (!reader) {
    throw new Error('Knowledge search stream is not readable')
  }

  const decoder = new TextDecoder()
  let buffer = ''
  let result: KnowledgeSearchResult | null = null
  let failureMessage: string | null = null

  const handleBlock = (block: string) => {
    const event = parseKnowledgeSearchStreamBlock(block)
    if (!event) return
    onEvent?.(event)
    if (event.type === 'completed' && event.result) {
      result = event.result
    }
    if (event.type === 'failed') {
      failureMessage = event.message || 'Knowledge search failed'
    }
  }

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let boundary = findSseBoundary(buffer)
    while (boundary) {
      const block = buffer.slice(0, boundary.index)
      buffer = buffer.slice(boundary.index + boundary.length)
      handleBlock(block)
      boundary = findSseBoundary(buffer)
    }
  }

  buffer += decoder.decode()
  if (buffer.trim()) {
    handleBlock(buffer)
  }
  if (failureMessage) {
    throw new Error(failureMessage)
  }
  if (!result) {
    throw new Error('Knowledge search stream ended without result')
  }
  return result
}

function findSseBoundary(buffer: string): { index: number; length: number } | null {
  const lfIndex = buffer.indexOf('\n\n')
  const crlfIndex = buffer.indexOf('\r\n\r\n')
  if (lfIndex < 0 && crlfIndex < 0) return null
  if (lfIndex >= 0 && (crlfIndex < 0 || lfIndex < crlfIndex)) {
    return { index: lfIndex, length: 2 }
  }
  return { index: crlfIndex, length: 4 }
}

function parseKnowledgeSearchStreamBlock(block: string): KnowledgeSearchStreamEvent | null {
  const lines = block.split(/\r?\n/)
  let eventType = ''
  const dataLines: string[] = []
  lines.forEach((line) => {
    if (line.startsWith('event:')) {
      eventType = line.slice('event:'.length).trim()
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart())
    }
  })
  if (dataLines.length === 0) {
    return null
  }
  const event = JSON.parse(dataLines.join('\n')) as KnowledgeSearchStreamEvent
  return event.type ? event : { ...event, type: eventType }
}

export async function getWorkflows(): Promise<WorkflowSummary[]> {
  const response = await apiFetch(`${API_BASE_URL}/workflows`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getPublishedWorkflows(): Promise<WorkflowSummary[]> {
  const response = await apiFetch(`${API_BASE_URL}/workflows/published`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteWorkflow(workflowCode: string, currentUserId: string): Promise<void> {
  const response = await apiFetch(`${API_BASE_URL}/workflows/${encodeURIComponent(workflowCode)}`, {
    method: 'DELETE',
    headers: {
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function getWorkflowVersions(workflowCode: string): Promise<WorkflowVersionSummary[]> {
  const response = await apiFetch(`${API_BASE_URL}/workflows/${workflowCode}/versions`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function archiveWorkflowVersion(workflowCode: string, version: string, currentUserId: string): Promise<WorkflowVersionSummary> {
  const response = await apiFetch(`${API_BASE_URL}/workflows/${workflowCode}/versions/${encodeURIComponent(version)}/archive`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId,
    },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteWorkflowVersion(workflowCode: string, version: string, currentUserId: string): Promise<void> {
  const response = await apiFetch(`${API_BASE_URL}/workflows/${workflowCode}/versions/${encodeURIComponent(version)}`, {
    method: 'DELETE',
    headers: {
      'X-User-Id': currentUserId,
    },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function getModelProviders(): Promise<ModelProviderConfig[]> {
  const response = await apiFetch(`${API_BASE_URL}/model-config/providers`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveModelProvider(
  payload: {
    provider_code: string
    provider_name: string
    provider_type: string
    base_url: string
    api_key_secret_ref?: string
    enabled: boolean
  },
  currentUserId: string,
  existingProviderCode?: string
): Promise<ModelProviderConfig> {
  const isUpdate = Boolean(existingProviderCode)
  const response = await apiFetch(
    isUpdate ? `${API_BASE_URL}/model-config/providers/${existingProviderCode}` : `${API_BASE_URL}/model-config/providers`,
    {
      method: isUpdate ? 'PUT' : 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-User-Id': currentUserId,
      },
      body: JSON.stringify(payload),
    }
  )
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteModelProvider(providerCode: string, currentUserId: string): Promise<void> {
  const response = await apiFetch(`${API_BASE_URL}/model-config/providers/${encodeURIComponent(providerCode)}`, {
    method: 'DELETE',
    headers: {
      'X-User-Id': currentUserId,
    },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function validateModelProviderDraft(
  payload: {
    provider_type: string
    base_url: string
    api_key_secret_ref?: string
    request_body?: Record<string, unknown>
  },
  currentUserId: string
): Promise<ProviderValidationResult> {
  const response = await apiFetch(`${API_BASE_URL}/model-config/providers/validate-draft`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId,
    },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getModelRecords(params: {
  page: number
  pageSize: number
  keyword?: string
}): Promise<PagedModelRecordResponse> {
  const query = new URLSearchParams()
  query.set('page', String(params.page))
  query.set('pageSize', String(params.pageSize))
  query.set('page_size', String(params.pageSize))
  if (params.keyword && params.keyword.trim()) {
    query.set('keyword', params.keyword.trim())
  }
  const response = await apiFetch(`${API_BASE_URL}/model-config/models?${query.toString()}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getModelRecord(modelCode: string): Promise<ModelRecordConfig> {
  const response = await apiFetch(`${API_BASE_URL}/model-config/models/${encodeURIComponent(modelCode)}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveModelRecord(
  payload: {
    model_code: string
    custom_model_name: string
    provider: string
    model_name: string
    api_key: string
    base_url: string
    default_options?: Record<string, unknown>
  },
  currentUserId: string,
  existingModelCode?: string
): Promise<ModelRecordConfig> {
  const isUpdate = Boolean(existingModelCode)
  const response = await apiFetch(
    isUpdate
      ? `${API_BASE_URL}/model-config/models/${encodeURIComponent(existingModelCode || '')}`
      : `${API_BASE_URL}/model-config/models`,
    {
      method: isUpdate ? 'PUT' : 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-User-Id': currentUserId,
      },
      body: JSON.stringify(payload),
    }
  )
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteModelRecord(modelCode: string, currentUserId: string): Promise<void> {
  const response = await apiFetch(`${API_BASE_URL}/model-config/models/${encodeURIComponent(modelCode)}`, {
    method: 'DELETE',
    headers: {
      'X-User-Id': currentUserId,
    },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function testModelRecordConnection(
  payload: {
    model_code: string
    custom_model_name: string
    provider: string
    model_name: string
    api_key: string
    base_url: string
    default_options?: Record<string, unknown>
  },
  currentUserId: string
): Promise<Record<string, unknown>> {
  const response = await apiFetch(`${API_BASE_URL}/model-config/models/test`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId,
    },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveWorkflowDraft(
  workflowCode: string,
  payload: {
    workflowSpaceCode?: string
    workflowName: string
    workflowDescription?: string
    version: string
    definition: Record<string, unknown>
    workflowConfig: Record<string, unknown>
    workflowSnapshot: Record<string, unknown>
    currentUserId: string
  }
): Promise<WorkflowVersionSummary> {
  const editorMetaFromDefinition =
    payload.definition?.editor_meta &&
    typeof payload.definition.editor_meta === 'object' &&
    !Array.isArray(payload.definition.editor_meta)
      ? (payload.definition.editor_meta as Record<string, unknown>)
      : null

  const response = await apiFetch(`${API_BASE_URL}/workflows/${workflowCode}/drafts`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': payload.currentUserId,
    },
    body: JSON.stringify({
      workflow_code: workflowCode,
      workflow_space_code: payload.workflowSpaceCode,
      workflow_name: payload.workflowName,
      workflow_description: payload.workflowDescription ?? '',
      version: payload.version,
      definition: JSON.stringify(payload.definition),
      editor_meta: JSON.stringify(
        editorMetaFromDefinition || {
          layout_engine: 'reactflow',
          viewport: { x: 0, y: 0, zoom: 0.92 },
          readonly: false,
          last_saved_by: payload.currentUserId,
        }
      ),
      config: JSON.stringify(payload.workflowConfig),
      workflow_snapshot: JSON.stringify(payload.workflowSnapshot),
    }),
  })
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  return await response.json()
}

export async function validateWorkflowDraft(
  workflowCode: string,
  payload: {
    definition: Record<string, unknown>
    workflowConfig: Record<string, unknown>
  }
): Promise<WorkflowDraftValidationResponse> {
  const response = await apiFetch(`${API_BASE_URL}/workflows/${workflowCode}/validate-draft`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      definition: JSON.stringify(payload.definition),
      config: JSON.stringify(payload.workflowConfig),
    }),
  })
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  return await response.json()
}

export async function validateFunctionFragment(payload: {
  language: string
  function_name?: string
  code: string
  timeout_ms?: number
}): Promise<FunctionFragmentValidationResult> {
  const response = await apiFetch(`${API_BASE_URL}/workflows/function-fragments/validate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function testRunFunctionFragment(payload: {
  language: string
  function_name?: string
  code: string
  timeout_ms?: number
  variables: {
    global: Record<string, unknown>
    local: Record<string, unknown>
  }
}): Promise<FunctionFragmentTestRunResult> {
  const response = await apiFetch(`${API_BASE_URL}/workflows/function-fragments/test-run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getOperationalReadiness(sessionId?: string): Promise<OperationalReadiness> {
  const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
  const response = await apiFetch(`${API_BASE_URL}/operations/readiness${query}`)
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  return await response.json()
}

export async function publishWorkflow(
  workflowCode: string,
  version: string,
  currentUserId: string
): Promise<WorkflowSummary> {
  const response = await apiFetch(`${API_BASE_URL}/workflows/${workflowCode}/publish`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify({ version }),
  })
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  return await response.json()
}

export async function rollbackWorkflow(workflowCode: string, version: string, currentUserId: string): Promise<WorkflowSummary> {
  const response = await apiFetch(`${API_BASE_URL}/workflows/${workflowCode}/rollback`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify({ version }),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getApiGroups(): Promise<ApiGroupSummary[]> {
  const response = await apiFetch(`${API_BASE_URL}/api-center/groups`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveApiGroup(
  payload: { groupName: string; description?: string; enabled?: boolean },
  currentUserId: string,
  existingGroupId?: number
): Promise<ApiGroupSummary> {
  const isUpdate = typeof existingGroupId === 'number'
  const response = await apiFetch(
    isUpdate ? `${API_BASE_URL}/api-center/groups/${existingGroupId}` : `${API_BASE_URL}/api-center/groups`,
    {
      method: isUpdate ? 'PUT' : 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-User-Id': currentUserId || ADMIN_USER_ID,
      },
      body: JSON.stringify(payload),
    }
  )
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteApiGroup(groupId: number, currentUserId: string): Promise<void> {
  const response = await apiFetch(`${API_BASE_URL}/api-center/groups/${groupId}`, {
    method: 'DELETE',
    headers: { 'X-User-Id': currentUserId || ADMIN_USER_ID },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function getApiGroupAuthConfig(groupId: number): Promise<Record<string, unknown>> {
  const response = await apiFetch(`${API_BASE_URL}/api-center/groups/${groupId}/auth-config`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveApiGroupAuthConfig(groupId: number, payload: ApiAuthConfigPayload, currentUserId: string): Promise<Record<string, unknown>> {
  const response = await apiFetch(`${API_BASE_URL}/api-center/groups/${groupId}/auth-config`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getApisByGroup(groupId: number): Promise<ApiItemSummary[]> {
  const response = await apiFetch(`${API_BASE_URL}/api-center/groups/${groupId}/items`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getApiItem(groupId: number, apiId: number): Promise<ApiItemSummary> {
  const response = await apiFetch(`${API_BASE_URL}/api-center/groups/${groupId}/items/${apiId}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveApiItem(
  groupId: number,
  apiId: number | undefined,
  payload: ApiItemPayload,
  currentUserId: string
): Promise<ApiItemSummary> {
  const isUpdate = typeof apiId === 'number'
  const response = await apiFetch(
    isUpdate ? `${API_BASE_URL}/api-center/groups/${groupId}/items/${apiId}` : `${API_BASE_URL}/api-center/groups/${groupId}/items`,
    {
      method: isUpdate ? 'PUT' : 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-User-Id': currentUserId || ADMIN_USER_ID,
      },
      body: JSON.stringify(payload),
    }
  )
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteApiItem(groupId: number, apiId: number, currentUserId: string): Promise<void> {
  const response = await apiFetch(`${API_BASE_URL}/api-center/groups/${groupId}/items/${apiId}`, {
    method: 'DELETE',
    headers: { 'X-User-Id': currentUserId || ADMIN_USER_ID },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function getApiItemAuthConfig(groupId: number, apiId: number): Promise<Record<string, unknown>> {
  const response = await apiFetch(`${API_BASE_URL}/api-center/groups/${groupId}/items/${apiId}/auth-config`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveApiItemAuthConfig(
  groupId: number,
  apiId: number,
  payload: { authMode: ApiAuthMode; authConfig?: ApiAuthConfigPayload },
  currentUserId: string
): Promise<Record<string, unknown>> {
  const response = await apiFetch(`${API_BASE_URL}/api-center/groups/${groupId}/items/${apiId}/auth-config`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function validateApiItem(
  groupId: number,
  payload: ApiItemPayload,
  currentUserId: string
): Promise<ApiValidationResult> {
  const response = await apiFetch(`${API_BASE_URL}/api-center/groups/${groupId}/validate`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function testApiItem(
  groupId: number,
  payload: ApiItemPayload,
  currentUserId: string
): Promise<ApiTestResult> {
  const response = await apiFetch(`${API_BASE_URL}/api-center/groups/${groupId}/test`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

