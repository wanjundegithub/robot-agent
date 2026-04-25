import React, { useMemo } from 'react'
import type { Message, SessionSummary } from '../types'
import { displayMessageType } from '../utils/displayText'

interface SessionReplayPanelProps {
  sessions: SessionSummary[]
  activeSessionId: string
  connectedSessionId: string
  selectedSessionId: string
  selectedMessages: Message[]
  sessionMessagesById: Record<string, Message[]>
  isLoadingSessions: boolean
  isLoadingMessages: boolean
  onSelectSession: (sessionId: string) => void
  onDeleteSession: (sessionId: string) => void
}

interface SessionListItem {
  session: SessionSummary
  title: string
  isCurrent: boolean
}

const fallbackTitle = '新会话'
const titleMaxLength = 24

const normalizeText = (value: string): string => value.replace(/\s+/g, ' ').trim()

const createSessionTitle = (messages: Message[]): string => {
  const firstUserMessage = messages.find((message) => message.type === 'user')
  if (!firstUserMessage) return fallbackTitle

  const normalized = normalizeText(firstUserMessage.content)
  if (!normalized) return fallbackTitle

  return normalized.length > titleMaxLength
    ? `${normalized.slice(0, titleMaxLength)}...`
    : normalized
}

const formatTime = (value?: string): string => {
  if (!value) return ''

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }

  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

const SessionReplayPanel: React.FC<SessionReplayPanelProps> = ({
  sessions,
  activeSessionId,
  connectedSessionId,
  selectedSessionId,
  selectedMessages,
  sessionMessagesById,
  isLoadingSessions,
  isLoadingMessages,
  onSelectSession,
  onDeleteSession,
}) => {
  const sessionItems = useMemo<SessionListItem[]>(
    () =>
      sessions.map((session) => ({
        session,
        title: createSessionTitle(sessionMessagesById[session.id] ?? []),
        isCurrent: session.id === activeSessionId,
      })),
    [activeSessionId, sessionMessagesById, sessions]
  )

  const currentItem = useMemo(
    () => sessionItems.find((item) => item.isCurrent) ?? null,
    [sessionItems]
  )

  const historyItems = useMemo(
    () => sessionItems.filter((item) => !item.isCurrent),
    [sessionItems]
  )

  const selectedItem = useMemo(() => {
    if (currentItem?.session.id === selectedSessionId) {
      return currentItem
    }
    return historyItems.find((item) => item.session.id === selectedSessionId) ?? null
  }, [currentItem, historyItems, selectedSessionId])

  const renderSessionCard = (item: SessionListItem, testId?: string) => {
    const { session } = item
    const isSelected = session.id === selectedSessionId
    const activityTime = formatTime(session.lastActivityAt ?? session.createdAt)

    return (
      <div
        key={session.id}
        data-testid={testId}
        className={`rounded-xl border px-3 py-3 transition ${
          isSelected
            ? 'border-slate-900 bg-slate-900 text-white'
            : 'border-slate-200 bg-white text-slate-700'
        }`}
      >
        <div className="flex items-start justify-between gap-3">
          <button
            type="button"
            onClick={() => onSelectSession(session.id)}
            className="min-w-0 flex-1 text-left"
          >
            <div className="flex items-center justify-between gap-3">
              <span className="truncate text-sm font-medium">{item.title}</span>
              {item.isCurrent && (
                <span className={`shrink-0 text-xs ${isSelected ? 'text-slate-200' : 'text-slate-400'}`}>
                  当前
                </span>
              )}
            </div>
            {activityTime && (
              <div className={`mt-1 text-xs ${isSelected ? 'text-slate-300' : 'text-slate-400'}`}>
                {activityTime}
              </div>
            )}
          </button>
          <button
            type="button"
            data-testid={`session-history-delete-${session.id}`}
            onClick={() => onDeleteSession(session.id)}
            className={`shrink-0 rounded-lg border px-2 py-1 text-xs ${
              isSelected
                ? 'border-slate-600 text-slate-200 hover:bg-slate-800'
                : 'border-slate-200 text-slate-500 hover:bg-slate-50'
            }`}
          >
            删除
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="panel-card h-full flex flex-col" data-testid="session-replay-panel">
      <div className="panel-header">
        <div>
          <div className="panel-title">会话历史</div>
          <div className="text-xs text-slate-500">
            当前会话：{activeSessionId ? '已创建' : '暂无'} / 连接：{connectedSessionId ? '已连接' : '空闲'}
          </div>
        </div>
      </div>

      <div className="panel-body space-y-4 overflow-y-auto">
        {currentItem && (
          <section className="space-y-2">
            <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">当前会话</div>
            {renderSessionCard(currentItem, `session-current-item-${currentItem.session.id}`)}
          </section>
        )}

        <section className="space-y-2">
          <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">历史会话</div>
          {isLoadingSessions && <div className="text-sm text-slate-500">加载会话中...</div>}
          {!isLoadingSessions && historyItems.length === 0 && (
            <div className="text-sm text-slate-500">暂无可用会话。</div>
          )}
          <div className="space-y-2" data-testid="session-history-list">
            {historyItems.map((item) => renderSessionCard(item, `session-history-item-${item.session.id}`))}
          </div>
        </section>

        <section className="space-y-2">
          <div className="flex items-center justify-between gap-3">
            <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">消息记录</div>
            {selectedItem && <div className="text-xs text-slate-400">{selectedItem.title}</div>}
          </div>
          {isLoadingMessages && <div className="text-sm text-slate-500">加载消息中...</div>}
          {!isLoadingMessages && selectedMessages.length === 0 && (
            <div className="text-sm text-slate-500">该会话暂无消息。</div>
          )}
          <div className="space-y-2">
            {selectedMessages.map((message) => (
              <div key={message.id} className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-3">
                <div className="flex items-center justify-between gap-3 text-xs text-slate-400">
                  <span>{displayMessageType(message.type)}</span>
                  <span>{formatTime(message.timestamp)}</span>
                </div>
                <div className="mt-2 whitespace-pre-wrap text-sm text-slate-700">{message.content}</div>
              </div>
            ))}
          </div>
        </section>
      </div>
    </div>
  )
}

export default SessionReplayPanel
