import React, { useCallback, useEffect, useMemo, useState } from 'react'
import {
  createKnowledgeSpace,
  createTextKnowledgeDocument,
  getKnowledgeDocumentTasks,
  getKnowledgeDocuments,
  getKnowledgeSpaces,
  retryKnowledgeTask,
  searchKnowledge,
  uploadKnowledgeDocument,
} from '../services/api'
import type { KnowledgeDocument, KnowledgeSearchResult, KnowledgeSpace, KnowledgeTask } from '../types'

interface KnowledgeCenterPanelProps {
  currentUserId: string
}

type KnowledgeTab = 'spaces' | 'tasks' | 'search'

type SpaceFormState = {
  kbCode: string
  name: string
  description: string
  embeddingModel: string
}

type DocumentFormState = {
  mode: 'text' | 'file'
  title: string
  description: string
  content: string
  file: File | null
}

const DEFAULT_EMBEDDING_MODEL = 'embedding-qwen3-8b'
const DEFAULT_TOP_K = 5

const defaultSpaceForm = (): SpaceFormState => ({
  kbCode: '',
  name: '',
  description: '',
  embeddingModel: DEFAULT_EMBEDDING_MODEL,
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
  const [spaceDialogOpen, setSpaceDialogOpen] = useState(false)
  const [documentDialogOpen, setDocumentDialogOpen] = useState(false)
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
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '加载知识空间失败。')
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
    } catch (loadError) {
      setDocuments([])
      setError(loadError instanceof Error ? loadError.message : '加载知识文档失败。')
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
    } catch (loadError) {
      setTasks([])
      setError(loadError instanceof Error ? loadError.message : '加载采集任务失败。')
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
    setSpaceDialogOpen(true)
    setStatusMessage(null)
    setError(null)
  }

  const openCreateDocument = () => {
    setDocumentForm(defaultDocumentForm())
    setDocumentDialogOpen(true)
    setStatusMessage(null)
    setError(null)
  }

  const submitSpace = async () => {
    const payload = {
      kbCode: spaceForm.kbCode.trim(),
      name: spaceForm.name.trim(),
      description: spaceForm.description.trim() || undefined,
      embeddingModel: spaceForm.embeddingModel.trim() || DEFAULT_EMBEDDING_MODEL,
    }
    if (!payload.kbCode || !payload.name) {
      setError('知识空间编码和名称不能为空。')
      return
    }
    setIsSaving(true)
    try {
      const saved = await createKnowledgeSpace(payload, currentUserId)
      setStatusMessage(`已新增知识空间：${saved.name}`)
      setSpaceDialogOpen(false)
      await loadSpaces()
      setSelectedKbCode(saved.kbCode)
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : '新增知识空间失败。')
    } finally {
      setIsSaving(false)
    }
  }

  const submitDocument = async () => {
    if (!selectedKbCode) {
      setError('请先选择知识空间。')
      return
    }
    setIsSaving(true)
    try {
      if (documentForm.mode === 'file') {
        if (!documentForm.file) {
          setError('请选择要上传的文档。')
          return
        }
        await uploadKnowledgeDocument(selectedKbCode, documentForm.file, currentUserId)
      } else {
        const title = documentForm.title.trim()
        const content = documentForm.content.trim()
        if (!title || !content) {
          setError('标题和正文不能为空。')
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
      }
      setStatusMessage('已提交知识采集，完成后可立即检索。')
      setDocumentDialogOpen(false)
      await loadDocuments(selectedKbCode)
      if (activeTab === 'tasks') {
        await loadTasks()
      }
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : '新增知识失败。')
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
      })
      setSearchResult(result)
      setStatusMessage(null)
      setError(null)
    } catch (searchError) {
      setError(searchError instanceof Error ? searchError.message : '知识检索失败。')
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
    } catch (retryError) {
      setError(retryError instanceof Error ? retryError.message : '重试采集任务失败。')
    }
  }

  return (
    <div className="panel-card knowledge-center-panel" data-testid="knowledge-center-panel">
      <div className="panel-header knowledge-center-header">
        <div>
          <div className="panel-title">知识库中心</div>
          <div className="mt-1 text-xs text-slate-500">
            当前空间：{selectedSpace?.name ?? '暂无知识空间'} / 向量模型：{selectedSpace?.embeddingModel ?? DEFAULT_EMBEDDING_MODEL}
          </div>
        </div>
        <div className="knowledge-center-actions">
          <button className="prompt-secondary" type="button" onClick={() => void loadSpaces()}>
            刷新
          </button>
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
                      <button
                        key={space.kbCode}
                        className={`knowledge-space-row ${space.kbCode === selectedKbCode ? 'active' : ''}`}
                        type="button"
                        onClick={() => setSelectedKbCode(space.kbCode)}
                      >
                        <div className="knowledge-space-row-main">
                          <div className="knowledge-space-name">{space.name}</div>
                          <div className="knowledge-space-desc">{space.description || '未填写描述'}</div>
                        </div>
                        <div className="knowledge-space-row-meta">
                          <span>{space.kbCode}</span>
                          <span>{space.embeddingModel || DEFAULT_EMBEDDING_MODEL}</span>
                          <span>{displayStatus(space.status)}</span>
                        </div>
                      </button>
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
                          ? `${selectedSpace.kbCode} / ${displayStatus(selectedSpace.status)} / ${selectedSpaceDocumentCount} 份知识`
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
                    {isLoadingDocuments && <div className="knowledge-empty">文档加载中...</div>}
                    {!isLoadingDocuments &&
                      documents.map((document) => (
                        <article key={document.docId} className="knowledge-document-row">
                          <div className="knowledge-document-main">
                            <div className="knowledge-document-title">{getDocumentTitle(document)}</div>
                            <div className="knowledge-document-summary">
                              {document.generatedSummary || document.errorMessage || document.docId}
                            </div>
                          </div>
                          <div className="knowledge-document-meta">
                            <span>{displayStatus(document.status)}</span>
                            <span>{document.chunkCount ?? 0} 段</span>
                            <span>{formatFileSize(document.fileSize)}</span>
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
                  <button className="prompt-secondary" type="button" onClick={() => void loadTasks()}>
                    同步任务
                  </button>
                </div>
                <div className="knowledge-task-list">
                  {tasks.map((task) => (
                    <article key={task.taskId} className="knowledge-task-row">
                      <div className="knowledge-task-main">
                        <div className="knowledge-task-title">{task.stage || '采集处理'}</div>
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
                    <div className="knowledge-section-meta">混合召回 / TopK {searchTopK}</div>
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
                      <span>TopK</span>
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
                          <span>{hit.kbCode}</span>
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

      {spaceDialogOpen && (
        <ModalShell title="新增知识空间" onClose={() => setSpaceDialogOpen(false)} actions={(
          <>
            <button className="prompt-secondary" type="button" onClick={() => setSpaceDialogOpen(false)}>
              取消
            </button>
            <button className="prompt-primary" type="button" onClick={() => void submitSpace()} disabled={isSaving}>
              保存
            </button>
          </>
        )}>
          <div className="knowledge-form-grid">
            <Field label="空间编码" inputId="knowledge-space-code">
              <input
                id="knowledge-space-code"
                className="form-input"
                value={spaceForm.kbCode}
                onChange={(event) => setSpaceForm((current) => ({ ...current, kbCode: event.target.value }))}
                placeholder="kb_product"
              />
            </Field>
            <Field label="空间名称" inputId="knowledge-space-name">
              <input
                id="knowledge-space-name"
                className="form-input"
                value={spaceForm.name}
                onChange={(event) => setSpaceForm((current) => ({ ...current, name: event.target.value }))}
                placeholder="产品知识"
              />
            </Field>
            <Field label="向量模型" inputId="knowledge-space-model">
              <input
                id="knowledge-space-model"
                className="form-input"
                value={spaceForm.embeddingModel}
                onChange={(event) => setSpaceForm((current) => ({ ...current, embeddingModel: event.target.value }))}
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

      {documentDialogOpen && selectedSpace && (
        <ModalShell title={`新增知识 / ${selectedSpace.name}`} wide onClose={() => setDocumentDialogOpen(false)} actions={(
          <>
            <button className="prompt-secondary" type="button" onClick={() => setDocumentDialogOpen(false)}>
              取消
            </button>
            <button className="prompt-primary" type="button" onClick={() => void submitDocument()} disabled={isSaving}>
              提交采集
            </button>
          </>
        )}>
          <div className="knowledge-document-form">
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
                文档
              </button>
            </div>

            {documentForm.mode === 'text' ? (
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
                <Field label="正文" inputId="knowledge-doc-content" className="md:col-span-2">
                  <textarea
                    id="knowledge-doc-content"
                    className="form-textarea knowledge-content-textarea"
                    value={documentForm.content}
                    onChange={(event) => setDocumentForm((current) => ({ ...current, content: event.target.value }))}
                  />
                </Field>
              </div>
            ) : (
              <div className="knowledge-file-upload">
                <input
                  id="knowledge-doc-file"
                  type="file"
                  accept=".txt,.md,.pdf,.doc,.docx,.csv,.tsv,.xls,.xlsx,.json,.html,.htm"
                  onChange={(event) =>
                    setDocumentForm((current) => ({
                      ...current,
                      file: event.target.files?.[0] ?? null,
                    }))
                  }
                />
                <label htmlFor="knowledge-doc-file">
                  <span>{documentForm.file?.name ?? '选择文本或常见文档'}</span>
                  <strong>{documentForm.file ? formatFileSize(documentForm.file.size) : 'TXT / PDF / Word / Excel / Markdown'}</strong>
                </label>
              </div>
            )}
          </div>
        </ModalShell>
      )}
    </div>
  )
}

function getDocumentTitle(document: KnowledgeDocument) {
  return document.generatedTitle || document.title || document.filename || document.docId
}

function displayStatus(status?: string | null) {
  switch (String(status ?? '').toUpperCase()) {
    case 'ACTIVE':
      return '启用'
    case 'READY':
      return '已就绪'
    case 'PENDING':
      return '待处理'
    case 'RUNNING':
    case 'PROCESSING':
      return '处理中'
    case 'FAILED':
      return '失败'
    case 'DISABLED':
      return '停用'
    default:
      return status || '--'
  }
}

function formatFileSize(value?: number | null) {
  if (!value || value <= 0) return '--'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
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
    <div className={`knowledge-modal ${wide ? 'knowledge-modal-wide' : ''}`}>
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
    <label htmlFor={inputId} className="mb-2 block text-xs font-semibold uppercase text-slate-400">
      {label}
    </label>
    {children}
  </div>
)

export default KnowledgeCenterPanel
