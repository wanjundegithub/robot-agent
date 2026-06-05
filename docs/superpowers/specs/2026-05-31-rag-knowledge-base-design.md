# RAG 知识库建设设计文档

版本：`v1.0`  
日期：`2026-05-31`  
适用项目：`robot-agent`

## 1. 建设目标

建设一个完整的 RAG 知识库能力，允许用户以最少输入成本维护知识内容，并让智能体、工作流、问答节点能够基于知识库进行可靠回答。

用户侧只需要提供：

- `知识描述`：必填。
- `知识内容`：可通过文件上传、图片上传、文本粘贴等方式提供。

系统自动完成：

- 文件类型识别。
- 文本抽取。
- 图片 OCR。
- 内容清洗。
- 自动标题、摘要、关键词生成。
- 语义分块。
- embedding 向量化。
- pgvector 入库。
- 混合检索。
- 引用溯源。
- 知识更新、删除、重处理后的索引同步。

## 2. 总体原则

- **用户输入最小化**：除“知识描述”外，不强制用户填写标题、标签、分类、版本、语言等信息。
- **格式兼容优先**：支持文本、PDF、Word、图片等常见知识载体。
- **异步处理优先**：上传接口只负责接收和创建任务，解析、OCR、向量化全部异步执行。
- **可追溯回答**：RAG 回答必须返回来源片段，避免无依据回答。
- **索引一致性**：知识更新、删除、重处理后，旧索引不得继续参与检索。
- **可扩展架构**：OCR、embedding、rerank、对象存储、任务队列均采用可替换设计。

## 3. 现有项目基础

当前项目已有可复用基础：

- Python AI 中已有知识检索存储抽象：`python-ai/src/core/knowledge_store.py:26`
- Python AI 中已有 pgvector 知识存储雏形：`python-ai/src/core/knowledge_store.py:91`
- Python AI 中已有知识节点：`python-ai/src/nodes/knowledge.py:9`
- 知识节点已支持 `retrieval_mode`：`python-ai/src/nodes/knowledge.py:15`
- 知识节点已写入 `retrieved_docs` 和 `knowledge_answer`：`python-ai/src/nodes/knowledge.py:76`
- LLM 节点已有知识问答分支：`python-ai/src/nodes/llm.py:56`
- Docker Compose 已包含 pgvector 服务：`docker-compose.yml:99`

本次设计应优先扩展现有能力，而不是重建一套独立 RAG 服务。

## 4. 范围说明

### 4.1 本阶段包含

- 知识库创建、查询、删除。
- 知识条目上传、文本录入、状态查询、重新处理。
- 支持 `txt`、文本粘贴、`pdf`、`doc/docx`、图片。
- 图片 OCR。
- PDF 文本抽取，必要时支持 OCR 兜底。
- Word 文档解析。
- 内容清洗、自动摘要、关键词生成。
- 分块、向量化、索引入库。
- 关键词 + 向量混合检索。
- RAG 引用溯源。
- 知识更新、删除、重处理后的索引同步。
- 基础权限与知识库范围过滤。
- 处理状态可视化。
- 失败重试。

### 4.2 本阶段不强制包含

- 多模态直接问答。
- 大规模知识图谱。
- 复杂人工审核流。
- 企业级全文搜索集群。
- 复杂权限矩阵。
- 知识自动爬取。
- 多版本人工发布审批。

## 5. 总体架构

### 5.1 架构分层

```text
前端 React
  |
  | 上传知识、查看状态、配置知识节点、查看引用来源
  v
Java Backend
  |
  | 知识库元数据、文件接收、任务编排、权限控制、状态管理
  v
Python AI
  |
  | 文档解析、OCR、清洗、分块、embedding、检索、RAG 组装
  v
PostgreSQL + pgvector
  |
  | chunk 内容、向量、元数据、索引状态
  v
文件存储
  |
  | 原始文件、图片、PDF、Word
```

### 5.2 职责划分

**前端**

- 提供知识库管理页面。
- 提供知识上传入口。
- 展示处理状态。
- 展示抽取文本、分块结果、引用来源。
- 在工作流知识节点中选择知识库。

**Java 后端**

- 接收上传。
- 校验文件类型和大小。
- 保存原始文件。
- 维护知识库、知识条目、处理任务状态。
- 触发 Python AI 处理任务。
- 提供前端查询接口。
- 做基础权限过滤。

**Python AI**

- 文件解析。
- 图片 OCR。
- 文本清洗。
- 自动标题、摘要、关键词生成。
- 文本分块。
- embedding 生成。
- pgvector 写入。
- 混合检索。
- RAG 上下文组装。

**pgvector**

- 存储知识 chunk。
- 存储 embedding。
- 支持向量相似度检索。
- 配合元数据过滤知识库、租户、状态、版本。

## 6. 用户流程设计

### 6.1 创建知识库

1. 用户进入知识库页面。
2. 点击“新建知识库”。
3. 输入知识库名称和描述。
4. 系统创建知识库。
5. 用户进入知识库详情页上传知识。

### 6.2 上传知识

用户只需要填写：

- `知识描述`：必填。
- `文件` 或 `文本内容`：至少提供一种。

支持方式：

- 上传 `.txt`。
- 上传 `.pdf`。
- 上传 `.doc/.docx`。
- 上传图片。
- 直接粘贴文本片段。

上传后系统自动：

1. 保存知识条目。
2. 保存原始文件。
3. 创建异步处理任务。
4. 返回知识条目 ID 和任务 ID。
5. 前端开始轮询状态。

### 6.3 知识处理

系统自动执行：

1. 识别文件类型。
2. 抽取文本。
3. 图片执行 OCR。
4. 清洗文本。
5. 自动生成标题、摘要、关键词。
6. 文本分块。
7. 生成向量。
8. 写入 pgvector。
9. 标记知识条目为可用。

### 6.4 知识问答

1. 用户在聊天或工作流中发起问题。
2. 工作流知识节点指定知识库范围。
3. Python AI 执行混合检索。
4. 返回相关知识片段。
5. LLM 基于片段生成回答。
6. 前端展示答案和引用来源。

### 6.5 更新知识

用户可更新：

- 知识描述。
- 原始文件。
- 文本内容。

处理规则：

- 只修改描述：更新元数据，必要时重算摘要和关键词。
- 修改文件或文本内容：旧索引失效，重新解析、分块、向量化。
- 重处理时保留同一个知识条目 ID，生成新的索引版本。

### 6.6 删除知识

删除知识时：

1. Java 后端将知识条目标记为 `DELETED`。
2. Python AI 或后端同步将 chunk 标记为不可检索。
3. 检索默认只查询 `ACTIVE` chunk。
4. 后台任务可定期物理清理原始文件和向量数据。

## 7. 前端页面设计

### 7.1 知识库列表页

展示字段：

- 知识库名称。
- 描述。
- 知识条目数量。
- 可用知识数量。
- 处理中数量。
- 最近更新时间。
- 状态。

操作：

- 新建知识库。
- 编辑知识库。
- 删除知识库。
- 进入详情。

### 7.2 知识库详情页

展示：

- 知识库基础信息。
- 知识条目列表。
- 上传知识按钮。
- 检索测试入口。

知识条目列表字段：

- 知识描述。
- 系统生成标题。
- 文件名。
- 文件类型。
- 处理状态。
- chunk 数量。
- 更新时间。
- 失败原因。

操作：

- 查看详情。
- 查看抽取文本。
- 查看分块。
- 重新处理。
- 删除。

### 7.3 上传知识弹窗

字段：

- `知识描述`：必填。
- `文件上传`：可选。
- `文本内容`：可选。

校验规则：

- 知识描述不能为空。
- 文件和文本内容不能同时为空。
- 如果同时提供文件和文本内容，系统将文本内容作为补充说明或附加知识。
- 文件大小超过限制时提示用户。

### 7.4 知识详情页

展示：

- 知识描述。
- 系统生成标题。
- 自动摘要。
- 自动关键词。
- 原始文件信息。
- 抽取文本预览。
- 分块结果。
- 处理日志。
- 错误详情。

### 7.5 RAG 引用展示

聊天或工作流执行结果中展示：

- 答案正文。
- 参考来源列表。
- 来源文件名。
- 来源页码或段落。
- 相关片段。
- 相似度分数。
- 点击展开原文。

## 8. Java 后端设计

### 8.1 核心模块

建议新增模块：

- `KnowledgeBaseController`
- `KnowledgeItemController`
- `KnowledgeProcessingController`
- `KnowledgeBaseService`
- `KnowledgeItemService`
- `KnowledgeProcessingService`
- `KnowledgeFileStorageService`
- `PythonKnowledgeClient`

### 8.2 推荐接口

**知识库接口**

```http
POST /api/knowledge-bases
GET /api/knowledge-bases
GET /api/knowledge-bases/{kbId}
PUT /api/knowledge-bases/{kbId}
DELETE /api/knowledge-bases/{kbId}
```

**知识条目接口**

```http
POST /api/knowledge-bases/{kbId}/items
GET /api/knowledge-bases/{kbId}/items
GET /api/knowledge-items/{itemId}
PUT /api/knowledge-items/{itemId}
DELETE /api/knowledge-items/{itemId}
POST /api/knowledge-items/{itemId}/reprocess
```

**处理任务接口**

```http
GET /api/knowledge-processing-jobs/{jobId}
GET /api/knowledge-items/{itemId}/processing-logs
```

**内容查看接口**

```http
GET /api/knowledge-items/{itemId}/extracted-text
GET /api/knowledge-items/{itemId}/chunks
```

**检索测试接口**

```http
POST /api/knowledge-bases/{kbId}/search
```

### 8.3 上传接口请求

```http
POST /api/knowledge-bases/{kbId}/items
Content-Type: multipart/form-data
```

字段：

```text
description: string, required
file: binary, optional
textContent: string, optional
```

响应：

```json
{
  "itemId": "ki_001",
  "jobId": "job_001",
  "status": "PENDING"
}
```

### 8.4 后端处理逻辑

上传成功后：

1. 校验知识库是否存在。
2. 校验用户是否有权限。
3. 校验 `description`。
4. 校验文件类型和大小。
5. 保存原始文件。
6. 计算 `content_hash`。
7. 创建 `knowledge_item`。
8. 创建 `knowledge_processing_job`。
9. 调用 Python AI 创建处理任务。
10. 返回任务信息。

## 9. Python AI 设计

### 9.1 核心模块

建议新增或扩展：

- `KnowledgeIngestionService`
- `KnowledgeParserRegistry`
- `TextExtractor`
- `PdfExtractor`
- `WordExtractor`
- `ImageOcrExtractor`
- `KnowledgeTextCleaner`
- `KnowledgeChunker`
- `EmbeddingService`
- `KnowledgeIndexService`
- `HybridKnowledgeRetriever`
- `CitationBuilder`

### 9.2 Python API

建议提供：

```http
POST /api/knowledge/ingest
POST /api/knowledge/reprocess
POST /api/knowledge/delete-index
POST /api/knowledge/search
POST /api/knowledge/generate-answer
```

### 9.3 入库请求

```json
{
  "itemId": "ki_001",
  "kbId": "kb_001",
  "description": "售后退货规则",
  "sourceType": "FILE",
  "filePath": "/data/uploads/ki_001.pdf",
  "fileName": "售后规则.pdf",
  "fileType": "pdf",
  "textContent": null,
  "indexVersion": 1
}
```

### 9.4 入库响应

```json
{
  "itemId": "ki_001",
  "status": "READY",
  "title": "售后退货规则",
  "summary": "本文档说明售后退货条件、流程和限制。",
  "keywords": ["售后", "退货", "退款", "质保"],
  "chunkCount": 12,
  "warnings": []
}
```

## 10. 文件解析设计

### 10.1 支持格式

| 类型 | 格式 | 处理方式 |
|---|---|---|
| 文本 | `txt` | 直接读取 UTF-8 文本 |
| PDF | `pdf` | 优先文本层抽取，必要时 OCR |
| Word | `doc/docx` | 文档解析 |
| 图片 | `png/jpg/jpeg/webp/bmp` | OCR 识别 |
| 手动输入 | 文本框 | 直接进入清洗流程 |

### 10.2 解析输出统一结构

```json
{
  "text": "抽取后的全文",
  "pages": [
    {
      "pageNumber": 1,
      "text": "第一页文本"
    }
  ],
  "metadata": {
    "sourceType": "PDF",
    "fileName": "售后规则.pdf",
    "ocrUsed": false
  },
  "warnings": []
}
```

### 10.3 OCR 规则

图片或扫描 PDF 使用 OCR：

- OCR 结果为空时，任务失败并提示用户。
- OCR 置信度低时，任务可成功但附带 warning。
- OCR 文本保留图片来源信息，便于引用溯源。

## 11. 文本清洗与自动增强

### 11.1 清洗规则

- 去除多余空行。
- 合并异常换行。
- 去除重复页眉页脚。
- 保留标题层级。
- 保留列表结构。
- 保留页码、段落、图片来源等元数据。

### 11.2 自动生成字段

系统自动生成：

- `title`
- `summary`
- `keywords`
- `language`
- `content_type`

用户不需要填写这些字段。

### 11.3 知识描述的作用

用户填写的 `知识描述` 参与：

- 自动标题生成。
- chunk 元数据。
- 检索加权。
- RAG 来源展示。
- 知识条目列表展示。

## 12. 分块设计

### 12.1 分块原则

- 优先按标题、段落、列表进行语义分块。
- 单个 chunk 不宜过短或过长。
- 保留上下文连续性。
- 分块之间可设置 overlap。

### 12.2 默认参数

```text
chunk_size: 500~1000 中文字符
chunk_overlap: 80~150 中文字符
max_chunk_size: 1500 中文字符
min_chunk_size: 100 中文字符
```

### 12.3 chunk 元数据

每个 chunk 保存：

```json
{
  "chunkIndex": 1,
  "pageNumber": 3,
  "sectionTitle": "退货条件",
  "sourceFileName": "售后规则.pdf",
  "sourceType": "PDF",
  "knowledgeDescription": "售后退货规则",
  "indexVersion": 1
}
```

## 13. 向量化设计

### 13.1 embedding 服务

采用可配置 embedding 服务：

- 支持本地 embedding。
- 支持第三方模型服务。
- 支持后续替换模型。

配置项：

```text
embedding_provider
embedding_model
embedding_dimension
embedding_batch_size
embedding_timeout
```

### 13.2 向量维度

现有 pgvector 表中 `VECTOR(8)` 仅适合测试。正式建设时应按实际 embedding 模型调整，例如：

- `VECTOR(768)`
- `VECTOR(1024)`
- `VECTOR(1536)`

### 13.3 写入策略

- 按 batch 写入。
- 写入失败支持重试。
- 同一 `item_id + index_version` 不重复写入。
- 重处理前先失效旧版本索引。

## 14. 数据库设计

### 14.1 `knowledge_base`

```sql
CREATE TABLE knowledge_base (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

### 14.2 `knowledge_item`

```sql
CREATE TABLE knowledge_item (
    id VARCHAR(64) PRIMARY KEY,
    kb_id VARCHAR(64) NOT NULL,
    description TEXT NOT NULL,
    generated_title VARCHAR(255),
    generated_summary TEXT,
    generated_keywords TEXT,
    source_type VARCHAR(32) NOT NULL,
    file_name VARCHAR(512),
    file_type VARCHAR(64),
    file_path TEXT,
    content_hash VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    index_version INT NOT NULL DEFAULT 1,
    error_message TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

### 14.3 `knowledge_processing_job`

```sql
CREATE TABLE knowledge_processing_job (
    id VARCHAR(64) PRIMARY KEY,
    item_id VARCHAR(64) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

### 14.4 `knowledge_chunk`

建议由 pgvector 所在 PostgreSQL 管理：

```sql
CREATE TABLE knowledge_chunk (
    chunk_id TEXT PRIMARY KEY,
    item_id TEXT NOT NULL,
    kb_id TEXT NOT NULL,
    index_version INT NOT NULL,
    chunk_index INT NOT NULL,
    title TEXT,
    content TEXT NOT NULL,
    summary TEXT,
    embedding VECTOR(1536) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 14.5 索引建议

```sql
CREATE INDEX idx_knowledge_chunk_kb_status
ON knowledge_chunk (kb_id, status);

CREATE INDEX idx_knowledge_chunk_item_version
ON knowledge_chunk (item_id, index_version);

CREATE INDEX idx_knowledge_chunk_metadata
ON knowledge_chunk USING GIN (metadata);

CREATE INDEX idx_knowledge_chunk_embedding
ON knowledge_chunk USING ivfflat (embedding vector_cosine_ops);
```

## 15. 任务状态机

### 15.1 状态定义

```text
PENDING     待处理
EXTRACTING  正在抽取文本或 OCR
CLEANING    正在清洗文本
CHUNKING    正在分块
EMBEDDING   正在生成向量
INDEXING    正在写入索引
READY       可用
FAILED      失败
DELETED     已删除
```

### 15.2 状态流转

```text
PENDING
  -> EXTRACTING
  -> CLEANING
  -> CHUNKING
  -> EMBEDDING
  -> INDEXING
  -> READY
```

失败时：

```text
任意阶段 -> FAILED
```

重新处理：

```text
FAILED/READY -> PENDING
```

删除：

```text
任意状态 -> DELETED
```

## 16. 混合检索设计

### 16.1 检索输入

```json
{
  "kbId": "kb_001",
  "query": "什么情况下可以退货？",
  "retrievalMode": "hybrid",
  "topK": 8,
  "scoreThreshold": 0.3
}
```

### 16.2 检索流程

1. 对 query 生成 embedding。
2. 执行向量召回。
3. 执行关键词召回。
4. 合并候选结果。
5. 去重。
6. 分数归一化。
7. 简单 rerank。
8. 返回最终 topK。
9. 构建引用来源。

### 16.3 检索模式

支持：

- `vector`：纯向量检索。
- `keyword`：关键词检索。
- `hybrid`：混合检索，默认模式。

### 16.4 返回结构

```json
{
  "query": "什么情况下可以退货？",
  "documents": [
    {
      "chunkId": "chunk_001",
      "itemId": "ki_001",
      "title": "售后退货规则",
      "content": "用户在签收后7天内...",
      "score": 0.86,
      "source": {
        "description": "售后退货规则",
        "fileName": "售后规则.pdf",
        "pageNumber": 3,
        "sectionTitle": "退货条件"
      }
    }
  ]
}
```

## 17. RAG 回答设计

### 17.1 回答约束

LLM 必须遵守：

- 只能基于召回片段回答。
- 不得编造来源。
- 证据不足时明确说明无法确认。
- 优先引用高分来源。
- 回答中可展示来源编号。

### 17.2 输出结构

```json
{
  "answer": "根据知识库，用户在签收后7天内且商品未影响二次销售时可以申请退货。",
  "citations": [
    {
      "index": 1,
      "chunkId": "chunk_001",
      "fileName": "售后规则.pdf",
      "pageNumber": 3,
      "content": "用户在签收后7天内..."
    }
  ],
  "confidence": "HIGH",
  "missingInfo": []
}
```

### 17.3 低置信度处理

如果召回结果低于阈值：

```json
{
  "answer": "知识库中未找到足够依据，无法确认该问题的答案。",
  "citations": [],
  "confidence": "LOW"
}
```

## 18. 索引同步设计

### 18.1 新增知识

- 创建 `knowledge_item`。
- 创建 `index_version=1`。
- 生成 chunk。
- 写入 `ACTIVE` 索引。

### 18.2 更新知识描述

- 更新 `knowledge_item.description`。
- 更新 chunk metadata 中的描述。
- 可不重新生成 embedding。
- 如摘要和关键词依赖描述，则重新生成增强字段。

### 18.3 更新文件或文本

- `index_version + 1`。
- 旧 chunk 标记为 `INACTIVE`。
- 新内容重新解析、分块、向量化。
- 新 chunk 写入 `ACTIVE`。
- 只有新版本参与检索。

### 18.4 删除知识

- `knowledge_item.status = DELETED`。
- 对应 chunk 标记为 `DELETED`。
- 原始文件可先保留，后续定时清理。

### 18.5 重新处理

- 保留原 `item_id`。
- 生成新的 `index_version`。
- 成功后切换有效版本。
- 失败时旧版本仍可保持可用，避免知识突然不可检索。

## 19. 权限与安全设计

### 19.1 权限过滤

检索必须带范围条件：

- `kb_id`
- `tenant_id`，如后续支持多租户。
- `created_by` 或权限组，如后续支持用户隔离。

### 19.2 上传安全

- 限制文件大小。
- 限制文件后缀。
- 校验 MIME 类型。
- 文件名去危险字符。
- 原始文件路径不直接暴露给前端。
- 下载或预览必须经过后端鉴权。

### 19.3 内容安全

- 不执行上传文件中的脚本。
- 不信任文档内嵌链接。
- OCR 和解析失败不暴露系统堆栈。
- LLM prompt 中明确知识片段边界，降低 prompt injection 风险。

## 20. 配置项设计

建议新增配置：

```text
knowledge.upload.max_file_size
knowledge.upload.allowed_types
knowledge.storage.type
knowledge.storage.local_path
knowledge.ocr.enabled
knowledge.ocr.provider
knowledge.ocr.min_confidence
knowledge.chunk.size
knowledge.chunk.overlap
knowledge.chunk.max_size
knowledge.embedding.provider
knowledge.embedding.model
knowledge.embedding.dimension
knowledge.embedding.batch_size
knowledge.retrieval.default_top_k
knowledge.retrieval.score_threshold
knowledge.retrieval.mode
knowledge.index.keep_old_versions
knowledge.index.cleanup_days
```

## 21. 异常处理设计

| 场景 | 处理方式 |
|---|---|
| 文件为空 | 上传失败，提示文件为空 |
| 格式不支持 | 上传失败，提示格式不支持 |
| PDF 无文本层 | 尝试 OCR |
| OCR 无结果 | 任务失败，提示未识别到有效文字 |
| Word 解析失败 | 任务失败，提示文档可能损坏 |
| embedding 失败 | 自动重试 |
| pgvector 写入失败 | 任务失败，可重新处理 |
| 删除索引失败 | 标记待清理，后台重试 |
| 检索无结果 | 返回知识库无足够依据 |

## 22. 可观测性设计

### 22.1 日志

记录：

- 上传日志。
- 解析日志。
- OCR 日志。
- 分块数量。
- embedding 耗时。
- 检索耗时。
- RAG 使用的 chunk。
- 失败原因。

### 22.2 指标

建议统计：

- 知识条目数量。
- 成功处理数量。
- 失败处理数量。
- 平均处理耗时。
- OCR 失败率。
- 平均 chunk 数。
- 检索平均耗时。
- 低置信度回答比例。

### 22.3 管理端展示

知识库详情中展示：

- 总知识数。
- 可用知识数。
- 处理中知识数。
- 失败知识数。
- 最近处理失败原因。

## 23. 验收标准

### 23.1 上传与入库

- 用户只填写“知识描述”即可上传知识。
- 支持文本粘贴、`.txt`、`.pdf`、`.doc/.docx`、图片。
- 上传后能看到处理状态。
- 处理成功后知识可检索。
- 处理失败后能看到明确原因。

### 23.2 OCR 与解析

- 图片可以识别文字并入库。
- PDF 有文本层时优先文本抽取。
- PDF 无文本层时可走 OCR。
- Word 文档可抽取正文内容。
- 用户可查看抽取文本。

### 23.3 检索与回答

- 支持向量、关键词、混合检索。
- 默认使用混合检索。
- RAG 回答带引用来源。
- 召回不足时不强行回答。
- 知识节点能使用新知识库检索结果。

### 23.4 更新与删除

- 更新文件后旧内容不再参与检索。
- 删除知识后对应 chunk 不再参与检索。
- 重新处理不会产生重复有效索引。
- 重新处理失败时可保留旧可用版本。

### 23.5 安全与权限

- 未授权用户不能访问知识库。
- 检索不会跨知识库泄露数据。
- 原始文件不能绕过后端直接访问。
- 上传异常文件不会导致服务崩溃。

## 24. 推荐一次性建设顺序

虽然作为一个阶段交付，实际开发建议按以下内部顺序推进：

1. Java 后端知识库、知识条目、处理任务表与接口。
2. 前端知识库列表、详情、上传、状态展示。
3. Python AI 文本/PDF/Word/图片解析服务。
4. 文本清洗、分块、embedding、pgvector 写入。
5. 混合检索接口。
6. 工作流知识节点接入新检索结果。
7. 引用溯源展示。
8. 更新、删除、重处理索引同步。
9. 日志、指标、异常兜底。
10. 端到端验收。

## 25. 关键风险与建议

- **OCR 质量风险**：图片质量差会影响知识质量，建议展示抽取文本供用户检查。
- **embedding 维度风险**：正式表结构必须与实际 embedding 模型维度一致。
- **大文件处理风险**：必须异步处理，并限制上传大小。
- **旧索引污染风险**：更新和删除必须使用 `index_version + status` 控制有效索引。
- **幻觉风险**：RAG prompt 必须要求“无依据不回答”，并展示引用来源。
- **权限风险**：检索 SQL 必须带知识库和用户权限过滤，不能先全量召回再过滤。

## 26. 评审结论项

请重点评审以下内容：

- 是否接受“一个阶段一次性建设”的范围。
- 是否确认用户只必填“知识描述”。
- 是否确认本阶段直接包含图片 OCR。
- 是否确认默认检索模式为 `hybrid`。
- 是否确认知识回答必须展示引用来源。
- 是否确认原始文件先使用本地存储，后续再扩展 MinIO/S3。
- 是否确认 pgvector 作为正式向量库。