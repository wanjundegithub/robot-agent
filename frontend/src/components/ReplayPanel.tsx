import React, { useEffect, useState } from 'react'
import { getExecutionReplay } from '../services/api'
import type { ReplayResponse } from '../types'

interface ReplayPanelProps {
  executionId?: string | null
}

const ReplayPanel: React.FC<ReplayPanelProps> = ({ executionId }) => {
  const [replay, setReplay] = useState<ReplayResponse | null>(null)

  useEffect(() => {
    if (!executionId) return
    void getExecutionReplay(executionId)
      .then(setReplay)
      .catch(() => setReplay(null))
  }, [executionId])

  return (
    <div className="panel-card h-full flex flex-col">
      <div className="panel-header">
        <div>
          <div className="panel-title">执行回放</div>
          <div className="text-xs text-slate-500">查看历史执行过程</div>
        </div>
      </div>
      <div className="panel-body space-y-3 overflow-y-auto">
        {!executionId && <div className="text-sm text-slate-500">选择 execution 后可查看回放。</div>}
        {executionId && !replay && <div className="text-sm text-slate-500">加载回放中...</div>}
        {replay && (
          <>
            <div className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-3 text-sm text-slate-700">
              <div className="font-medium">{replay.workflow_code}</div>
              <div className="text-xs text-slate-500">{replay.workflow_version} · {replay.status}</div>
            </div>
            <div>
              <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">事件流</div>
              <ul className="space-y-2">
                {replay.event_stream.slice(0, 6).map((event, index) => (
                  <li key={`${String(event.event_type)}_${index}`} className="rounded-lg bg-white/70 px-3 py-2 text-sm text-slate-700">
                    {String(event.event_type)}
                  </li>
                ))}
              </ul>
            </div>
            <div>
              <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">最终输出</div>
              <pre className="max-h-48 overflow-auto rounded-xl bg-slate-950 px-3 py-3 text-xs text-slate-100">
                {JSON.stringify(replay.output_variables, null, 2)}
              </pre>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

export default ReplayPanel
