export type MessageType = 'user' | 'ai' | 'system' | 'error'

export interface ExecutionProcessStep {
  id: string
  label: string
  detail?: string
  timestamp: string
}

export interface Message {
  id: string
  type: MessageType
  content: string
  timestamp: string
  streaming?: boolean
  executionId?: string
  processSteps?: ExecutionProcessStep[]
}

export interface IntentCandidate {
  intent_code?: string
  target_type?: string
  target_code?: string
  confidence?: number
  source?: string
  evidence?: string
}

export interface SendMessageResponse {
  session_id: string
  execution_id: string | null
  workflow_code: string
  workflow_version: string
  status: string
  route_decision?: string
  route_confidence?: number
  route_reason?: string
  route_threshold?: number
  threshold_source?: string
  candidate_workflows?: string[]
  active_execution_id?: string | null
  priority?: number
  experiment_id?: string
  experiment_group?: string
  permission_effect?: string
  permission_reason?: string
  requested_tool_code?: string
  confirmation_id?: string
  confirmation_expires_at?: string
  protection_status?: string
  protection_reason?: string
  retry_after_seconds?: number
  degradation_message?: string
  intent_candidate_queue?: IntentCandidate[]
  clarification_question?: string
}

export interface FormSubmitResponse {
  execution_id: string
  status: string
}

export interface ResumeExecutionResponse {
  execution_id: string
  status: string
  form_definition?: string | null
}

export type ExecutionEventType =
  | 'routing.decided'
  | 'plan.created'
  | 'plan.replanned'
  | 'branch.decided'
  | 'execution.started'
  | 'execution.completed'
  | 'execution.failed'
  | 'execution.suspended'
  | 'execution.waiting_user'
  | 'execution.waiting_tool'
  | 'execution.resumed'
  | 'execution.switch_requested'
  | 'execution.resume_offered'
  | 'node.started'
  | 'node.skipped'
  | 'node.completed'
  | 'node.failed'
  | 'form.requested'
  | 'tool.called'
  | 'tool.returned'
  | 'security.prompt_sanitized'
  | 'security.output_rejected'
  | 'cost.recorded'
  | 'budget.alert'
  | 'replay.snapshot_ready'
  | 'confirmation.required'
  | 'protection.rate_limited'
  | 'protection.degraded'
  | 'protection.circuit_open'
  | 'optimization.vector_access'
  | 'workflow.validation_failed'

export interface ExecutionEventEnvelope {
  type: 'event'
  event_type: ExecutionEventType
  execution_id: string
  session_id?: string
  data?: Record<string, unknown>
}

export interface MessageDeltaEnvelope {
  type: 'message_delta'
  execution_id: string
  session_id?: string
  content: string
  is_complete?: boolean
}

export interface LegacyAckEnvelope<T = Record<string, unknown>> {
  type: 'ack'
  request_id: string
  action: string
  status: string
  data?: T
}

export interface LegacyErrorEnvelope {
  type: 'error'
  request_id?: string
  error_code: string
  message: string
}


export type UserFrameCode = 8 | 9 | number

export interface UserFrameEnvelope<T = Record<string, unknown>> {
  frame: UserFrameCode
  request_id?: string
  user_id?: string
  session_id?: string
  execution_id?: string | null
  event_type?: string
  payload?: T
  timestamp?: string
}

export type WebSocketEnvelope =
  | ExecutionEventEnvelope
  | MessageDeltaEnvelope
  | LegacyAckEnvelope
  | LegacyErrorEnvelope
  | UserFrameEnvelope

export interface ExecutionEventView {
  id: string
  execution_id: string
  session_id?: string
  event_type: ExecutionEventType
  node_id?: string
  node_type?: string
  tool_code?: string
  status?: string
  timestamp: string
}

export type FormFieldType = 'text' | 'number' | 'date' | 'select' | 'textarea'

export interface FormField {
  name: string
  label?: string
  type: FormFieldType
  required?: boolean
  placeholder?: string
  options?: string[]
}

export interface FormDefinition {
  title?: string
  description?: string
  fields: FormField[]
}

export interface ExecutionDetail {
  execution_id: string
  session_id: string
  workflow_code: string
  workflow_version: string
  status: string
  current_node_id?: string | null
  variables?: string | null
  error?: string | null
}

export interface CostAlert {
  scope: string
  scope_id: string
  total_cost: number
  threshold: number
  message: string
}

export interface AnalyticsSummary {
  total_executions: number
  active_executions: number
  success_rate: number
  task_completion_rate: number
  avg_completion_seconds: number
  intent_accuracy: number
  human_intervention_rate: number
  total_cost: number
  input_tokens: number
  output_tokens: number
}

export interface AnalyticsDashboard {
  summary: AnalyticsSummary
  workflow_breakdown: Array<Record<string, unknown>>
  experiment_summary: Array<Record<string, unknown>>
  cost_alerts: CostAlert[]
}

export interface ReplayResponse {
  execution_id: string
  workflow_code: string
  workflow_version: string
  session_id: string
  status: string
  input_variables: Record<string, unknown>
  output_variables: Record<string, unknown>
  variables: Record<string, unknown>
  metrics: Record<string, unknown>
  node_logs: ReplayNodeLog[]
  event_stream: ReplayEvent[]
}

export interface ReplayNodeLog {
  node_id?: string
  node_type?: string
  status?: string
  started_at?: string
  completed_at?: string
  input?: Record<string, unknown>
  output?: Record<string, unknown>
  metrics?: Record<string, unknown>
  error?: string | null
}

export interface ReplayEvent {
  event_type?: string
  execution_id?: string
  workflow_code?: string
  workflow_version?: string
  node_id?: string
  node_type?: string
  final_output?: Record<string, unknown>
}

export interface RagEvaluationResponse {
  dataset_size: number
  hit_rate: number
  avg_relevance: number
  results: Array<Record<string, unknown>>
}

export interface KnowledgeSpace {
  id: number
  workspaceId: number
  kbCode: string
  name: string
  description?: string | null
  embeddingModel?: string | null
  currentVersion?: string | null
  status: string
  documentCount?: number
  createdAt?: string | null
}

export interface KnowledgeDocument {
  docId: string
  kbCode: string
  filename?: string | null
  fileSize?: number | null
  sourceType?: string | null
  status: string
  chunkCount?: number | null
  errorMessage?: string | null
  generatedTitle?: string | null
  generatedSummary?: string | null
  generatedKeywords?: string | null
  indexVersion?: number | null
  uploadedAt?: string | null
  processedAt?: string | null
  title?: string | null
}

export interface KnowledgeTask {
  taskId: string
  docId: string
  kbCode: string
  stage: string
  status: string
  progress?: number | null
  errorMessage?: string | null
  retryCount?: number | null
  createdAt?: string | null
  updatedAt?: string | null
  startedAt?: string | null
  completedAt?: string | null
}

export interface KnowledgeDocumentHit {
  chunkId: string
  docId: string
  kbCode: string
  title?: string | null
  content?: string | null
  score: number
}

export interface KnowledgeCitation {
  chunkId: string
  docId: string
  score: number
}

export interface KnowledgeSearchResult {
  query: string
  documents: KnowledgeDocumentHit[]
  answer: string
  citations: KnowledgeCitation[]
  bestScore: number
}

export interface SubflowRecommendationResponse {
  workflow_code: string
  recommendations: Array<Record<string, unknown>>
}

export interface WorkflowSummary {
  id: number
  workflowCode: string
  name: string
  description?: string
  status: string
  currentVersion?: string
  createdBy?: string
}

export interface WorkflowVersionSummary {
  id: number
  workflowId?: number | null
  workflowCode: string
  workflowName?: string | null
  workflowDescription?: string | null
  version: string
  status: string
  definition?: string
  entryRule?: string
  editorMeta?: string
  config?: string
  workflowSnapshot?: string
  createdAt?: string
  publishedAt?: string | null
}

export interface WorkflowEditorSelection {
  workflowCode: string
  workflowName?: string | null
  workflowDescription?: string | null
  publishedVersion?: string
  version: WorkflowVersionSummary
}

export interface WorkflowDesignerGraphDefinition {
  graph_id: string
  graph_type: 'MAIN' | 'SUBGRAPH' | string
  entry_node_id: string
  graph_name?: string
  graph_description?: string
  nodes: Record<string, unknown>
  edges: Array<Record<string, unknown>>
  id?: string
  name?: string
  entry?: string
  transitions?: Record<string, unknown>
}

export interface WorkflowDesignerDefinitionV2 {
  schema_version: 'workflow-designer/v2'
  workflow_code: string
  workflow_name: string
  workflow_description?: string
  workflow_version: string
  main_graph_id: string
  graphs: Record<string, WorkflowDesignerGraphDefinition>
  variables: {
    global: unknown[]
    temporary: unknown[]
  }
  model_bindings?: {
    routing_model_code: string
    llm_defaults: {
      model_code: string
    }
  }
  editor_meta: Record<string, unknown>
}

export interface WorkflowSnapshotV1 {
  schema_version: 'workflow-snapshot/v1'
  workflow: {
    workflow_code: string
    workflow_name: string
    workflow_description?: string
    workflow_version: string
  }
  designer: {
    definition: WorkflowDesignerDefinitionV2
    entry_rule: Record<string, unknown>
    workflow_config: Record<string, unknown>
    editor_meta: Record<string, unknown>
  }
}

export interface SessionSummary {
  id: string
  workspaceId: number
  userId: string
  status: string
  currentExecutionId?: string | null
  variables?: string | null
  createdAt?: string
  lastActivityAt?: string
}

export interface WorkflowDraftValidationResponse {
  valid: boolean
  issues: WorkflowValidationIssue[]
}

export interface FunctionFragmentValidationResult {
  valid: boolean
  error_message?: string | null
  line?: number | null
  column?: number | null
}

export interface FunctionFragmentTestRunResult {
  success: boolean
  variables: {
    global: Record<string, unknown>
    local: Record<string, unknown>
  }
  stdout: string
  error_message?: string | null
  line?: number | null
  column?: number | null
  duration_ms: number
}

export interface ModelProviderConfig {
  provider_code: string
  provider_name?: string | null
  provider_type: string
  base_url: string
  api_key_secret_ref?: string
  enabled: boolean
  api_key_mode?: string
  api_key_configured?: boolean
  api_key_masked?: string
  api_key_error?: string
  created_at?: string
  updated_at?: string
}

export interface ModelRecordConfig {
  id: number
  custom_model_name: string
  provider: string
  model_name: string
  api_key: string
  base_url: string
  default_options?: Record<string, unknown>
  created_at?: string
  updated_at?: string
}

export interface PagedModelRecordResponse {
  items: ModelRecordConfig[]
  page: number
  page_size: number
  total: number
}

export interface ProviderValidationResult {
  valid: boolean
  provider_code: string
  message: string
  status_code?: number
  tested_model_code?: string
}

export type SocketState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'disconnected'

export interface GovernanceNotice {
  title: string
  detail: string
  tone: 'info' | 'warning' | 'danger'
  updated_at: string
}

export interface OperationalReadiness {
  protection: {
    circuit: Record<string, unknown>
    rate_limits: Array<Record<string, unknown>>
    degradation_modes: string[]
  }
  archive: {
    retention: Record<string, unknown>
    summary: Record<string, unknown>
    recent_candidates: Array<Record<string, unknown>>
    restore_entrypoints: string[]
  }
  platform: {
    index_targets: Array<Record<string, unknown>>
    redis_cluster: Record<string, unknown>
    vector_sharding: Record<string, unknown>
  }
}

export interface WorkflowValidationIssue {
  node_id?: string | null
  field: string
  message: string
}

export interface ApiGroupSummary {
  id: number
  groupName: string
  description?: string | null
  enabled?: boolean
  status: string
  apiCount?: number
  authType?: string | null
  authPreview?: string | null
  updatedAt?: string | null
}

export interface ApiItemSummary {
  id: number
  groupId?: number
  apiName: string
  description?: string | null
  enabled?: boolean
  status: string
  requestUrl: string
  requestMethod: string
  authMode?: string | null
  authType?: string | null
  authPreview?: string | null
  authConfig?: Record<string, unknown> | null
  headers?: string | Array<{ key?: string; value?: string; enabled?: boolean; checked?: boolean; selected?: boolean }> | null
  inputSchema?: string | null
  outputSchema?: string | null
  lastTestStatus?: string | null
  lastTestTime?: string | null
  lastTestErrorMessage?: string | null
  lastTestToken?: string | null
  urlVariables?: string[]
  updatedAt?: string | null
}

export interface ApiValidationResult {
  valid: boolean
  message: string
  issues?: Array<{ field?: string; message?: string }>
}

export interface ApiTestResult {
  success: boolean
  testType: string
  statusCode?: number | null
  responsePayload?: string | null
  errorMessage?: string | null
  durationMs?: number | null
  testedAt?: string | null
  lastTestToken?: string | null
}
