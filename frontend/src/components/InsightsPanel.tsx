import React, { useEffect, useState } from 'react'
import { getSubflowRecommendations, runRagEvaluation } from '../services/api'
import type { RagEvaluationResponse, SubflowRecommendationResponse } from '../types'

interface InsightsPanelProps {
  workflowCode?: string
}

const InsightsPanel: React.FC<InsightsPanelProps> = ({ workflowCode }) => {
  const [evaluation, setEvaluation] = useState<RagEvaluationResponse | null>(null)
  const [recommendations, setRecommendations] = useState<SubflowRecommendationResponse | null>(null)

  useEffect(() => {
    if (!workflowCode) return
    void getSubflowRecommendations(workflowCode, '我想确认座位库存和退票政策')
      .then(setRecommendations)
      .catch(() => setRecommendations(null))
  }, [workflowCode])

  const handleRunEvaluation = async () => {
    try {
      const result = await runRagEvaluation()
      setEvaluation(result)
    } catch {
      setEvaluation(null)
    }
  }

  return (
    <div className="panel-card h-full flex flex-col">
      <div className="panel-header">
        <div>
          <div className="panel-title">Insights</div>
          <div className="text-xs text-slate-500">Phase 4 recommendation / RAG evaluation</div>
        </div>
        <button className="text-xs text-slate-500 hover:text-slate-700" onClick={() => void handleRunEvaluation()}>
          运行评测
        </button>
      </div>
      <div className="panel-body space-y-3 overflow-y-auto">
        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">Subflow Recommendations</div>
          <ul className="space-y-2">
            {(recommendations?.recommendations || []).map((item, index) => (
              <li key={`${String(item.subflow_code)}_${index}`} className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-2 text-sm text-slate-700">
                {String(item.subflow_code)}@{String(item.subflow_version)} · score {String(item.score)} · {String(item.reason)}
              </li>
            ))}
            {!recommendations && <li className="text-sm text-slate-500">暂无推荐结果。</li>}
          </ul>
        </div>
        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">RAG Evaluation</div>
          {!evaluation && <div className="text-sm text-slate-500">点击“运行评测”获取评测结果。</div>}
          {evaluation && (
            <div className="rounded-xl border border-slate-200 bg-white/80 px-3 py-3 text-sm text-slate-700">
              dataset: {evaluation.dataset_size} · hit rate: {evaluation.hit_rate} · avg relevance: {evaluation.avg_relevance}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

export default InsightsPanel
