import React, { useRef } from 'react'
import { Message } from '../types'

interface MessageListProps {
  messages: Message[]
  isLoading: boolean
}

const MessageList: React.FC<MessageListProps> = ({ messages, isLoading }) => {
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const visibleMessages = messages.filter((message) => message.type !== 'system')

  React.useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [visibleMessages])

  const formatTime = (timestamp: string) => {
    return new Date(timestamp).toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  const renderMessage = (message: Message) => {
    const isUser = message.type === 'user'
    const isAI = message.type === 'ai'
    const isSystem = message.type === 'system'
    const isError = message.type === 'error'

    return (
      <div
        key={message.id}
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
            {isUser && <span className="text-xs opacity-75">用户</span>}
            {isAI && <span className="text-xs opacity-75">机器人</span>}
            {isSystem && <span className="text-xs opacity-75">系统</span>}
            {isError && <span className="text-xs opacity-75">错误</span>}
            <span className="text-xs opacity-60">{formatTime(message.timestamp)}</span>
          </div>
          <p className={`whitespace-pre-wrap break-words ${message.streaming ? 'opacity-80 italic' : ''}`}>
            {message.content}
            {message.streaming && <span className="inline-block animate-pulse ml-1">...</span>}
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex-1 overflow-y-auto p-4 bg-slate-50" data-testid="message-list">
      <div className="space-y-4">
        {visibleMessages.map(renderMessage)}
        {isLoading && (
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
