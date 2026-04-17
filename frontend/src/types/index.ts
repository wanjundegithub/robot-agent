export type MessageType = 'user' | 'ai' | 'system' | 'error'

export interface Message {
  id: string
  type: MessageType
  content: string
  timestamp: string
  streaming?: boolean
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

export interface GatewayAckEnvelope<T = Record<string, unknown>> {
  type: 'ack'
  request_id: string
  action: string
  status: string
  data?: T
}

export interface GatewayErrorEnvelope {
  type: 'error'
  request_id?: string
  error_code: string
  message: string
}

export interface GatewayActionEnvelope {
  type: 'action'
  request_id: string
  action: string
  session_id?: string
  payload?: Record<string, unknown>
}

export type WebSocketEnvelope =
  | ExecutionEventEnvelope
  | MessageDeltaEnvelope
  | GatewayAckEnvelope
  | GatewayErrorEnvelope

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
  node_logs: Array<Record<string, unknown>>
  event_stream: Array<Record<string, unknown>>
}

export interface RagEvaluationResponse {
  dataset_size: number
  hit_rate: number
  avg_relevance: number
  results: Array<Record<string, unknown>>
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
}

export interface WorkflowVersionSummary {
  id: number
  workflowCode: string
  version: string
  status: string
  definition?: string
  entryRule?: string
  editorMeta?: string
  config?: string
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
