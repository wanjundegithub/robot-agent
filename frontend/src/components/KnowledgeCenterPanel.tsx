import React, { useCallback, useEffect, useMemo, useState } from 'react'
import {
  createKnowledgeSpace,
  createTextKnowledgeDocument,
  deleteKnowledgeDocument,
  deleteKnowledgeSpace,
  deleteKnowledgeTask,
  getKnowledgeDocumentTasks,
  getKnowledgeDocuments,
  getKnowledgeSpaces,
  retryKnowledgeTask,
  searchKnowledge,
  updateKnowledgeDocument,
  updateKnowledgeSpace,
  uploadKnowledgeDocument,
} from '../services/api'
import type { KnowledgeDocument, KnowledgeSearchResult, KnowledgeSpace, KnowledgeTask } from '../types'

interface KnowledgeCenterPanelProps {
  currentUserId: string
}

type KnowledgeTab = 'spaces' | 'tasks' | 'search'
type DialogMode = 'create' | 'edit'

type SpaceFormState = {
  name: string
  description: string
}

type DocumentFormState = {
  mode: 'text' | 'file'
  title: string
  description: string
  content: string
  file: File | null
}

type ConfirmState =
  | { type: 'space'; kbCode: string; name: string }
  | { type: 'document'; docId: string; title: string }
  | null

const DEFAULT_TOP_K = 5

const defaultSpaceForm = (): SpaceFormState => ({
  name: '',
  description: '',
})

const defaultDocumentForm = (): DocumentFormState => ({
  mode: 'text',
  title: '',
  description: '',
  content: '',
  file: null,
})

const KnowledgeCenterPanel: React.FC<KnowledgeCenterPanelProps> = ({ currentUserId }) => {
  const [activeTab, setActiveTab] = useState<KnowledgeTab>('spaces')
  const [spaces, setSpaces] = useState<KnowledgeSpace[]>([])
  const [selectedKbCode, setSelectedKbCode] = useState('')
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([])
  const [tasks, setTasks] = useState<KnowledgeTask[]>([])
  const [searchKbCodes, setSearchKbCodes] = useState<string[]>([])
  const [searchQuery, setSearchQuery] = useState('')
  const [searchTopK, setSearchTopK] = useState(DEFAULT_TOP_K)
  const [searchResult, setSearchResult] = useState<KnowledgeSearchResult | null>(null)
  const [spaceDialogMode, setSpaceDialogMode] = useState<DialogMode | null>(null)
  const [documentDialogMode, setDocumentDialogMode] = useState<DialogMode | null>(null)
  const [editingSpaceCode, setEditingSpaceCode] = useState('')
  const [editingDocument, setEditingDocument] = useState<KnowledgeDocument | null>(null)
  const [confirmState, setConfirmState] = useState<ConfirmState>(null)
  const [spaceForm, setSpaceForm] = useState<SpaceFormState>(defaultSpaceForm)
  const [documentForm, setDocumentForm] = useState<DocumentFormState>(defaultDocumentForm)
  const [isLoadingSpaces, setIsLoadingSpaces] = useState(false)
  const [isLoadingDocuments, setIsLoadingDocuments] = useState(false)
  const [isLoadingTasks, setIsLoadingTasks] = useState(false)
  const [isSearching, setIsSearching] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [statusMessage, setStatusMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const selectedSpace = useMemo(
    () => spaces.find((space) => space.kbCode === selectedKbCode) ?? null,
    [selectedKbCode, spaces]
  )

  const selectedSpaceDocumentCount = documents.length || selectedSpace?.documentCount || 0

  const loadSpaces = useCallback(async () => {
    setIsLoadingSpaces(true)
    try {
      const loadedSpaces = await getKnowledgeSpaces()
      setSpaces(loadedSpaces)
      setSelectedKbCode((current) => {
        if (current && loadedSpaces.some((space) => space.kbCode === current)) {
          return current
        }
        return loadedSpaces[0]?.kbCode ?? ''
      })
      setSearchKbCodes((current) => {
        const availableCodes = loadedSpaces.map((space) => space.kbCode)
        const next = current.filter((code) => availableCodes.includes(code))
        return next.length > 0 ? next : availableCodes.slice(0, 1)
      })
      setError(null)
    } catch {
      setError('加载知识空间失败。')
    } finally {
      setIsLoadingSpaces(false)
    }
  }, [])

  const loadDocuments = useCallback(async (kbCode: string) => {
    if (!kbCode) {
      setDocuments([])
      return
    }
    setIsLoadingDocuments(true)
    try {
      const loadedDocuments = await getKnowledgeDocuments(kbCode)
      setDocuments(loadedDocuments)
      setError(null)
    } catch {
      setDocuments([])
      setError('加载知识失败。')
    } finally {
      setIsLoadingDocuments(false)
    }
  }, [])

  const loadTasks = useCallback(async () => {
    setIsLoadingTasks(true)
    try {
      const taskGroups = await Promise.all(
        documents.map(async (document) => {
          try {
            return await getKnowledgeDocumentTasks(document.docId)
          } catch {
            return [] as KnowledgeTask[]
          }
        })
      )
      setTasks(taskGroups.flat())
      setError(null)
    } catch {
      setTasks([])
      setError('加载采集任务失败。')
    } finally {
      setIsLoadingTasks(false)
    }
  }, [documents])

  useEffect(() => {
    void loadSpaces()
  }, [loadSpaces])

  useEffect(() => {
    void loadDocuments(selectedKbCode)
  }, [loadDocuments, selectedKbCode])

  useEffect(() => {
    if (activeTab !== 'tasks') return
    void loadTasks()
  }, [activeTab, loadTasks])

  const openCreateSpace = () => {
    setSpaceForm(defaultSpaceForm())
    setEditingSpaceCode('')
    setSpaceDialogMode('create')
    setStatusMessage(null)
    setError(null)
  }

  const openEditSpace = (space: KnowledgeSpace) => {
    setSpaceForm({
      name: space.name,
      description: space.description ?? '',
    })
    setEditingSpaceCode(space.kbCode)
    setSpaceDialogMode('edit')
    setStatusMessage(null)
    setError(null)
  }

  const openCreateDocument = () => {
    setDocumentForm(defaultDocumentForm())
    setEditingDocument(null)
    setDocumentDialogMode('create')
    setStatusMessage(null)
    setError(null)
  }

  const openEditDocument = (document: KnowledgeDocument) => {
    setDocumentForm({
      mode: String(document.sourceType).toUpperCase() === 'FILE' ? 'file' : 'text',
      title: getDocumentTitle(document),
      description: document.description ?? '',
      content: document.content ?? document.generatedSummary ?? '',
      file: null,
    })
    setEditingDocument(document)
    setDocumentDialogMode('edit')
    setStatusMessage(null)
    setError(null)
  }

  const submitSpace = async () => {
    const payload = {
      name: spaceForm.name.trim(),
      description: spaceForm.description.trim() || undefined,
    }
    if (!payload.name) {
      setError('知识空间名称不能为空。')
      return
    }
    setIsSaving(true)
    try {
      const saved =
        spaceDialogMode === 'edit'
          ? await updateKnowledgeSpace(editingSpaceCode, payload, currentUserId)
          : await createKnowledgeSpace(payload, currentUserId)
      setStatusMessage(spaceDialogMode === 'edit' ? `已更新知识空间：${saved.name}` : `已新增知识空间：${saved.name}`)
      setSpaceDialogMode(null)
      await loadSpaces()
      setSelectedKbCode(saved.kbCode)
    } catch {
      setError('保存知识空间失败。')
    } finally {
      setIsSaving(false)
    }
  }

  const submitDocument = async () => {
    if (!selectedKbCode) {
      setError('请先选择知识空间。')
      return
    }
    const title = documentForm.title.trim()
    const content = documentForm.content.trim()
    if (!title) {
      setError('标题不能为空。')
      return
    }
    setIsSaving(true)
    try {
      if (documentDialogMode === 'edit' && editingDocument) {
        const isTextDocument = String(editingDocument.sourceType).toUpperCase() !== 'FILE'
        await updateKnowledgeDocument(
          editingDocument.docId,
          {
            title,
            description: documentForm.description.trim() || undefined,
            content: isTextDocument ? content : undefined,
          },
          currentUserId
        )
        setStatusMessage('已更新知识，采集完成后可检索。')
      } else if (documentForm.mode === 'file') {
        if (!documentForm.file) {
          setError('请选择要上传的文件。')
          return
        }
        await uploadKnowledgeDocument(selectedKbCode, documentForm.file, currentUserId)
        setStatusMessage('已提交文件采集，完成后可检索。')
      } else {
        if (!content) {
          setError('正文不能为空。')
          return
        }
        await createTextKnowledgeDocument(
          selectedKbCode,
          {
            title,
            description: documentForm.description.trim() || undefined,
            content,
          },
          currentUserId
        )
        setStatusMessage('已提交知识采集，完成后可检索。')
      }
      setDocumentDialogMode(null)
      setEditingDocument(null)
      await loadDocuments(selectedKbCode)
      if (activeTab === 'tasks') {
        await loadTasks()
      }
    } catch {
      setError('保存知识失败。')
    } finally {
      setIsSaving(false)
    }
  }

  const confirmDelete = async () => {
    if (!confirmState) return
    setIsSaving(true)
    try {
      if (confirmState.type === 'space') {
        await deleteKnowledgeSpace(confirmState.kbCode, currentUserId)
        setStatusMessage(`已删除知识空间：${confirmState.name}`)
        setConfirmState(null)
        await loadSpaces()
      } else {
        await deleteKnowledgeDocument(confirmState.docId, currentUserId)
        setStatusMessage(`已删除知识：${confirmState.title}`)
        setConfirmState(null)
        await loadDocuments(selectedKbCode)
      }
    } catch {
      setError('删除失败。')
    } finally {
      setIsSaving(false)
    }
  }

  const runSearch = async () => {
    const query = searchQuery.trim()
    if (!query) {
      setError('请输入检索问题。')
      return
    }
    if (searchKbCodes.length === 0) {
      setError('请至少选择一个知识空间。')
      return
    }
    setIsSearching(true)
    setSearchResult(null)
    try {
      const result = await searchKnowledge({
        query,
        kbCodes: searchKbCodes,
        retrievalMode: 'hybrid',
        topK: searchTopK,
        generateAnswer: true,
        currentUserId,
      })
      setSearchResult(result)
      setStatusMessage(null)
      setError(null)
    } catch {
      setError('知识检索失败。')
    } finally {
      setIsSearching(false)
    }
  }

  const toggleSearchSpace = (kbCode: string) => {
    setSearchKbCodes((current) =>
      current.includes(kbCode) ? current.filter((code) => code !== kbCode) : [...current, kbCode]
    )
  }

  const retryTask = async (taskId: string) => {
    try {
      await retryKnowledgeTask(taskId, currentUserId)
      await loadTasks()
    } catch {
      setError('重试采集任务失败。')
    }
  }

  const deleteTask = async (taskId: string) => {
    try {
      await deleteKnowledgeTask(taskId, currentUserId)
      setTasks((current) => current.filter((task) => task.taskId !== taskId))
      setStatusMessage('已删除采集任务。')
      setError(null)
    } catch {
      setError('删除采集任务失败。')
    }
  }

  return (
    <div className="panel-card knowledge-center-panel" data-testid="knowledge-center-panel">
      <div className="panel-header knowledge-center-header">
        <div>
          <div className="panel-title">知识库中心</div>
          <div className="mt-1 text-xs text-slate-500">
            当前空间：{selectedSpace?.name ?? '暂无知识空间'}
          </div>
        </div>
        <div className="knowledge-center-actions">
          <button className="prompt-primary" type="button" onClick={openCreateSpace} data-testid="knowledge-space-create">
            + 新增知识空间
          </button>
        </div>
      </div>

      <div className="panel-body knowledge-center-body">
        {error && <div className="knowledge-alert knowledge-alert-error">{error}</div>}
        {statusMessage && <div className="knowledge-alert knowledge-alert-success">{statusMessage}</div>}

        <div className="knowledge-center-layout">
          <aside className="knowledge-center-subnav" aria-label="知识库中心导航">
            <button
              className={activeTab === 'spaces' ? 'active' : ''}
              type="button"
              onClick={() => setActiveTab('spaces')}
              data-testid="knowledge-subnav-spaces"
            >
              知识空间
            </button>
            <button
              className={activeTab === 'tasks' ? 'active' : ''}
              type="button"
              onClick={() => setActiveTab('tasks')}
              data-testid="knowledge-subnav-tasks"
            >
              采集任务
            </button>
            <button
              className={activeTab === 'search' ? 'active' : ''}
              type="button"
              onClick={() => setActiveTab('search')}
              data-testid="knowledge-subnav-search"
            >
              知识检索
            </button>
          </aside>

          <main className="knowledge-center-main">
            {activeTab === 'spaces' && (
              <div className="knowledge-spaces-grid">
                <section className="knowledge-space-list-panel">
                  <div className="knowledge-section-header">
                    <div>
                      <div className="knowledge-section-title">知识空间</div>
                      <div className="knowledge-section-meta">
                        {isLoadingSpaces ? '加载中...' : `共 ${spaces.length} 个空间`}
                      </div>
                    </div>
                  </div>
                  <div className="knowledge-space-list" data-testid="knowledge-space-list">
                    {spaces.map((space) => (
                      <div
                        key={space.kbCode}
                        className={`knowledge-space-row ${space.kbCode === selectedKbCode ? 'active' : ''}`}
                        role="button"
                        tabIndex={0}
                        onClick={() => setSelectedKbCode(space.kbCode)}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            setSelectedKbCode(space.kbCode)
                          }
                        }}
                      >
                        <div className="knowledge-space-row-main">
                          <div className="knowledge-space-name">{space.name}</div>
                          <div className="knowledge-space-desc">{space.description || '未填写描述'}</div>
                        </div>
                        <div className="knowledge-space-row-meta">
                          <span>{displayStatus(space.status)}</span>
                          <span>{space.documentCount ?? 0} 份知识</span>
                        </div>
                        <div className="knowledge-row-actions">
                          <button
                            className="prompt-secondary"
                            type="button"
                            onClick={(event) => {
                              event.stopPropagation()
                              openEditSpace(space)
                            }}
                            data-testid={`knowledge-space-edit-${space.kbCode}`}
                          >
                            编辑
                          </button>
                          <button
                            className="prompt-secondary knowledge-danger-action"
                            type="button"
                            onClick={(event) => {
                              event.stopPropagation()
                              setConfirmState({ type: 'space', kbCode: space.kbCode, name: space.name })
                            }}
                            data-testid={`knowledge-space-delete-${space.kbCode}`}
                          >
                            删除
                          </button>
                        </div>
                      </div>
                    ))}
                    {spaces.length === 0 && (
                      <div className="knowledge-empty">暂无知识空间，请点击“+ 新增知识空间”。</div>
                    )}
                  </div>
                </section>

                <section className="knowledge-detail-panel">
                  <div className="knowledge-section-header">
                    <div>
                      <div className="knowledge-section-title">{selectedSpace?.name ?? '空间详情'}</div>
                      <div className="knowledge-section-meta">
                        {selectedSpace
                          ? `${displayStatus(selectedSpace.status)} / ${selectedSpaceDocumentCount} 份知识`
                          : '请选择知识空间'}
                      </div>
                    </div>
                    <button
                      className="prompt-primary"
                      type="button"
                      onClick={openCreateDocument}
                      disabled={!selectedSpace}
                      data-testid="knowledge-document-create"
                    >
                      + 新增知识
                    </button>
                  </div>
                  <div className="knowledge-document-list">
                    {isLoadingDocuments && <div className="knowledge-empty">知识加载中...</div>}
                    {!isLoadingDocuments &&
                      documents.map((document) => (
                        <article key={document.docId} className="knowledge-document-row">
                          <div className="knowledge-document-main">
                            <div className="knowledge-document-title">{getDocumentTitle(document)}</div>
                            <div className="knowledge-document-summary">
                              {document.generatedSummary || document.description || document.errorMessage || document.docId}
                            </div>
                          </div>
                          <div className="knowledge-document-meta">
                            <span>{displayStatus(document.status)}</span>
                            <span>{document.chunkCount ?? 0} 段</span>
                            <span>{formatFileSize(document.fileSize)}</span>
                          </div>
                          <div className="knowledge-row-actions">
                            <button
                              className="prompt-secondary"
                              type="button"
                              onClick={() => openEditDocument(document)}
                              data-testid={`knowledge-document-edit-${document.docId}`}
                            >
                              编辑
                            </button>
                            <button
                              className="prompt-secondary knowledge-danger-action"
                              type="button"
                              onClick={() => setConfirmState({ type: 'document', docId: document.docId, title: getDocumentTitle(document) })}
                              data-testid={`knowledge-document-delete-${document.docId}`}
                            >
                              删除
                            </button>
                          </div>
                        </article>
                      ))}
                    {!isLoadingDocuments && documents.length === 0 && (
                      <div className="knowledge-empty">当前空间暂无知识，请点击“+ 新增知识”。</div>
                    )}
                  </div>
                </section>
              </div>
            )}

            {activeTab === 'tasks' && (
              <section className="knowledge-tab-panel">
                <div className="knowledge-section-header">
                  <div>
                    <div className="knowledge-section-title">采集任务</div>
                    <div className="knowledge-section-meta">
                      {selectedSpace?.name ?? '请选择知识空间'} / {isLoadingTasks ? '同步中...' : `${tasks.length} 个任务`}
                    </div>
                  </div>
                </div>
                <div className="knowledge-task-list">
                  {tasks.map((task) => (
                    <article key={task.taskId} className="knowledge-task-row">
                      <div className="knowledge-task-main">
                        <div className="knowledge-task-title">{displayTaskStage(task.stage)}</div>
                        <div className="knowledge-task-desc">{task.docId} / {task.taskId}</div>
                        <div className="knowledge-task-progress">
                          <span style={{ width: `${Math.max(0, Math.min(100, task.progress ?? 0))}%` }} />
                        </div>
                      </div>
                      <div className="knowledge-task-meta">
                        <span>{displayStatus(task.status)}</span>
                        <span>{task.progress ?? 0}%</span>
                        {String(task.status).toUpperCase() === 'FAILED' && (
                          <button className="prompt-secondary" type="button" onClick={() => void retryTask(task.taskId)}>
                            重试
                          </button>
                        )}
                        <button
                          className="prompt-secondary knowledge-danger-action"
                          type="button"
                          onClick={() => void deleteTask(task.taskId)}
                          data-testid={`knowledge-task-delete-${task.taskId}`}
                        >
                          删除
                        </button>
                      </div>
                    </article>
                  ))}
                  {tasks.length === 0 && (
                    <div className="knowledge-empty">暂无采集任务。上传或录入知识后会生成处理任务。</div>
                  )}
                </div>
              </section>
            )}

            {activeTab === 'search' && (
              <section className="knowledge-tab-panel knowledge-search-panel">
                <div className="knowledge-section-header">
                  <div>
                    <div className="knowledge-section-title">知识检索</div>
                    <div className="knowledge-section-meta">混合召回 / 返回 {searchTopK} 条</div>
                  </div>
                </div>
                <div className="knowledge-search-form">
                  <textarea
                    className="form-textarea knowledge-search-input"
                    value={searchQuery}
                    onChange={(event) => setSearchQuery(event.target.value)}
                    placeholder="输入要查询的知识问题"
                  />
                  <div className="knowledge-search-controls">
                    <label>
                      <span>返回数量</span>
                      <select
                        className="form-input"
                        value={searchTopK}
                        onChange={(event) => setSearchTopK(Number(event.target.value))}
                      >
                        {[3, 5, 8, 10].map((value) => (
                          <option key={value} value={value}>{value}</option>
                        ))}
                      </select>
                    </label>
                    <button className="prompt-primary" type="button" onClick={() => void runSearch()} disabled={isSearching}>
                      {isSearching ? '检索中...' : '开始检索'}
                    </button>
                  </div>
                </div>
                <div className="knowledge-search-space-picker">
                  {spaces.map((space) => (
                    <label key={space.kbCode} className="knowledge-search-space-option">
                      <input
                        type="checkbox"
                        checked={searchKbCodes.includes(space.kbCode)}
                        onChange={() => toggleSearchSpace(space.kbCode)}
                      />
                      <span>{space.name}</span>
                    </label>
                  ))}
                </div>
                <div className="knowledge-search-results">
                  {searchResult?.answer && (
                    <section className="knowledge-answer-panel">
                      <div className="knowledge-section-title">总结答案</div>
                      <p>{searchResult.answer}</p>
                      <div className="knowledge-section-meta">最高命中分：{formatScore(searchResult.bestScore)}</div>
                    </section>
                  )}
                  <div className="knowledge-hit-list">
                    {searchResult?.documents.map((hit) => (
                      <article key={`${hit.chunkId}-${hit.docId}`} className="knowledge-hit-row">
                        <div className="knowledge-hit-title">{hit.title || hit.docId}</div>
                        <p>{hit.content || '暂无片段内容'}</p>
                        <div className="knowledge-hit-meta">
                          <span>{hit.docId}</span>
                          <span>{formatScore(hit.score)}</span>
                        </div>
                      </article>
                    ))}
                    {searchResult && searchResult.documents.length === 0 && (
                      <div className="knowledge-empty">未命中相关知识。</div>
                    )}
                    {!searchResult && <div className="knowledge-empty">输入问题并选择知识空间后开始检索。</div>}
                  </div>
                </div>
              </section>
            )}
          </main>
        </div>
      </div>

      {spaceDialogMode && (
        <ModalShell
          title={spaceDialogMode === 'edit' ? '编辑知识空间' : '新增知识空间'}
          onClose={() => setSpaceDialogMode(null)}
          actions={(
            <>
              <button className="prompt-secondary" type="button" onClick={() => setSpaceDialogMode(null)}>
                取消
              </button>
              <button className="prompt-primary" type="button" onClick={() => void submitSpace()} disabled={isSaving}>
                保存
              </button>
            </>
          )}
        >
          <div className="knowledge-form-grid">
            <Field label="空间名称" inputId="knowledge-space-name">
              <input
                id="knowledge-space-name"
                className="form-input"
                value={spaceForm.name}
                onChange={(event) => setSpaceForm((current) => ({ ...current, name: event.target.value }))}
              />
            </Field>
            <Field label="描述" inputId="knowledge-space-description" className="md:col-span-2">
              <textarea
                id="knowledge-space-description"
                className="form-textarea"
                value={spaceForm.description}
                onChange={(event) => setSpaceForm((current) => ({ ...current, description: event.target.value }))}
              />
            </Field>
          </div>
        </ModalShell>
      )}

      {documentDialogMode && selectedSpace && (
        <ModalShell
          title={`${documentDialogMode === 'edit' ? '编辑知识' : '新增知识'} / ${selectedSpace.name}`}
          wide
          onClose={() => {
            setDocumentDialogMode(null)
            setEditingDocument(null)
          }}
          actions={(
            <>
              <button
                className="prompt-secondary"
                type="button"
                onClick={() => {
                  setDocumentDialogMode(null)
                  setEditingDocument(null)
                }}
              >
                取消
              </button>
              <button className="prompt-primary" type="button" onClick={() => void submitDocument()} disabled={isSaving}>
                提交采集
              </button>
            </>
          )}
        >
          <div className="knowledge-document-form">
            {documentDialogMode === 'create' && (
              <div className="knowledge-segmented">
                <button
                  className={documentForm.mode === 'text' ? 'active' : ''}
                  type="button"
                  onClick={() => setDocumentForm((current) => ({ ...current, mode: 'text' }))}
                >
                  文本
                </button>
                <button
                  className={documentForm.mode === 'file' ? 'active' : ''}
                  type="button"
                  onClick={() => setDocumentForm((current) => ({ ...current, mode: 'file' }))}
                >
                  文件
                </button>
              </div>
            )}

            {documentForm.mode === 'file' && documentDialogMode === 'create' ? (
              <div className="knowledge-file-upload">
                <input
                  id="knowledge-doc-file"
                  type="file"
                  accept=".txt,.md,.pdf,.doc,.docx,.csv,.tsv,.xls,.xlsx,.json,.html,.htm"
                  onChange={(event) =>
                    setDocumentForm((current) => ({
                      ...current,
                      file: event.target.files?.[0] ?? null,
                      title: current.title || event.target.files?.[0]?.name.replace(/\.[^.]+$/, '') || '',
                    }))
                  }
                />
                <label htmlFor="knowledge-doc-file">
                  <span>{documentForm.file?.name ?? '选择文本或常见文档'}</span>
                  <strong>{documentForm.file ? formatFileSize(documentForm.file.size) : '支持常见文本、文档和表格文件'}</strong>
                </label>
              </div>
            ) : (
              <div className="knowledge-form-grid">
                <Field label="标题" inputId="knowledge-doc-title">
                  <input
                    id="knowledge-doc-title"
                    className="form-input"
                    value={documentForm.title}
                    onChange={(event) => setDocumentForm((current) => ({ ...current, title: event.target.value }))}
                  />
                </Field>
                <Field label="描述" inputId="knowledge-doc-description">
                  <input
                    id="knowledge-doc-description"
                    className="form-input"
                    value={documentForm.description}
                    onChange={(event) => setDocumentForm((current) => ({ ...current, description: event.target.value }))}
                  />
                </Field>
                {String(editingDocument?.sourceType).toUpperCase() !== 'FILE' && (
                  <Field label="正文" inputId="knowledge-doc-content" className="md:col-span-2">
                    <textarea
                      id="knowledge-doc-content"
                      className="form-textarea knowledge-content-textarea"
                      value={documentForm.content}
                      onChange={(event) => setDocumentForm((current) => ({ ...current, content: event.target.value }))}
                    />
                  </Field>
                )}
              </div>
            )}
          </div>
        </ModalShell>
      )}

      {confirmState && (
        <ModalShell
          title={confirmState.type === 'space' ? '删除知识空间' : '删除知识'}
          onClose={() => setConfirmState(null)}
          actions={(
            <>
              <button className="prompt-secondary" type="button" onClick={() => setConfirmState(null)}>
                取消
              </button>
              <button className="prompt-primary" type="button" onClick={() => void confirmDelete()} disabled={isSaving}>
                确认删除
              </button>
            </>
          )}
        >
          <div className="knowledge-empty">
            {confirmState.type === 'space'
              ? `删除后“${confirmState.name}”及其中知识会从页面隐藏。`
              : `删除后“${confirmState.title}”会从页面隐藏。`}
          </div>
        </ModalShell>
      )}
    </div>
  )
}

function getDocumentTitle(document: KnowledgeDocument) {
  return document.title || document.generatedTitle || document.filename || document.docId
}

function displayStatus(status?: string | null) {
  switch (String(status ?? '').toUpperCase()) {
    case 'ACTIVE':
      return '启用'
    case 'READY':
      return '已就绪'
    case 'PENDING':
      return '待处理'
    case 'QUEUED':
      return '排队中'
    case 'RUNNING':
    case 'PROCESSING':
      return '处理中'
    case 'SUCCEEDED':
      return '成功'
    case 'FAILED':
      return '失败'
    case 'DISABLED':
    case 'INACTIVE':
      return '停用'
    case 'DELETED':
      return '已删除'
    default:
      return status ? '未知状态' : '--'
  }
}

function displayTaskStage(stage?: string | null) {
  switch (String(stage ?? '').toUpperCase()) {
    case 'RAW_SAVED':
      return '原文已保存'
    case 'TEXT_EXTRACTED':
      return '文本已提取'
    case 'CLEANED':
      return '内容已清洗'
    case 'CHUNKED':
      return '内容已切分'
    case 'EMBEDDED':
      return '索引已生成'
    case 'INDEXED':
      return '已入库'
    default:
      return '采集处理'
  }
}

function formatFileSize(value?: number | null) {
  if (!value || value <= 0) return '--'
  if (value < 1024) return `${value} 字节`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} 千字节`
  return `${(value / 1024 / 1024).toFixed(1)} 兆字节`
}

function formatScore(value?: number | null) {
  if (value == null || Number.isNaN(value)) return '--'
  return value.toFixed(2)
}

const ModalShell: React.FC<{
  title: string
  wide?: boolean
  onClose: () => void
  actions: React.ReactNode
  children: React.ReactNode
}> = ({ title, wide = false, onClose, actions, children }) => (
  <div className="form-overlay">
    <div className={`knowledge-modal ${wide ? 'knowledge-modal-wide' : ''}`} role="dialog" aria-modal="true">
      <div className="knowledge-modal-header">
        <div className="panel-title">{title}</div>
        <button type="button" className="knowledge-modal-close" onClick={onClose}>
          关闭
        </button>
      </div>
      <div className="knowledge-modal-body">{children}</div>
      <div className="knowledge-modal-actions">{actions}</div>
    </div>
  </div>
)

const Field: React.FC<{ label: string; inputId: string; children: React.ReactNode; className?: string }> = ({
  label,
  inputId,
  children,
  className,
}) => (
  <div className={className}>
    <label htmlFor={inputId} className="mb-2 block text-xs font-semibold text-slate-500">
      {label}
    </label>
    {children}
  </div>
)

export default KnowledgeCenterPanel
