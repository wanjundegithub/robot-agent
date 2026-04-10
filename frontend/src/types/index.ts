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
  candidate_workflows?: string[]
  active_execution_id?: string | null
  priority?: number
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

export type WebSocketEnvelope = ExecutionEventEnvelope | MessageDeltaEnvelope

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
  config?: string
}

export type SocketState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'disconnected'
