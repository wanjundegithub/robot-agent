import React, { useEffect, useState } from 'react'
import { getAnalyticsDashboard } from '../services/api'
import type { AnalyticsDashboard } from '../types'

interface AnalyticsPanelProps {
  sessionId?: string
}

const AnalyticsPanel: React.FC<AnalyticsPanelProps> = ({ sessionId }) => {
  const [dashboard, setDashboard] = useState<AnalyticsDashboard | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!sessionId) return
    void getAnalyticsDashboard(sessionId)
      .then(setDashboard)
      .catch((loadError) => setError(loadError instanceof Error ? loadError.message : '加载分析数据失败'))
  }, [sessionId])

  return (
    <div className="panel-card h-full flex flex-col">
      <div className="panel-header">
        <div>
          <div className="panel-title">Analytics</div>
          <div className="text-xs text-slate-500">Phase 4 KPI / cost dashboard</div>
        </div>
      </div>
      <div className="panel-body space-y-3 overflow-y-auto">
        {error && <div className="text-sm text-rose-600">{error}</div>}
        {!dashboard && !error && <div className="text-sm text-slate-500">加载分析数据中...</div>}
        {dashboard && (
          <>
            <div className="grid grid-cols-2 gap-3">
              <MetricCard label="Intent Accuracy" value={dashboard.summary.intent_accuracy} />
              <MetricCard label="Completion Rate" value={dashboard.summary.task_completion_rate} />
              <MetricCard label="Human Intervention" value={dashboard.summary.human_intervention_rate} />
              <MetricCard label="Total Cost" value={dashboard.summary.total_cost} suffix="$" />
            </div>

            <div>
              <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">Workflow Breakdown</div>
              <ul className="space-y-2">
                {dashboard.workflow_breakdown.map((item, index) => (
                  <li key={`${item.workflow_code ?? 'workflow'}_${index}`} className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-2 text-sm text-slate-700">
                    <div className="font-medium">{String(item.workflow_code)}</div>
                    <div className="text-xs text-slate-500">
                      completion: {String(item.completion_rate)} · avg: {String(item.avg_completion_seconds)}s · cost: ${String(item.total_cost)}
                    </div>
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">Experiment Summary</div>
              <ul className="space-y-2">
                {dashboard.experiment_summary.length === 0 && (
                  <li className="text-sm text-slate-500">暂无实验数据。</li>
                )}
                {dashboard.experiment_summary.map((item, index) => (
                  <li key={`${item.experiment_id ?? 'experiment'}_${index}`} className="rounded-xl border border-slate-200 bg-white/70 px-3 py-2 text-sm text-slate-700">
                    {String(item.experiment_id)} · group {String(item.experiment_group)} · executions {String(item.executions)}
                  </li>
                ))}
              </ul>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

interface MetricCardProps {
  label: string
  value: number
  suffix?: string
}

const MetricCard: React.FC<MetricCardProps> = ({ label, value, suffix = '' }) => (
  <div className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-3">
    <div className="text-[11px] uppercase tracking-[0.18em] text-slate-400">{label}</div>
    <div className="mt-1 text-lg font-semibold text-slate-900">
      {suffix === '$' ? `${suffix}${value.toFixed(2)}` : value.toFixed(2)}
    </div>
  </div>
)

export default AnalyticsPanel
