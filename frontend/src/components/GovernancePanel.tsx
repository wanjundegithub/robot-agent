import React, { useEffect, useState } from 'react'
import { getOperationalReadiness } from '../services/api'
import type { GovernanceNotice, OperationalReadiness } from '../types'

interface GovernancePanelProps {
  sessionId: string
  notice: GovernanceNotice | null
}

const GovernancePanel: React.FC<GovernancePanelProps> = ({ sessionId, notice }) => {
  const [readiness, setReadiness] = useState<OperationalReadiness | null>(null)

  useEffect(() => {
    if (!sessionId) return
    getOperationalReadiness(sessionId)
      .then(setReadiness)
      .catch((error) => console.error('获取第5阶段运行状态失败:', error))
  }, [sessionId, notice?.updated_at])

  const noticeClass =
    notice?.tone === 'danger'
      ? 'border-rose-200 bg-rose-50 text-rose-700'
      : notice?.tone === 'warning'
        ? 'border-amber-200 bg-amber-50 text-amber-700'
        : 'border-sky-200 bg-sky-50 text-sky-700'

  const archiveSummary = readiness?.archive.summary ?? {}
  const rateLimits = readiness?.protection.rate_limits ?? []
  const recentCandidates = readiness?.archive.recent_candidates ?? []

  const displayScope = (value: unknown) => {
    switch (String(value || '')) {
      case 'user':
        return '用户'
      case 'session':
        return '会话'
      case 'workflow':
        return '流程'
      case 'tool':
        return '工具'
      default:
        return String(value || '')
    }
  }

  const displayTier = (value: unknown) => {
    switch (String(value || '')) {
      case 'hot':
        return '热'
      case 'warm':
        return '温'
      case 'cold':
        return '冷'
      default:
        return String(value || '')
    }
  }

  return (
    <div className="panel-card h-full flex flex-col">
      <div className="panel-header">
        <div>
          <div className="panel-title">治理视图</div>
          <div className="text-xs text-slate-500">第五阶段 权限 / 保护 / 归档</div>
        </div>
      </div>
      <div className="panel-body space-y-4 overflow-y-auto">
        <div className={`rounded-xl border px-3 py-3 text-sm ${noticeClass}`}>
          <div className="font-medium">{notice?.title ?? '当前无阻断事件'}</div>
          <div className="mt-1 text-xs opacity-80">
            {notice?.detail ?? '权限拒绝、二次确认、限流和降级会在这里持续展示。'}
          </div>
        </div>

        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">限流规则</div>
          <ul className="space-y-2">
            {rateLimits.map((rule) => (
              <li key={`${String(rule.scope)}_${String(rule.limit)}`} className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-2">
                <div className="text-sm font-medium text-slate-800">{displayScope(rule.scope)}</div>
                <div className="text-xs text-slate-500">
                  {String(rule.limit)} / {String(rule.window_seconds)}s · {String(rule.redis_key_pattern)}
                </div>
              </li>
            ))}
          </ul>
        </div>

        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">归档概览</div>
          <div className="grid grid-cols-2 gap-2 text-sm">
            <div className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-2">
              热数据: {String(archiveSummary.hot_executions ?? 0)}
            </div>
            <div className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-2">
              温数据: {String(archiveSummary.warm_executions ?? 0)}
            </div>
            <div className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-2">
              冷数据: {String(archiveSummary.cold_executions ?? 0)}
            </div>
            <div className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-2">
              清理候选: {String(archiveSummary.cleanup_candidates ?? 0)}
            </div>
          </div>
        </div>

        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">生命周期预览</div>
          {recentCandidates.length === 0 && (
            <div className="text-sm text-slate-500">暂无归档候选。</div>
          )}
          <ul className="space-y-2">
            {recentCandidates.slice(0, 4).map((item) => (
              <li key={String(item.execution_id)} className="rounded-xl border border-slate-200 bg-white px-3 py-2">
                <div className="text-sm font-medium text-slate-800">{String(item.workflow_code)}</div>
                <div className="text-xs text-slate-500">
                  {displayTier(item.tier)} · {String(item.cleanup_action)} · {String(item.archive_target)}
                </div>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  )
}

export default GovernancePanel
