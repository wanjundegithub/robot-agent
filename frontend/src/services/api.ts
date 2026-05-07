import type {
  AnalyticsDashboard,
  AuthConfigSummary,
  CapabilityGroupSnapshot,
  CapabilityGroupSummary,
  CapabilityItemSummary,
  CapabilityTestResult,
  CapabilityValidationResult,
  CapabilityVersionSummary,
  CostAlert,
  ExecutionDetail,
  FormSubmitResponse,
  Message,
  RagEvaluationResponse,
  ReplayResponse,
  ResumeExecutionResponse,
  SendMessageResponse,
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

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'
const ADMIN_USER_ID = 'demo-admin'

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

export async function sendMessage(
  sessionId: string,
  messageId: string,
  content: string,
  attachments: string[] = [],
  options?: {
    confirmSwitch?: boolean
    userId?: string
    requestedToolCode?: string
    confirmationId?: string
    cancelConfirmation?: boolean
    workflowId?: number | null
    sessionId?: string
  }
): Promise<SendMessageResponse> {
  const response = await fetch(`${API_BASE_URL}/sessions/${sessionId}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message_id: messageId,
      content,
      attachments,
      user_id: options?.userId ?? 'demo-user',
      confirm_switch: options?.confirmSwitch ?? false,
      requested_tool_code: options?.requestedToolCode ?? null,
      confirmation_id: options?.confirmationId ?? null,
      cancel_confirmation: options?.cancelConfirmation ?? false,
      session_id: options?.sessionId ?? sessionId,
      workflow_id: options?.workflowId ?? null,
    }),
  })

  if (!response.ok) {
    await parseApiError(response)
  }

  return await response.json()
}

export async function getSessionMessages(sessionId: string): Promise<Message[]> {
  const response = await fetch(`${API_BASE_URL}/sessions/${encodeURIComponent(sessionId)}/messages`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function submitForm(
  executionId: string,
  submitId: string,
  formData: Record<string, unknown>
): Promise<FormSubmitResponse> {
  const response = await fetch(`${API_BASE_URL}/executions/${executionId}/form-submit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      submit_id: submitId,
      form_data: formData,
    }),
  })

  if (!response.ok) {
    await parseApiError(response)
  }

  return await response.json()
}

export async function resumeExecution(executionId: string): Promise<ResumeExecutionResponse> {
  const response = await fetch(`${API_BASE_URL}/executions/${executionId}/resume`, {
    method: 'POST',
  })
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getExecution(executionId: string): Promise<ExecutionDetail> {
  const response = await fetch(`${API_BASE_URL}/executions/${executionId}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getSessionExecutions(sessionId: string): Promise<ExecutionDetail[]> {
  const response = await fetch(`${API_BASE_URL}/executions?sessionId=${encodeURIComponent(sessionId)}`)
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
  const response = await fetch(`${API_BASE_URL}/sessions`, {
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
  const response = await fetch(`${API_BASE_URL}/sessions/${encodeURIComponent(sessionId)}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteSession(sessionId: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function getSessionsByUserId(userId: string): Promise<SessionSummary[]> {
  const response = await fetch(`${API_BASE_URL}/sessions?userId=${encodeURIComponent(userId)}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getAnalyticsDashboard(sessionId?: string): Promise<AnalyticsDashboard> {
  const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
  const response = await fetch(`${API_BASE_URL}/analytics/dashboard${query}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getCostAlerts(sessionId?: string): Promise<CostAlert[]> {
  const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
  const response = await fetch(`${API_BASE_URL}/analytics/cost-alerts${query}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getExecutionReplay(executionId: string): Promise<ReplayResponse> {
  const response = await fetch(`${API_BASE_URL}/executions/${executionId}/replay`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getSubflowRecommendations(
  workflowCode: string,
  message: string
): Promise<SubflowRecommendationResponse> {
  const response = await fetch(
    `${API_BASE_URL}/workflows/${workflowCode}/subflow-recommendations?message=${encodeURIComponent(message)}`
  )
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function runRagEvaluation(dataset?: Array<Record<string, unknown>>): Promise<RagEvaluationResponse> {
  const response = await fetch(`${API_BASE_URL}/evaluations/rag`, {
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
  const response = await fetch(`${API_BASE_URL}/workflows`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getPublishedWorkflows(): Promise<WorkflowSummary[]> {
  const response = await fetch(`${API_BASE_URL}/workflows/published`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteWorkflow(workflowCode: string, currentUserId: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/workflows/${encodeURIComponent(workflowCode)}`, {
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
  const response = await fetch(`${API_BASE_URL}/workflows/${workflowCode}/versions`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function archiveWorkflowVersion(workflowCode: string, version: string, currentUserId: string): Promise<WorkflowVersionSummary> {
  const response = await fetch(`${API_BASE_URL}/workflows/${workflowCode}/versions/${encodeURIComponent(version)}/archive`, {
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
  const response = await fetch(`${API_BASE_URL}/workflows/${workflowCode}/versions/${encodeURIComponent(version)}`, {
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
  const response = await fetch(`${API_BASE_URL}/model-config/providers`)
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
  const response = await fetch(
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
  const response = await fetch(`${API_BASE_URL}/model-config/providers/${encodeURIComponent(providerCode)}`, {
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
  const response = await fetch(`${API_BASE_URL}/model-config/providers/validate-draft`, {
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
  const response = await fetch(`${API_BASE_URL}/model-config/models?${query.toString()}`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getModelRecord(id: number): Promise<ModelRecordConfig> {
  const response = await fetch(`${API_BASE_URL}/model-config/models/${id}`)
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
  const response = await fetch(
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
  const response = await fetch(`${API_BASE_URL}/model-config/models/${id}`, {
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
  const response = await fetch(`${API_BASE_URL}/model-config/models/test`, {
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
    version: string
    definition: Record<string, unknown>
    entryRule: Record<string, unknown>
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

  const response = await fetch(`${API_BASE_URL}/workflows/${workflowCode}/drafts`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': payload.currentUserId,
    },
    body: JSON.stringify({
      workflow_code: workflowCode,
      workflow_name: payload.workflowName,
      version: payload.version,
      definition: JSON.stringify(payload.definition),
      entry_rule: JSON.stringify(payload.entryRule),
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
  const response = await fetch(`${API_BASE_URL}/workflows/${workflowCode}/validate-draft`, {
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
  const response = await fetch(`${API_BASE_URL}/operations/readiness${query}`)
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
  const response = await fetch(`${API_BASE_URL}/workflows/${workflowCode}/publish`, {
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
  const response = await fetch(`${API_BASE_URL}/workflows/${workflowCode}/rollback`, {
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

export async function getCapabilityGroups(): Promise<CapabilityGroupSummary[]> {
  const response = await fetch(`${API_BASE_URL}/capabilities/groups`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveCapabilityGroup(
  payload: {
    groupName: string
    description?: string
  },
  currentUserId: string,
  existingGroupId?: number
): Promise<CapabilityGroupSummary> {
  const isUpdate = typeof existingGroupId === 'number'
  const response = await fetch(
    isUpdate
      ? `${API_BASE_URL}/capabilities/groups/${existingGroupId}`
      : `${API_BASE_URL}/capabilities/groups`,
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

export async function deleteCapabilityGroup(groupId: number, currentUserId: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/capabilities/groups/${groupId}`, {
    method: 'DELETE',
    headers: {
      'X-User-Id': currentUserId || ADMIN_USER_ID,
    },
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function getCapabilitiesByGroup(groupId: number): Promise<CapabilityItemSummary[]> {
  const response = await fetch(`${API_BASE_URL}/capabilities/groups/${groupId}/items`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function getCapabilityVersions(
  groupId: number,
  capabilityCode: string
): Promise<CapabilityVersionSummary[]> {
  const response = await fetch(
    `${API_BASE_URL}/capabilities/groups/${groupId}/items/${encodeURIComponent(capabilityCode)}/versions`
  )
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveCapabilityDraft(
  groupId: number,
  capabilityCode: string | undefined,
  payload: {
    capabilityCode?: string
    capabilityName: string
    capabilityType: string
    description?: string
    inputSchema?: string
    outputSchema?: string
    definitionJson?: string
    authConfigId?: number | null
  },
  currentUserId: string
): Promise<CapabilityVersionSummary> {
  const isUpdate = Boolean(capabilityCode)
  const response = await fetch(
    isUpdate
      ? `${API_BASE_URL}/capabilities/groups/${groupId}/items/${encodeURIComponent(capabilityCode || '')}/draft`
      : `${API_BASE_URL}/capabilities/groups/${groupId}/items`,
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

export async function publishCapability(
  groupId: number,
  capabilityCode: string,
  currentUserId: string
): Promise<CapabilityVersionSummary> {
  const response = await fetch(
    `${API_BASE_URL}/capabilities/groups/${groupId}/items/${encodeURIComponent(capabilityCode)}/publish`,
    {
      method: 'POST',
      headers: {
        'X-User-Id': currentUserId || ADMIN_USER_ID,
      },
    }
  )
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function deleteCapability(
  groupId: number,
  capabilityCode: string,
  currentUserId: string
): Promise<void> {
  const response = await fetch(
    `${API_BASE_URL}/capabilities/groups/${groupId}/items/${encodeURIComponent(capabilityCode)}`,
    {
      method: 'DELETE',
      headers: {
        'X-User-Id': currentUserId || ADMIN_USER_ID,
      },
    }
  )
  if (!response.ok) {
    await parseApiError(response)
  }
}

export async function getCapabilityGroupSnapshots(groupId: number): Promise<CapabilityGroupSnapshot[]> {
  const response = await fetch(`${API_BASE_URL}/capabilities/groups/${groupId}/snapshots`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function publishCapabilityGroupSnapshot(
  groupId: number,
  payload: { description?: string },
  currentUserId: string
): Promise<CapabilityGroupSnapshot> {
  const response = await fetch(`${API_BASE_URL}/capabilities/groups/${groupId}/snapshots/publish`, {
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

export async function getCapabilityAuthConfigs(groupId: number): Promise<AuthConfigSummary[]> {
  const response = await fetch(`${API_BASE_URL}/capabilities/groups/${groupId}/auth-configs`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}

export async function saveCapabilityAuthConfig(
  groupId: number,
  payload: {
    id?: number
    authName: string
    authType: string
    scope?: 'GROUP' | 'CAPABILITY'
    maskedPreview?: string
    config?: Record<string, unknown>
  },
  currentUserId: string
): Promise<AuthConfigSummary> {
  const isUpdate = Boolean(payload.id)
  const response = await fetch(
    isUpdate
      ? `${API_BASE_URL}/capabilities/groups/${groupId}/auth-configs/${payload.id}`
      : `${API_BASE_URL}/capabilities/groups/${groupId}/auth-configs`,
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

export async function validateCapabilityDraft(
  groupId: number,
  payload: Record<string, unknown>,
  currentUserId: string
): Promise<CapabilityValidationResult> {
  const response = await fetch(`${API_BASE_URL}/capabilities/groups/${groupId}/validate-draft`, {
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

export async function testCapability(
  groupId: number,
  capabilityCode: string,
  payload: Record<string, unknown>,
  currentUserId: string
): Promise<CapabilityTestResult> {
  const response = await fetch(
    `${API_BASE_URL}/capabilities/groups/${groupId}/items/${encodeURIComponent(capabilityCode)}/test`,
    {
      method: 'POST',
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
