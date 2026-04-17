import React, { useEffect, useState } from 'react'
import { getWorkflows, getWorkflowVersions, publishWorkflow, rollbackWorkflow } from '../services/api'
import type { WorkflowSummary, WorkflowVersionSummary } from '../types'

const WorkflowPanel: React.FC = () => {
  const [workflows, setWorkflows] = useState<WorkflowSummary[]>([])
  const [versions, setVersions] = useState<Record<string, WorkflowVersionSummary[]>>({})
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = async () => {
    setIsLoading(true)
    setError(null)
    try {
      const workflowItems = await getWorkflows()
      setWorkflows(workflowItems)
      const versionEntries = await Promise.all(
        workflowItems.map(async (workflow) => [workflow.workflowCode, await getWorkflowVersions(workflow.workflowCode)] as const)
      )
      setVersions(Object.fromEntries(versionEntries))
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '加载工作流失败')
    } finally {
      setIsLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const handlePublish = async (workflowCode: string, version: string) => {
    await publishWorkflow(workflowCode, version)
    await load()
  }

  const handleRollback = async (workflowCode: string, version: string) => {
    await rollbackWorkflow(workflowCode, version)
    await load()
  }

  const displayVersionStatus = (value: string) => {
    switch ((value || '').toLowerCase()) {
      case 'draft':
        return '草稿'
      case 'published':
        return '已发布'
      case 'archived':
        return '已归档'
      default:
        return value
    }
  }

  return (
    <div className="panel-card h-full flex flex-col">
      <div className="panel-header">
        <div>
          <div className="panel-title">流程版本</div>
          <div className="text-xs text-slate-500">第三阶段 发布 / 回滚 / 路由候选</div>
        </div>
        <button className="text-xs text-slate-500 hover:text-slate-700" onClick={() => void load()}>
          刷新
        </button>
      </div>
      <div className="panel-body space-y-3">
        {isLoading && <div className="text-sm text-slate-500">加载中...</div>}
        {error && <div className="text-sm text-red-600">{error}</div>}
        {!isLoading && !error && workflows.map((workflow) => (
          <div key={workflow.workflowCode} className="rounded-xl border border-slate-200 bg-white/60 p-3">
            <div className="flex items-center justify-between gap-3">
              <div>
                <div className="font-medium text-slate-800">{workflow.name}</div>
                <div className="text-xs text-slate-500">{workflow.workflowCode}</div>
              </div>
              <div className="text-xs text-slate-500">当前版本：{workflow.currentVersion || '-'}</div>
            </div>
            <div className="mt-2 space-y-2">
              {(versions[workflow.workflowCode] || []).map((version) => (
                <div key={version.version} className="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-2">
                  <div className="text-sm text-slate-700">
                    {version.version}
                    <span className="ml-2 text-xs uppercase text-slate-400">{displayVersionStatus(version.status)}</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      className="rounded-md border border-slate-200 px-2 py-1 text-xs text-slate-600 hover:border-slate-400"
                      onClick={() => void handlePublish(workflow.workflowCode, version.version)}
                    >
                      发布
                    </button>
                    <button
                      className="rounded-md border border-slate-200 px-2 py-1 text-xs text-slate-600 hover:border-slate-400"
                      onClick={() => void handleRollback(workflow.workflowCode, version.version)}
                    >
                      回滚
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

export default WorkflowPanel
