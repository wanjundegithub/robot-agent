type CallKind = 'http' | 'ws' | 'eventsource'
type CallStage = 'request' | 'response' | 'error' | 'event'

interface CallLogEntry {
  id: string
  timestamp: string
  kind: CallKind
  stage: CallStage
  name: string
  durationMs?: number
  status?: number | string
  request?: unknown
  response?: unknown
  error?: unknown
  meta?: Record<string, unknown>
}

const STORAGE_KEY = 'frontend_call_logs_v1'
const MAX_LOG_ENTRIES = 2000
const MAX_PREVIEW_LENGTH = 1200

const SENSITIVE_KEY_PATTERN = /(authorization|token|api[-_]?key|secret|password|passwd|cookie|set-cookie)/i

function createId(prefix: string): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}

function truncateText(value: string): string {
  if (value.length <= MAX_PREVIEW_LENGTH) return value
  return `${value.slice(0, MAX_PREVIEW_LENGTH)}...(truncated)`
}

function maskString(value: string): string {
  if (!value) return value
  if (value.length <= 6) return '***'
  return `${value.slice(0, 3)}***${value.slice(-2)}`
}

function sanitizeData(value: unknown): unknown {
  if (value == null) return value
  if (typeof value === 'string') {
    return truncateText(value)
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return value
  }
  if (Array.isArray(value)) {
    return value.map((item) => sanitizeData(item))
  }
  if (typeof value === 'object') {
    const objectValue = value as Record<string, unknown>
    const sanitized: Record<string, unknown> = {}
    Object.entries(objectValue).forEach(([key, item]) => {
      if (SENSITIVE_KEY_PATTERN.test(key)) {
        sanitized[key] = typeof item === 'string' ? maskString(item) : '***'
      } else {
        sanitized[key] = sanitizeData(item)
      }
    })
    return sanitized
  }
  return String(value)
}

function readLogsFromStorage(): CallLogEntry[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed as CallLogEntry[]
  } catch {
    return []
  }
}

function persistLogs(logs: CallLogEntry[]): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(logs.slice(-MAX_LOG_ENTRIES)))
  } catch (error) {
    console.warn('[callLogger] persist failed', error)
  }
}

export function appendCallLog(entry: Omit<CallLogEntry, 'id' | 'timestamp'>): void {
  const fullEntry: CallLogEntry = {
    id: createId('call'),
    timestamp: new Date().toISOString(),
    ...entry,
    request: sanitizeData(entry.request),
    response: sanitizeData(entry.response),
    error: sanitizeData(entry.error),
    meta: sanitizeData(entry.meta) as Record<string, unknown> | undefined,
  }
  const logs = readLogsFromStorage()
  logs.push(fullEntry)
  persistLogs(logs)
  console.info('[call-log]', fullEntry)
}

export async function apiFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  return await fetch(input, init)
}

export function logGatewayEvent(
  name: string,
  stage: CallStage,
  details?: Record<string, unknown>,
  status?: string | number
): void {
  appendCallLog({
    kind: 'ws',
    stage,
    name,
    status,
    meta: details,
  })
}

export function downloadCallLogs(filename = 'frontend-calls.log'): void {
  const logs = readLogsFromStorage()
  const text = logs.map((item) => JSON.stringify(item)).join('\n')
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const href = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = href
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(href)
}

export function clearCallLogs(): void {
  if (typeof window === 'undefined') return
  window.localStorage.removeItem(STORAGE_KEY)
}
