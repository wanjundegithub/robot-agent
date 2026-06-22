import React, { useCallback, useEffect, useMemo, useState } from 'react'
import {
  getKnowledgeSpaces,
  getRobotBindings,
  getRobots,
  getWorkflowSpaces,
  publishRobot,
  saveRobot,
  updateRobotBindings,
} from '../services/api'
import type { KnowledgeSpace, RobotBinding, RobotConfig, WorkflowSpace } from '../types'

interface RobotConfigPanelProps {
  currentUserId: string
  onRobotsChanged?: (robots: RobotConfig[]) => void
}

const emptyRobot: RobotConfig = {
  robot_code: '',
  name: '',
  description: '',
  opening_message: '',
  status: 'DRAFT',
  default_model_code: '',
  route_strategy: 'PARALLEL_AGGREGATE',
  workflow_binding_count: 0,
  knowledge_binding_count: 0,
}

const RobotConfigPanel: React.FC<RobotConfigPanelProps> = ({ currentUserId, onRobotsChanged }) => {
  const [robots, setRobots] = useState<RobotConfig[]>([])
  const [selectedRobotCode, setSelectedRobotCode] = useState('')
  const [draft, setDraft] = useState<RobotConfig>(emptyRobot)
  const [bindings, setBindings] = useState<RobotBinding[]>([])
  const [workflowSpaces, setWorkflowSpaces] = useState<WorkflowSpace[]>([])
  const [knowledgeSpaces, setKnowledgeSpaces] = useState<KnowledgeSpace[]>([])
  const [notice, setNotice] = useState('')
  const [isSaving, setIsSaving] = useState(false)

  const selectedWorkflowSpaceCodes = useMemo(
    () => bindings.filter((item) => item.binding_type === 'WORKFLOW_SPACE' && item.enabled).map((item) => item.target_code),
    [bindings]
  )

  const selectedKbCodes = useMemo(
    () => bindings.filter((item) => item.binding_type === 'KNOWLEDGE_SPACE' && item.enabled).map((item) => item.target_code),
    [bindings]
  )

  const loadRobots = useCallback(async (preferredRobotCode?: string) => {
    const loaded = await getRobots()
    setRobots(loaded)
    onRobotsChanged?.(loaded)
    const nextSelectedCode = preferredRobotCode || selectedRobotCode
    const nextSelectedRobot = loaded.find((robot) => robot.robot_code === nextSelectedCode)
    if (nextSelectedRobot) {
      setSelectedRobotCode(nextSelectedRobot.robot_code)
      setDraft(nextSelectedRobot)
      return
    }
    if (loaded.length > 0) {
      setSelectedRobotCode(loaded[0].robot_code)
      setDraft(loaded[0])
    }
  }, [onRobotsChanged])

  const loadBindings = useCallback(async (robotCode: string) => {
    if (!robotCode) {
      setBindings([])
      return
    }
    setBindings(await getRobotBindings(robotCode))
  }, [])

  useEffect(() => {
    void Promise.all([
      loadRobots(),
      getWorkflowSpaces().then(setWorkflowSpaces),
      getKnowledgeSpaces().then(setKnowledgeSpaces),
    ]).catch((error) => setNotice(error instanceof Error ? error.message : String(error)))
  }, [loadRobots])

  useEffect(() => {
    void loadBindings(selectedRobotCode).catch((error) => setNotice(error instanceof Error ? error.message : String(error)))
  }, [loadBindings, selectedRobotCode])

  const selectRobot = (robot: RobotConfig) => {
    setSelectedRobotCode(robot.robot_code)
    setDraft(robot)
    setNotice('')
  }

  const startNewRobot = () => {
    setSelectedRobotCode('')
    setDraft(emptyRobot)
    setBindings([])
    setNotice('')
  }

  const toggleBinding = (type: 'WORKFLOW_SPACE' | 'KNOWLEDGE_SPACE', targetCode: string) => {
    setBindings((current) => {
      const exists = current.some((item) => item.binding_type === type && item.target_code === targetCode && item.enabled)
      if (exists) {
        return current.filter((item) => !(item.binding_type === type && item.target_code === targetCode))
      }
      return [
        ...current,
        {
          robot_code: draft.robot_code,
          binding_type: type,
          target_code: targetCode,
          enabled: true,
        },
      ]
    })
  }

  const persistRobot = async (publishAfterSave: boolean) => {
    if (!draft.robot_code.trim() || !draft.name.trim()) {
      setNotice('请填写机器人编码和名称')
      return
    }
    if (publishAfterSave && selectedWorkflowSpaceCodes.length === 0) {
      setNotice('发布前请至少绑定一个工作流空间')
      return
    }
    if (publishAfterSave && selectedKbCodes.length === 0) {
      setNotice('发布前请至少绑定一个知识空间')
      return
    }
    setIsSaving(true)
    try {
      const saved = await saveRobot({
        workspace_id: draft.workspace_id ?? 1,
        robot_code: draft.robot_code.trim(),
        name: draft.name.trim(),
        description: draft.description || '',
        avatar: draft.avatar || '',
        opening_message: draft.opening_message || '',
        status: draft.status || 'DRAFT',
        default_model_code: draft.default_model_code || '',
        route_strategy: draft.route_strategy || 'PARALLEL_AGGREGATE',
        created_by: currentUserId,
      })
      await updateRobotBindings(saved.robot_code, {
        workspace_id: saved.workspace_id ?? 1,
        workflow_space_codes: selectedWorkflowSpaceCodes,
        kb_codes: selectedKbCodes,
      })
      const finalRobot = publishAfterSave ? await publishRobot(saved.robot_code) : saved
      setSelectedRobotCode(finalRobot.robot_code)
      setDraft(finalRobot)
      await loadBindings(finalRobot.robot_code)
      await loadRobots(finalRobot.robot_code)
      setNotice(publishAfterSave ? '机器人已保存并发布' : '机器人草稿已保存')
    } catch (error) {
      setNotice(error instanceof Error ? error.message : String(error))
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <div className="grid min-h-0 w-full flex-1 gap-4 xl:grid-cols-[280px_minmax(0,1fr)]" data-testid="robot-config-panel">
      <aside className="panel-card flex min-h-0 flex-col p-0">
        <div className="panel-header">
          <div className="panel-title">机器人配置</div>
          <button className="prompt-secondary" type="button" onClick={startNewRobot}>
            新增
          </button>
        </div>
        <div className="min-h-0 flex-1 space-y-2 overflow-auto p-3">
          {robots.map((robot) => (
            <button
              key={robot.robot_code}
              type="button"
              className={`w-full rounded-xl border px-3 py-3 text-left text-sm ${
                robot.robot_code === draft.robot_code ? 'border-blue-300 bg-blue-50' : 'border-slate-200 bg-white'
              }`}
              onClick={() => selectRobot(robot)}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="font-semibold text-slate-800">{robot.name}</span>
                <span className="rounded-full bg-slate-100 px-2 py-1 text-xs text-slate-500">{robot.status}</span>
              </div>
              <div className="mt-1 text-xs text-slate-500">
                {robot.robot_code} / {robot.workflow_binding_count ?? 0} 工作流空间 / {robot.knowledge_binding_count ?? 0} 知识空间
              </div>
            </button>
          ))}
        </div>
      </aside>

      <section className="panel-card flex min-h-0 flex-col p-0">
        <div className="panel-header">
          <div>
            <div className="panel-title">{draft.name || '新建机器人'}</div>
            <div className="mt-1 text-xs text-slate-500">{draft.robot_code || '保存后用于聊天入口 robot_code'}</div>
          </div>
          <div className="flex gap-2">
            <button className="prompt-secondary" type="button" onClick={() => void persistRobot(false)} disabled={isSaving}>
              保存草稿
            </button>
            <button className="prompt-primary" type="button" onClick={() => void persistRobot(true)} disabled={isSaving}>
              保存并发布
            </button>
          </div>
        </div>

        <div className="grid min-h-0 flex-1 gap-4 overflow-auto p-4 xl:grid-cols-2">
          <div className="rounded-2xl border border-slate-200 bg-white p-4 xl:col-span-2">
            <div className="text-sm font-semibold text-slate-800">基础信息</div>
            <div className="mt-4 grid gap-3">
              <label className="text-xs font-semibold text-slate-500">
                机器人名称
                <input
                  className="mt-1 w-full rounded-xl border border-slate-200 px-3 py-2 text-sm text-slate-700"
                  value={draft.name}
                  onChange={(event) => setDraft((current) => ({ ...current, name: event.target.value }))}
                />
              </label>
              <label className="text-xs font-semibold text-slate-500">
                机器人编码
                <input
                  className="mt-1 w-full rounded-xl border border-slate-200 px-3 py-2 text-sm text-slate-700"
                  value={draft.robot_code}
                  onChange={(event) => setDraft((current) => ({ ...current, robot_code: event.target.value }))}
                  disabled={Boolean(selectedRobotCode)}
                />
              </label>
              <label className="text-xs font-semibold text-slate-500">
                开场白
                <textarea
                  className="mt-1 min-h-[88px] w-full rounded-xl border border-slate-200 px-3 py-2 text-sm text-slate-700"
                  value={draft.opening_message || ''}
                  onChange={(event) => setDraft((current) => ({ ...current, opening_message: event.target.value }))}
                />
              </label>
            </div>
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white p-4">
            <div className="mb-3 flex items-center justify-between">
              <div className="text-sm font-semibold text-slate-800">工作流空间绑定</div>
              <span className="text-xs text-slate-500">{selectedWorkflowSpaceCodes.length} 已选</span>
            </div>
            <div className="grid gap-2">
              {workflowSpaces.map((space) => (
                <label key={space.space_code} className="flex items-center justify-between rounded-xl border border-slate-200 px-3 py-2 text-sm">
                  <span>
                    <span className="font-semibold text-slate-700">{space.name}</span>
                    <span className="ml-2 text-xs text-slate-400">{space.space_code}</span>
                  </span>
                  <input
                    type="checkbox"
                    checked={selectedWorkflowSpaceCodes.includes(space.space_code)}
                    onChange={() => toggleBinding('WORKFLOW_SPACE', space.space_code)}
                  />
                </label>
              ))}
            </div>
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white p-4">
            <div className="mb-3 flex items-center justify-between">
              <div className="text-sm font-semibold text-slate-800">知识空间绑定</div>
              <span className="text-xs text-slate-500">{selectedKbCodes.length} 已选</span>
            </div>
            <div className="grid gap-2 md:grid-cols-2">
              {knowledgeSpaces.map((space) => (
                <label key={space.kbCode} className="flex items-center justify-between rounded-xl border border-slate-200 px-3 py-2 text-sm">
                  <span>
                    <span className="font-semibold text-slate-700">{space.name}</span>
                    <span className="ml-2 text-xs text-slate-400">{space.kbCode}</span>
                  </span>
                  <input
                    type="checkbox"
                    checked={selectedKbCodes.includes(space.kbCode)}
                    onChange={() => toggleBinding('KNOWLEDGE_SPACE', space.kbCode)}
                  />
                </label>
              ))}
            </div>
          </div>
        </div>

        {notice && <div className="border-t border-slate-100 px-4 py-3 text-sm text-slate-600">{notice}</div>}
      </section>
    </div>
  )
}

export default RobotConfigPanel
