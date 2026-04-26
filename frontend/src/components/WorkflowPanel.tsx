import React, { useCallback, useEffect, useState } from 'react'
import {
  archiveWorkflowVersion,
  getWorkflows,
  getWorkflowVersions,
  publishWorkflow,
  rollbackWorkflow,
} from '../services/api'
import type { WorkflowEditorSelection, WorkflowSummary, WorkflowVersionSummary } from '../types'
import type { WorkflowVersionMutation } from './Orchestrator'

interface WorkflowPanelProps {
  currentUserId: string
  workflowCode?: string
  refreshSignal?: WorkflowVersionMutation | null
  onWorkflowVersionMutation?: (mutation: WorkflowVersionMutation) => void
  onEditVersion?: (selection: WorkflowEditorSelection) => void
}

interface WorkflowVersionGroup {
  workflow: WorkflowSummary
  versions: WorkflowVersionSummary[]
}

const WorkflowPanel: React.FC<WorkflowPanelProps> = ({
  currentUserId,
  workflowCode,
  refreshSignal,
  onWorkflowVersionMutation,
  onEditVersion,
}) => {
  const [groups, setGroups] = useState<WorkflowVersionGroup[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setIsLoading(true)
    setError(null)

    try {
      const workflows = (await getWorkflows()).filter((item) => item.createdBy !== 'system')
      const orderedWorkflows = [...workflows].sort((left, right) => {
        const leftIsCurrent = left.workflowCode === workflowCode
        const rightIsCurrent = right.workflowCode === workflowCode
        if (leftIsCurrent && !rightIsCurrent) return -1
        if (!leftIsCurrent && rightIsCurrent) return 1
        return 0
      })

      if (workflowCode && !orderedWorkflows.some((item) => item.workflowCode === workflowCode)) {
        orderedWorkflows.unshift({
          id: 0,
          workflowCode,
          name: workflowCode,
          status: 'draft',
        })
      }

      const versionGroups = await Promise.all(
        orderedWorkflows.map(async (workflow) => ({
          workflow,
          versions: (await getWorkflowVersions(workflow.workflowCode)).filter(
            (version) => String(version.status || '').toLowerCase() !== 'draft'
          ),
        }))
      )

      setGroups(
        versionGroups.filter(
          (group) => group.versions.length > 0 || group.workflow.workflowCode === workflowCode
        )
      )
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '加载工作流版本失败。')
      setGroups([])
    } finally {
      setIsLoading(false)
    }
  }, [workflowCode])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    if (!refreshSignal) return
    void load()
  }, [load, refreshSignal])

  const handlePublish = async (targetWorkflowCode: string, version: string) => {
    await publishWorkflow(targetWorkflowCode, version, currentUserId)
    onWorkflowVersionMutation?.({
      workflowCode: targetWorkflowCode,
      version,
      action: 'publish',
      refreshAt: Date.now(),
    })
    await load()
  }

  const handleRollback = async (targetWorkflowCode: string, version: string) => {
    await rollbackWorkflow(targetWorkflowCode, version, currentUserId)
    onWorkflowVersionMutation?.({
      workflowCode: targetWorkflowCode,
      version,
      action: 'rollback',
      refreshAt: Date.now(),
    })
    await load()
  }

  const handleArchive = async (targetWorkflowCode: string, version: string) => {
    await archiveWorkflowVersion(targetWorkflowCode, version, currentUserId)
    onWorkflowVersionMutation?.({
      workflowCode: targetWorkflowCode,
      version,
      action: 'archive',
      refreshAt: Date.now(),
    })
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
    <div className="panel-card h-full flex flex-col" data-testid="workflow-version-panel">
      <div className="panel-header">
        <div>
          <div className="panel-title">工作流版本</div>
          <div className="text-xs text-slate-500">
            查看每个工作流的已保存、已发布、已回滚和已归档版本。
          </div>
        </div>
        <button className="text-xs text-slate-500 hover:text-slate-700" onClick={() => void load()}>
          刷新
        </button>
      </div>
      <div className="panel-body space-y-3">
        {workflowCode ? (
          <div className="rounded-xl border border-sky-200 bg-sky-50/70 px-3 py-2 text-xs text-sky-700">
            正在查看当前工作流的版本记录
          </div>
        ) : (
          <div className="rounded-xl border border-slate-200 bg-white/70 px-3 py-2 text-xs text-slate-500">
            当前未选中工作流，正在展示全部工作流的已保存和已发布版本。
          </div>
        )}

        {isLoading && <div className="text-sm text-slate-500">加载中...</div>}
        {error && <div className="text-sm text-red-600">{error}</div>}

        {!isLoading && !error && groups.length === 0 && (
          <div className="rounded-xl border border-dashed border-slate-200 bg-slate-50/60 p-4 text-sm text-slate-500">
            暂无可用工作流版本。
          </div>
        )}

        {!isLoading && !error && groups.length > 0 && (
          <div className="space-y-4">
            {groups.map((group) => (
              <section
                key={group.workflow.workflowCode}
                className="rounded-2xl border border-slate-200 bg-white/70 p-4"
              >
                <div className="mb-3 flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <div className="text-sm font-semibold text-slate-800">
                      {group.workflow.name || '未命名工作流'}
                    </div>
                    <div className="mt-1 text-xs text-slate-500">
                      共 {group.versions.length} 个版本
                      {group.workflow.currentVersion
                        ? ` / 当前发布 ${group.workflow.currentVersion}`
                        : ''}
                    </div>
                  </div>
                  {group.workflow.workflowCode === workflowCode && (
                    <div className="rounded-full border border-sky-200 bg-sky-50 px-3 py-1 text-xs text-sky-700">
                      当前工作流
                    </div>
                  )}
                </div>

                {group.versions.length === 0 ? (
                  <div className="rounded-xl border border-dashed border-slate-200 bg-slate-50/60 p-4 text-sm text-slate-500">
                    该工作流还没有已保存版本。
                  </div>
                ) : (
                  <div className="space-y-2">
                    {group.versions.map((version) => (
                      <div
                        key={`${group.workflow.workflowCode}_${version.version}`}
                        className="rounded-xl bg-slate-50 px-3 py-3"
                      >
                        <div className="flex flex-wrap items-start justify-between gap-3">
                          <div className="text-sm text-slate-700">
                            <div className="font-medium text-slate-800">{version.version}</div>
                            <div className="mt-1 text-xs uppercase text-slate-400">
                              {displayVersionStatus(version.status)}
                            </div>
                          </div>
                          <div className="flex items-center gap-2">
                            <button
                              className="rounded-md border border-sky-200 px-2 py-1 text-xs text-sky-700 hover:border-sky-300"
                              onClick={() =>
                                onEditVersion?.({
                                  workflowCode: group.workflow.workflowCode,
                                  workflowName: group.workflow.name,
                                  publishedVersion: group.workflow.currentVersion,
                                  version: { ...version },
                                })
                              }
                            >
                              编辑
                            </button>
                            <button
                              className="rounded-md border border-slate-200 px-2 py-1 text-xs text-slate-600 hover:border-slate-400"
                              onClick={() =>
                                void handlePublish(group.workflow.workflowCode, version.version)
                              }
                            >
                              发布
                            </button>
                            <button
                              className="rounded-md border border-slate-200 px-2 py-1 text-xs text-slate-600 hover:border-slate-400"
                              onClick={() =>
                                void handleRollback(group.workflow.workflowCode, version.version)
                              }
                            >
                              回滚
                            </button>
                            <button
                              className="rounded-md border border-red-200 px-2 py-1 text-xs text-red-600 hover:border-red-300"
                              onClick={() =>
                                void handleArchive(group.workflow.workflowCode, version.version)
                              }
                            >
                              归档
                            </button>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

export default WorkflowPanel
