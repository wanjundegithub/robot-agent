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
          <div className="panel-title">分析看板</div>
          <div className="text-xs text-slate-500">关键指标与成本概览</div>
        </div>
      </div>
      <div className="panel-body space-y-3 overflow-y-auto">
        {error && <div className="text-sm text-rose-600">{error}</div>}
        {!dashboard && !error && <div className="text-sm text-slate-500">加载分析数据中...</div>}
        {dashboard && (
          <>
            <div className="grid grid-cols-2 gap-3">
              <MetricCard label="意图准确率" value={dashboard.summary.intent_accuracy} />
              <MetricCard label="完成率" value={dashboard.summary.task_completion_rate} />
              <MetricCard label="人工介入率" value={dashboard.summary.human_intervention_rate} />
              <MetricCard label="总成本" value={dashboard.summary.total_cost} suffix="$" />
            </div>

            <div>
              <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">流程分布</div>
              <ul className="space-y-2">
                {dashboard.workflow_breakdown.map((item, index) => (
                  <li key={`${item.workflow_code ?? 'workflow'}_${index}`} className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-2 text-sm text-slate-700">
                    <div className="font-medium">{String(item.workflow_code)}</div>
                    <div className="text-xs text-slate-500">
                      完成率：{String(item.completion_rate)} · 平均耗时：{String(item.avg_completion_seconds)} 秒 · 成本：${String(item.total_cost)}
                    </div>
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">实验汇总</div>
              <ul className="space-y-2">
                {dashboard.experiment_summary.length === 0 && (
                  <li className="text-sm text-slate-500">暂无实验数据。</li>
                )}
                {dashboard.experiment_summary.map((item, index) => (
                  <li key={`${item.experiment_id ?? 'experiment'}_${index}`} className="rounded-xl border border-slate-200 bg-white/70 px-3 py-2 text-sm text-slate-700">
                    {String(item.experiment_id)} · 分组 {String(item.experiment_group)} · 执行次数 {String(item.executions)}
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
