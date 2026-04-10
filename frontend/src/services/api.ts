import type {
  ExecutionDetail,
  FormSubmitResponse,
  ResumeExecutionResponse,
  SendMessageResponse,
  WorkflowSummary,
  WorkflowVersionSummary,
} from '../types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'
const ADMIN_USER_ID = 'demo-admin'

export async function sendMessage(
  sessionId: string,
  messageId: string,
  content: string,
  attachments: string[] = [],
  options?: { confirmSwitch?: boolean }
): Promise<SendMessageResponse> {
  const response = await fetch(`${API_BASE_URL}/sessions/${sessionId}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message_id: messageId,
      content,
      attachments,
      confirm_switch: options?.confirmSwitch ?? false,
    }),
  })

  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
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
    throw new Error(`HTTP error! status: ${response.status}`)
  }

  return await response.json()
}

export async function resumeExecution(executionId: string): Promise<ResumeExecutionResponse> {
  const response = await fetch(`${API_BASE_URL}/executions/${executionId}/resume`, {
    method: 'POST',
  })
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  return await response.json()
}

export async function getExecution(executionId: string): Promise<ExecutionDetail> {
  const response = await fetch(`${API_BASE_URL}/executions/${executionId}`)
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  return await response.json()
}

export async function getSessionExecutions(sessionId: string): Promise<ExecutionDetail[]> {
  const response = await fetch(`${API_BASE_URL}/executions?sessionId=${encodeURIComponent(sessionId)}`)
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  return await response.json()
}

export async function getWorkflows(): Promise<WorkflowSummary[]> {
  const response = await fetch(`${API_BASE_URL}/workflows`)
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  return await response.json()
}

export async function getWorkflowVersions(workflowCode: string): Promise<WorkflowVersionSummary[]> {
  const response = await fetch(`${API_BASE_URL}/workflows/${workflowCode}/versions`)
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  return await response.json()
}

export async function publishWorkflow(workflowCode: string, version: string): Promise<WorkflowSummary> {
  const response = await fetch(`${API_BASE_URL}/workflows/${workflowCode}/publish`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': ADMIN_USER_ID,
    },
    body: JSON.stringify({ version }),
  })
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  return await response.json()
}

export async function rollbackWorkflow(workflowCode: string, version: string): Promise<WorkflowSummary> {
  const response = await fetch(`${API_BASE_URL}/workflows/${workflowCode}/rollback`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': ADMIN_USER_ID,
    },
    body: JSON.stringify({ version }),
  })
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  return await response.json()
}
