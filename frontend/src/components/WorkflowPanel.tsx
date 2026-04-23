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
          versions: await getWorkflowVersions(workflow.workflowCode),
        }))
      )

      setGroups(
        versionGroups.filter(
          (group) => group.versions.length > 0 || group.workflow.workflowCode === workflowCode
        )
      )
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '�������̰汾ʧ��')
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
        return '�ݸ�'
      case 'published':
        return '�ѷ���'
      case 'archived':
        return '�ѹ鵵'
      default:
        return value
    }
  }

  return (
    <div className="panel-card h-full flex flex-col">
      <div className="panel-header">
        <div>
          <div className="panel-title">���̰汾</div>
          <div className="text-xs text-slate-500">չʾ�����ѱ�����ѷ��������̰汾���������̷��顣</div>
        </div>
        <button className="text-xs text-slate-500 hover:text-slate-700" onClick={() => void load()}>
          ˢ��
        </button>
      </div>
      <div className="panel-body space-y-3">
        {workflowCode ? (
          <div className="rounded-xl border border-sky-200 bg-sky-50/70 px-3 py-2 text-xs text-sky-700">
            ��ǰ�༭���̣�{workflowCode}
          </div>
        ) : (
          <div className="rounded-xl border border-slate-200 bg-white/70 px-3 py-2 text-xs text-slate-500">
            ��ǰδѡ�����̣�����չʾȫ���ѱ�����ѷ����İ汾��
          </div>
        )}

        {isLoading && <div className="text-sm text-slate-500">������...</div>}
        {error && <div className="text-sm text-red-600">{error}</div>}

        {!isLoading && !error && groups.length === 0 && (
          <div className="rounded-xl border border-dashed border-slate-200 bg-slate-50/60 p-4 text-sm text-slate-500">
            ��û�п�չʾ�����̰汾��
          </div>
        )}

        {!isLoading && !error && groups.length > 0 && (
          <div className="space-y-4">
            {groups.map((group) => (
              <section key={group.workflow.workflowCode} className="rounded-2xl border border-slate-200 bg-white/70 p-4">
                <div className="mb-3 flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <div className="text-sm font-semibold text-slate-800">{group.workflow.name || group.workflow.workflowCode}</div>
                    <div className="mt-1 text-xs text-slate-500">
                      {group.workflow.workflowCode} �� �� {group.versions.length} ���汾
                      {group.workflow.currentVersion ? ` �� ��ǰ���� ${group.workflow.currentVersion}` : ''}
                    </div>
                  </div>
                  {group.workflow.workflowCode === workflowCode && (
                    <div className="rounded-full border border-sky-200 bg-sky-50 px-3 py-1 text-xs text-sky-700">
                      ��ǰ����
                    </div>
                  )}
                </div>

                {group.versions.length === 0 ? (
                  <div className="rounded-xl border border-dashed border-slate-200 bg-slate-50/60 p-4 text-sm text-slate-500">
                    ��ǰ���̻�û�а汾���ݡ�
                  </div>
                ) : (
                  <div className="space-y-2">
                    {group.versions.map((version) => (
                      <div key={`${group.workflow.workflowCode}_${version.version}`} className="rounded-xl bg-slate-50 px-3 py-3">
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
                              �༭
                            </button>
                            <button
                              className="rounded-md border border-slate-200 px-2 py-1 text-xs text-slate-600 hover:border-slate-400"
                              onClick={() => void handlePublish(group.workflow.workflowCode, version.version)}
                            >
                              ����
                            </button>
                            <button
                              className="rounded-md border border-slate-200 px-2 py-1 text-xs text-slate-600 hover:border-slate-400"
                              onClick={() => void handleRollback(group.workflow.workflowCode, version.version)}
                            >
                              �ع�
                            </button>
                            <button
                              className="rounded-md border border-red-200 px-2 py-1 text-xs text-red-600 hover:border-red-300"
                              onClick={() => void handleArchive(group.workflow.workflowCode, version.version)}
                            >
                              ɾ��
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
