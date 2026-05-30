import type { UserFrameEnvelope, WorkflowSummary } from '../types'

export const CONNECT_FRAME = 8
export const INTERACTIVE_FRAME = 9

export const isUserFrameEnvelope = (value: unknown): value is UserFrameEnvelope => {
  if (!value || typeof value !== 'object') return false
  return typeof (value as { frame?: unknown }).frame === 'number'
}

export const toInteractiveEventType = (action: string): string => {
  switch (action) {
    case 'chat.send':
    case 'send_message':
      return 'message.text'
    case 'ping':
      return 'heartbeat.ping'
    case 'submit_form':
      return 'form.submit'
    case 'resume_execution':
      return 'execution.resume'
    default:
      return action
  }
}

export const createInitFrame = ({
  requestId,
  userId,
  sessionId,
  workflow,
}: {
  requestId: string
  userId: string
  sessionId: string
  workflow: WorkflowSummary | null
}): UserFrameEnvelope => ({
  frame: CONNECT_FRAME,
  request_id: requestId,
  user_id: userId,
  session_id: sessionId,
  event_type: 'connection.init',
  payload: {
    workflow_code: workflow?.workflowCode ?? null,
    workflow_version: workflow?.currentVersion ?? null,
    client_version: 'web',
  },
})

export const createInteractiveFrame = ({
  requestId,
  userId,
  sessionId,
  executionId,
  eventType,
  payload,
}: {
  requestId: string
  userId: string
  sessionId: string
  executionId?: string | null
  eventType: string
  payload: Record<string, unknown>
}): UserFrameEnvelope => ({
  frame: INTERACTIVE_FRAME,
  request_id: requestId,
  user_id: userId,
  session_id: sessionId,
  execution_id: executionId ?? null,
  event_type: eventType,
  payload,
})
