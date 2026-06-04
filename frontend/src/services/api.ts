import type {
  AnalyticsDashboard,
  ApiGroupSummary,
  ApiItemSummary,
  ApiTestResult,
  ApiValidationResult,
  CostAlert,
  ExecutionDetail,
  Message,
  RagEvaluationResponse,
  ReplayResponse,
  SubflowRecommendationResponse,
  OperationalReadiness,
  ModelRecordConfig,
  ModelProviderConfig,
  PagedModelRecordResponse,
  ProviderValidationResult,
  SessionSummary,
  WorkflowDraftValidationResponse,
  WorkflowSummary,
  WorkflowVersionSummary,
} from '../types'
import { loggedFetch } from './callLogger'

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
  const response = await loggedFetch(`${API_BASE_URL}/sessions/${encodeURIComponent(sessionId)}/messages`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getExecution(executionId: string): Promise<ExecutionDetail> {
  const response = await loggedFetch(`${API_BASE_URL}/executions/${executionId}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getSessionExecutions(sessionId: string): Promise<ExecutionDetail[]> {
  const response = await loggedFetch(`${API_BASE_URL}/executions?sessionId=${encodeURIComponent(sessionId)}`)
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
  const response = await loggedFetch(`${API_BASE_URL}/sessions`, {
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
  const response = await loggedFetch(`${API_BASE_URL}/sessions/${encodeURIComponent(sessionId)}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteSession(sessionId: string): Promise<void> {
  const response = await loggedFetch(`${API_BASE_URL}/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function getSessionsByUserId(userId: string): Promise<SessionSummary[]> {
  const response = await loggedFetch(`${API_BASE_URL}/sessions?userId=${encodeURIComponent(userId)}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getAnalyticsDashboard(sessionId?: string): Promise<AnalyticsDashboard> {
  const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
  const response = await loggedFetch(`${API_BASE_URL}/analytics/dashboard${query}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getCostAlerts(sessionId?: string): Promise<CostAlert[]> {
  const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
  const response = await loggedFetch(`${API_BASE_URL}/analytics/cost-alerts${query}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getExecutionReplay(executionId: string): Promise<ReplayResponse> {
  const response = await loggedFetch(`${API_BASE_URL}/executions/${executionId}/replay`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getSubflowRecommendations(
  workflowCode: string,
  message: string
): Promise<SubflowRecommendationResponse> {
  const response = await loggedFetch(
    `${API_BASE_URL}/workflows/${workflowCode}/subflow-recommendations?message=${encodeURIComponent(message)}`
  )
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function runRagEvaluation(dataset?: Array<Record<string, unknown>>): Promise<RagEvaluationResponse> {
  const response = await loggedFetch(`${API_BASE_URL}/evaluations/rag`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ dataset: dataset ?? null }),
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getWorkflows(): Promise<WorkflowSummary[]> {
  const response = await loggedFetch(`${API_BASE_URL}/workflows`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getPublishedWorkflows(): Promise<WorkflowSummary[]> {
  const response = await loggedFetch(`${API_BASE_URL}/workflows/published`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteWorkflow(workflowCode: string, currentUserId: string): Promise<void> {
  const response = await loggedFetch(`${API_BASE_URL}/workflows/${encodeURIComponent(workflowCode)}`, {
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
  const response = await loggedFetch(`${API_BASE_URL}/workflows/${workflowCode}/versions`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function archiveWorkflowVersion(workflowCode: string, version: string, currentUserId: string): Promise<WorkflowVersionSummary> {
  const response = await loggedFetch(`${API_BASE_URL}/workflows/${workflowCode}/versions/${encodeURIComponent(version)}/archive`, {
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
  const response = await loggedFetch(`${API_BASE_URL}/workflows/${workflowCode}/versions/${encodeURIComponent(version)}`, {
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
  const response = await loggedFetch(`${API_BASE_URL}/model-config/providers`)
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
  const response = await loggedFetch(
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
  const response = await loggedFetch(`${API_BASE_URL}/model-config/providers/${encodeURIComponent(providerCode)}`, {
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
  const response = await loggedFetch(`${API_BASE_URL}/model-config/providers/validate-draft`, {
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
  const response = await loggedFetch(`${API_BASE_URL}/model-config/models?${query.toString()}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getModelRecord(id: number): Promise<ModelRecordConfig> {
  const response = await loggedFetch(`${API_BASE_URL}/model-config/models/${id}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveModelRecord(
  payload: {
    custom_model_name: string
    provider: string
    model_name: string
    api_key: string
    base_url: string
  },
  currentUserId: string,
  existingId?: number
): Promise<ModelRecordConfig> {
  const isUpdate = typeof existingId === 'number'
  const response = await loggedFetch(
    isUpdate ? `${API_BASE_URL}/model-config/models/${existingId}` : `${API_BASE_URL}/model-config/models`,
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

export async function deleteModelRecord(id: number, currentUserId: string): Promise<void> {
  const response = await loggedFetch(`${API_BASE_URL}/model-config/models/${id}`, {
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
    custom_model_name: string
    provider: string
    model_name: string
    api_key: string
    base_url: string
  },
  currentUserId: string
): Promise<Record<string, unknown>> {
  const response = await loggedFetch(`${API_BASE_URL}/model-config/models/test`, {
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

  const response = await loggedFetch(`${API_BASE_URL}/workflows/${workflowCode}/drafts`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': payload.currentUserId,
    },
    body: JSON.stringify({
      workflow_code: workflowCode,
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
  const response = await loggedFetch(`${API_BASE_URL}/workflows/${workflowCode}/validate-draft`, {
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

export async function getOperationalReadiness(sessionId?: string): Promise<OperationalReadiness> {
  const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
  const response = await loggedFetch(`${API_BASE_URL}/operations/readiness${query}`)
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
  const response = await loggedFetch(`${API_BASE_URL}/workflows/${workflowCode}/publish`, {
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
  const response = await loggedFetch(`${API_BASE_URL}/workflows/${workflowCode}/rollback`, {
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
  const response = await loggedFetch(`${API_BASE_URL}/api-center/groups`)
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
  const response = await loggedFetch(
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
  const response = await loggedFetch(`${API_BASE_URL}/api-center/groups/${groupId}`, {
    method: 'DELETE',
    headers: { 'X-User-Id': currentUserId || ADMIN_USER_ID },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function getApiGroupAuthConfig(groupId: number): Promise<Record<string, unknown>> {
  const response = await loggedFetch(`${API_BASE_URL}/api-center/groups/${groupId}/auth-config`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveApiGroupAuthConfig(groupId: number, payload: ApiAuthConfigPayload, currentUserId: string): Promise<Record<string, unknown>> {
  const response = await loggedFetch(`${API_BASE_URL}/api-center/groups/${groupId}/auth-config`, {
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
  const response = await loggedFetch(`${API_BASE_URL}/api-center/groups/${groupId}/items`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getApiItem(groupId: number, apiId: number): Promise<ApiItemSummary> {
  const response = await loggedFetch(`${API_BASE_URL}/api-center/groups/${groupId}/items/${apiId}`)
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
  const response = await loggedFetch(
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
  const response = await loggedFetch(`${API_BASE_URL}/api-center/groups/${groupId}/items/${apiId}`, {
    method: 'DELETE',
    headers: { 'X-User-Id': currentUserId || ADMIN_USER_ID },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function getApiItemAuthConfig(groupId: number, apiId: number): Promise<Record<string, unknown>> {
  const response = await loggedFetch(`${API_BASE_URL}/api-center/groups/${groupId}/items/${apiId}/auth-config`)
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
  const response = await loggedFetch(`${API_BASE_URL}/api-center/groups/${groupId}/items/${apiId}/auth-config`, {
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
  const response = await loggedFetch(`${API_BASE_URL}/api-center/groups/${groupId}/validate`, {
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
  const response = await loggedFetch(`${API_BASE_URL}/api-center/groups/${groupId}/test`, {
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

