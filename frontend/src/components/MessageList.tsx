import React, { useRef, useState } from 'react'
import type { ExecutionProcessStep, Message } from '../types'

interface MessageListProps {
  messages: Message[]
  isLoading: boolean
}

interface ThinkingProcessProps {
  steps: ExecutionProcessStep[]
  isStreaming: boolean
  isExpanded: boolean
  onToggle: () => void
}

const ThinkingProcess: React.FC<ThinkingProcessProps> = ({ steps, isStreaming, isExpanded, onToggle }) => {
  const activeStep = steps[steps.length - 1]
  const showTimeline = isStreaming || isExpanded
  const activeLabel = isStreaming ? activeStep?.label ?? '正在思考' : `已完成 ${steps.length} 步思考`
  const actionLabel = isStreaming ? `${steps.length} 步进行中` : isExpanded ? '收起' : `${steps.length} 步`

  return (
    <div className={`thinking-process ${isStreaming ? 'thinking-process-live' : 'thinking-process-complete'}`}>
      <button
        type="button"
        data-testid="execution-process-toggle"
        className="thinking-process-header"
        onClick={onToggle}
        aria-expanded={showTimeline}
      >
        <span className="thinking-orbit" aria-hidden="true">
          <span></span>
          <span></span>
          <span></span>
          <span></span>
        </span>
        <span className="thinking-process-copy">
          <span className="thinking-process-kicker">思考过程</span>
          <span data-testid="execution-process-active" className="thinking-process-active">
            {activeLabel}
          </span>
        </span>
        <span className="thinking-process-action">{actionLabel}</span>
      </button>

      {showTimeline && (
        <ol data-testid="execution-process-panel" className="thinking-process-timeline">
          {steps.map((step, index) => {
            const isActive = isStreaming && index === steps.length - 1
            return (
              <li
                key={step.id}
                className={`thinking-step ${isActive ? 'thinking-step-active' : 'thinking-step-done'}`}
                style={{ animationDelay: `${Math.min(index * 70, 280)}ms` }}
              >
                <span className="thinking-step-index">{index + 1}</span>
                <span className="thinking-step-content">
                  <span>{step.label}</span>
                  {step.detail && <span className="thinking-step-detail">{step.detail}</span>}
                </span>
              </li>
            )
          })}
        </ol>
      )}
    </div>
  )
}

const MessageList: React.FC<MessageListProps> = ({ messages, isLoading }) => {
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const [expandedProcessIds, setExpandedProcessIds] = useState<Set<string>>(() => new Set())
  const visibleMessages = messages.filter((message) => message.type !== 'system')
  const hasStreamingAssistant = visibleMessages.some((message) => message.type === 'ai' && message.streaming)

  React.useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [visibleMessages])

  const formatTime = (timestamp: string) => {
    return new Date(timestamp).toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  const toggleProcess = (messageId: string) => {
    setExpandedProcessIds((prev) => {
      const next = new Set(prev)
      if (next.has(messageId)) {
        next.delete(messageId)
      } else {
        next.add(messageId)
      }
      return next
    })
  }

  const renderMessage = (message: Message) => {
    const isUser = message.type === 'user'
    const isAI = message.type === 'ai'
    const isSystem = message.type === 'system'
    const isError = message.type === 'error'
    const processSteps = isAI ? message.processSteps ?? [] : []
    const isProcessExpanded = expandedProcessIds.has(message.id)

    return (
      <div
        key={message.id}
        data-testid={`message-${message.type}`}
        className={`flex mb-4 ${isUser ? 'justify-end' : 'justify-start'}`}
      >
        <div
          className={`max-w-[80%] rounded-lg px-4 py-2 ${
            isUser
              ? 'bg-blue-500 text-white rounded-br-none'
              : isError
                ? 'bg-red-100 text-red-800 rounded-bl-none border border-red-200'
                : isAI
                  ? 'bg-gray-100 text-gray-800 rounded-bl-none'
                  : 'bg-blue-50 text-blue-700 rounded-bl-none text-sm'
          }`}
        >
          <div className="flex items-center gap-2 mb-1">
            {isUser && <span className="text-xs opacity-75">{'\u7528\u6237'}</span>}
            {isAI && <span className="text-xs opacity-75">{'\u673a\u5668\u4eba'}</span>}
            {isSystem && <span className="text-xs opacity-75">{'\u7cfb\u7edf'}</span>}
            {isError && <span className="text-xs opacity-75">{'\u9519\u8bef'}</span>}
            <span className="text-xs opacity-60">{formatTime(message.timestamp)}</span>
          </div>
          {message.streaming && processSteps.length > 0 && (
            <ThinkingProcess
              steps={processSteps}
              isStreaming={true}
              isExpanded={isProcessExpanded}
              onToggle={() => toggleProcess(message.id)}
            />
          )}
          {(message.content || message.streaming) && (
            <p className={`whitespace-pre-wrap break-words ${message.streaming && message.content ? 'opacity-80 italic' : ''}`}>
              {message.content}
              {message.streaming && message.content && <span className="inline-block animate-pulse ml-1">...</span>}
            </p>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className="flex-1 overflow-y-auto p-4 bg-slate-50" data-testid="message-list">
      <div className="space-y-4">
        {visibleMessages.map(renderMessage)}
        {isLoading && !hasStreamingAssistant && (
          <div className="flex justify-start">
            <div className="bg-gray-100 rounded-lg px-4 py-2 rounded-bl-none">
              <div className="flex items-center gap-2 mb-1">
                <span className="text-xs opacity-75">机器人</span>
                <span className="text-xs opacity-60">{new Date().toLocaleTimeString('zh-CN')}</span>
              </div>
              <div className="flex items-center gap-2">
                <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce"></div>
                <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '0.2s' }}></div>
                <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '0.4s' }}></div>
              </div>
            </div>
          </div>
        )}
        <div ref={messagesEndRef} />
      </div>
    </div>
  )
}

export default MessageList
