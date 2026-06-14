# 知识库中心设计文档

版本：`v1.0`
日期：`2026-06-14`
适用项目：`robot-agent`

## 1. 设计目标

建设一个独立的知识库中心，作为服务机器人控制台中的全屏工作台，而不是嵌入式小卡片模块。

目标包括：

- 提供知识空间的创建、查看、编辑、删除。
- 提供知识条目的文本录入、文件上传、处理状态查看。
- 提供采集任务的全流程可观测与重试。
- 提供独立的知识检索页面，展示命中片段、总结答案和引用来源。
- 在聊天或工作流交互中，先区分意图与知识，再走对应链路。
- 知识处理成功后立即可检索，不设置手动发布门槛。

## 2. 范围说明

### 2.1 本阶段包含

- 知识空间首页、详情页、采集任务页、知识检索页。
- 文本输入与文件上传。
- 支持 `txt`、`pdf`、`doc`、`docx`、`md` 和直接粘贴文本。
- 不支持图片 OCR。
- 采集任务状态展示、失败原因、重试。
- 知识命中结果、总结答案、引用来源展示。
- 输入路由：意图识别与知识检索并行，按规则聚合决策。

### 2.2 本阶段不包含

- 图片 OCR。
- 复杂发布审批流。
- 大规模知识图谱。
- 自动爬取知识。
- 企业级复杂权限矩阵。

## 3. 现有基础

项目中已有可复用基础：

- `KnowledgeController`
- `KnowledgeService`
- `KnowledgeBase`
- `KnowledgeDocument`
- `KnowledgeVersion`
- Docker Compose 已包含 MySQL、Redis、pgvector。
- Python AI 已有 `KnowledgeStore` 抽象和 pgvector 存储雏形。

本次设计不重做一套独立知识服务，而是在现有知识模型基础上，切换到“知识空间 + 知识条目 + 采集任务 + 检索”的产品语义。

其中：

- `KnowledgeBase` 对应知识空间。
- `KnowledgeDocument` 对应知识条目/来源文档。
- `KnowledgeVersion` 不再作为用户可见的发布门槛，若继续保留，仅作为内部索引版本兼容。

## 4. 技术架构与中间件

### 4.1 中间件选择

本阶段使用现有中间件，不额外引入 Elasticsearch、Milvus、Qdrant、Kafka 或 RabbitMQ。

| 用途 | 中间件 | 说明 |
|---|---|---|
| 业务元数据 | MySQL 8 | 存储知识空间、知识条目、采集任务、绑定关系、审计信息 |
| 原始文件 | 本地文件存储 | 初版使用后端本地挂载目录；后续可替换 MinIO/S3 |
| 向量与分块 | PostgreSQL + pgvector | 存储 chunk 文本、embedding、来源元数据、索引状态 |
| 缓存与幂等 | Redis 7 | 复用现有 Redis，用于幂等、短期检索缓存、路由结果缓存 |
| 异步处理 | Java 任务表 + Python AI 处理接口 | 初版不引入独立消息队列；任务状态落 MySQL，处理可由后台线程或轮询 worker 驱动 |

### 4.2 服务职责

**Java Backend**

- 管理知识空间和知识条目元数据。
- 接收文本与文件上传。
- 存储原始文件。
- 创建采集任务。
- 调用 Python AI 进行解析、分块、向量化、检索。
- 对前端提供统一接口。

**Python AI**

- 文档解析。
- 文本清洗。
- 语义分块。
- embedding 生成。
- pgvector 写入。
- 混合检索。
- 基于命中片段生成总结。

**MySQL**

- 不存储 embedding。
- 不承担向量检索。
- 存储业务视图需要的元数据与任务状态。

**pgvector**

- 存储可检索 chunk。
- 存储向量。
- 存储检索用来源 metadata。
- 执行向量相似度召回。

## 5. 存储设计

### 5.1 原始数据存储

原始数据分为文本输入和文件输入。

**文本输入**

- Java 后端接收文本内容。
- 原始文本作为知识条目的原始内容保存，可存 MySQL `TEXT` 字段或落本地文本文件。
- 推荐初版将较短文本存 MySQL，较长文本写入本地文件，并在 MySQL 中保存 `raw_content_path`。

**文件输入**

- 文件由 Java 后端保存到本地挂载目录。
- 推荐路径结构：

```text
data/knowledge/raw/{workspace_id}/{kb_code}/{doc_id}/{original_filename}
```

- MySQL 只保存文件元数据和路径，不保存二进制文件内容。
- 文件名必须做安全清洗，前端不能直接访问真实文件路径。
- 后续扩展 MinIO/S3 时，只替换文件存储服务，不改变知识条目模型。

### 5.2 元数据存储

MySQL 存储：

- 知识空间。
- 知识条目。
- 采集任务。
- 会话/工作流与知识空间绑定关系。
- 任务状态、失败原因、统计字段。

### 5.3 向量数据存储

pgvector 存储：

- chunk 文本。
- embedding 向量。
- chunk 来源元数据。
- `kb_code`、`doc_id`、`index_version`、`status`。

正式表的 embedding 维度必须与实际 embedding 模型一致，不能沿用当前测试雏形中的 `VECTOR(8)`。

推荐表结构：

```sql
CREATE TABLE knowledge_chunks (
    chunk_id TEXT PRIMARY KEY,
    kb_code TEXT NOT NULL,
    doc_id TEXT NOT NULL,
    index_version INT NOT NULL,
    chunk_index INT NOT NULL,
    title TEXT,
    content TEXT NOT NULL,
    content_hash TEXT,
    embedding VECTOR(1536) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

推荐索引：

```sql
CREATE INDEX idx_knowledge_chunks_scope
ON knowledge_chunks (kb_code, status, index_version);

CREATE INDEX idx_knowledge_chunks_doc
ON knowledge_chunks (doc_id, index_version);

CREATE INDEX idx_knowledge_chunks_metadata
ON knowledge_chunks USING GIN (metadata);

CREATE INDEX idx_knowledge_chunks_embedding
ON knowledge_chunks USING ivfflat (embedding vector_cosine_ops);
```

### 5.4 索引版本

知识处理成功后立即可检索。

更新文件或文本时：

1. 生成新的 `index_version`。
2. 新 chunk 写入 pgvector。
3. 成功后将新版本标记为 `ACTIVE`。
4. 旧版本标记为 `INACTIVE`。
5. 失败时旧版本保持可用，避免知识突然不可检索。

删除知识时：

- MySQL 知识条目标记删除。
- pgvector chunk 标记为 `DELETED`。
- 检索默认只查询 `ACTIVE`。

## 6. 页面设计

### 6.1 全局布局

- 知识库中心占满整个可用屏幕空间。
- 顶部保持与现有控制台一致的全局头部和导航风格。
- 左侧二级导航仅保留文字项，不使用前置小框装饰。
- 主工作区采用白色半透明面板、Slate 色系、深色主按钮、蓝色链接。

### 6.2 知识空间首页

首页是知识库中心入口，展示：

- 空间总数、知识总数、文件总数、采集中任务、失败任务。
- 知识空间搜索与筛选。
- 知识空间列表。
- `+ 新增知识空间` 入口必须首屏可见。

首页不展示单个空间的知识条目，不承担上传入口。

### 6.3 知识空间详情页

进入某个知识空间后展示：

- 空间统计。
- 知识分类标签。
- 知识列表。
- `+ 新增知识` 入口首屏可见。
- `检索测试` 与 `空间设置`。

新增知识弹窗包含：

- 标题。
- 描述。
- 来源类型：输入文本 / 上传文件。
- 文本内容。
- 文件上传区。

### 6.4 采集任务页

采集任务页用于排查异步处理过程，展示：

- 任务列表。
- 空间筛选。
- 状态筛选。
- 阶段筛选。
- 任务详情。
- 失败原因。
- 重试入口。

任务详情展示阶段进度：

1. 文本保存 / 文本抽取
2. 内容清洗
3. 语义分块
4. 向量化
5. 索引入库

### 6.5 知识检索页

知识检索页用于单独查询知识库，展示：

- 检索输入框。
- 检索范围选择。
- 知识类型选择。
- Top K 选择。
- 命中结果列表。
- 总结答案。
- 命中详情。

结果必须展示：

- 命中来源。
- 页码或段落。
- 相似度分数。
- 命中片段。
- 总结答案的引用来源。

## 7. 输入路由与交互链路

### 7.1 绑定上下文

会话或工作流在启动时加载一次已绑定的知识空间列表，写入运行时上下文。

运行时只读取缓存的：

- `boundKnowledgeSpaceIds`
- `bindingVersion`

不在每次用户输入时全量查询知识空间列表。

### 7.2 并行路由

用户输入后，系统并行启动两条线路：

- 意图识别线路
- 知识检索线路

知识检索线路仅在 `boundKnowledgeSpaceIds` 非空时启动；为空时直接短路，不查询知识库。

### 7.3 聚合决策

聚合器以配置阈值决定最终路由。判定顺序必须固定，避免同一输入在不同分支中产生不一致结果：

1. 如果 `intent.confidence >= knowledge.route.intent_primary_threshold`，最终路由为 `INTENT`。
2. 如果 `intent.confidence < knowledge.route.intent_primary_threshold` 且 `knowledge.bestScore >= knowledge.route.knowledge_primary_threshold`，最终路由为 `KNOWLEDGE`。
3. 如果 `intent.confidence >= knowledge.route.intent_clarify_threshold` 且 `intent.confidence < knowledge.route.intent_primary_threshold`，同时 `knowledge.bestScore >= knowledge.route.knowledge_clarify_threshold` 且 `knowledge.bestScore < knowledge.route.knowledge_primary_threshold`，最终路由为 `CLARIFY`。
4. 如果 `intent.confidence < knowledge.route.intent_clarify_threshold` 且 `knowledge.bestScore < knowledge.route.knowledge_clarify_threshold`，最终路由为 `FALLBACK`。
5. 其他边界情况按“意图优先、知识次之、无法确定则澄清”处理：
   - 意图达到澄清阈值但知识低于澄清阈值时，输出意图澄清问题。
   - 意图低于澄清阈值但知识达到澄清阈值时，输出知识澄清问题或低置信知识提示。

推荐默认阈值写入配置，当前建议值如下：

```text
knowledge.route.intent_primary_threshold = 0.75
knowledge.route.knowledge_primary_threshold = 0.65
knowledge.route.intent_clarify_threshold = 0.55
knowledge.route.knowledge_clarify_threshold = 0.55
```

可选的附加配置：

```text
knowledge.route.intent_timeout_ms
knowledge.route.knowledge_timeout_ms
knowledge.route.max_wait_ms
knowledge.route.cancel_late_branch
```

### 7.4 路由结果

统一输出结构建议：

```json
{
  "finalRoute": "INTENT",
  "routeReason": "意图识别高置信命中",
  "intentResult": {
    "matched": true,
    "workflowCode": "after_sale_flow",
    "confidence": 0.82
  },
  "knowledgeResult": {
    "searched": true,
    "matched": false,
    "bestScore": 0.43,
    "spaceIds": ["kb_product", "kb_faq"]
  }
}
```

## 8. 知识检索方式

### 8.1 命中范围

知识检索只在绑定的知识空间内进行，并且只查可用知识：

- `READY`
- `ACTIVE`

不查未完成、失败、删除或失效索引。

### 8.2 默认检索模式

默认使用混合检索 `hybrid`，不单独依赖向量或关键词。

流程：

1. 对用户问题生成 query embedding。
2. 在 pgvector 中按绑定 `kb_code`、`status=ACTIVE`、有效 `index_version` 做向量召回。
3. 在 pgvector/PostgreSQL 中对 `content`、`title`、`metadata` 做关键词召回。
4. 合并两个候选集合。
5. 按 `chunk_id` 去重。
6. 计算综合分数。
7. 按分数排序取 Top K。
8. 构建引用来源。

### 8.3 分数融合

建议综合分数：

```text
final_score = vector_score * vector_weight + keyword_score * keyword_weight + metadata_boost
```

默认配置：

```text
knowledge.retrieval.mode = hybrid
knowledge.retrieval.top_k = 5
knowledge.retrieval.score_threshold = 0.65
knowledge.retrieval.vector_weight = 0.7
knowledge.retrieval.keyword_weight = 0.3
knowledge.retrieval.metadata_boost = 0.05
```

`metadata_boost` 可用于提升标题、用户填写描述、文件名中的明确命中。

### 8.4 检索返回

检索接口返回 chunk 列表，不直接只返回答案。

```json
{
  "query": "产品保修期多久？",
  "documents": [
    {
      "chunkId": "chunk_001",
      "docId": "doc_001",
      "kbCode": "kb_product",
      "title": "产品手册.pdf",
      "content": "保修期为自购买之日起一年...",
      "score": 0.96,
      "source": {
        "fileName": "产品手册.pdf",
        "pageNumber": 15,
        "sectionTitle": "保修政策"
      }
    }
  ]
}
```

## 9. 知识总结输出

### 9.1 总结规则

知识命中后，LLM 只能基于召回片段总结，不允许编造来源。

输出必须包含：

- 答案正文
- 引用列表
- 命中分数
- 片段来源

若证据不足，返回明确的低置信提示，不强行总结。

### 9.2 输出结构

```json
{
  "answer": "根据当前知识空间，产品保修期通常为自购买之日起一年。",
  "citations": [
    {
      "index": 1,
      "chunkId": "chunk_001",
      "docId": "doc_001",
      "kbCode": "kb_product",
      "title": "产品手册.pdf",
      "pageNumber": 15,
      "score": 0.96,
      "snippet": "保修期为自购买之日起一年..."
    }
  ],
  "confidence": "HIGH",
  "missingInfo": []
}
```

### 9.3 兜底话术

当意图识别和知识检索都失败时，返回兜底话术：

- 未识别到明确意图
- 未找到足够知识依据
- 提示用户重新描述或选择知识空间

## 10. 数据模型

### 10.1 知识空间

复用 `knowledge_base`，字段建议保持：

- `id`
- `workspace_id`
- `kb_code`
- `name`
- `description`
- `status`
- `created_by`
- `created_at`
- `updated_at`

### 10.2 知识条目

复用 `knowledge_document` 作为知识条目/来源文档，建议包含：

- `doc_id`
- `kb_code`
- `filename`
- `file_size`
- `file_url`
- `status`
- `chunk_count`
- `error_message`
- `uploaded_at`
- `processed_at`

建议新增字段：

- `source_type`
- `raw_content_path`
- `content_hash`
- `generated_title`
- `generated_summary`
- `generated_keywords`
- `index_version`

### 10.3 采集任务

新增或扩展任务表，记录：

- `task_id`
- `doc_id`
- `kb_code`
- `stage`
- `status`
- `progress`
- `error_message`
- `retry_count`
- `created_at`
- `updated_at`

### 10.4 分块索引

分块表保存：

- `chunk_id`
- `doc_id`
- `kb_code`
- `index_version`
- `chunk_index`
- `content`
- `summary`
- `embedding`
- `metadata`
- `status`

分块索引由 pgvector 所在 PostgreSQL 管理，不存 MySQL。

## 11. 接口设计

### 11.1 知识空间

```http
GET /api/knowledge-bases
POST /api/knowledge-bases
GET /api/knowledge-bases/{kbCode}
PUT /api/knowledge-bases/{kbCode}
DELETE /api/knowledge-bases/{kbCode}
```

### 11.2 知识条目

```http
GET /api/knowledge-bases/{kbCode}/documents
POST /api/knowledge-bases/{kbCode}/documents
GET /api/knowledge-documents/{docId}
PUT /api/knowledge-documents/{docId}
DELETE /api/knowledge-documents/{docId}
POST /api/knowledge-documents/{docId}/reprocess
```

### 11.3 采集任务

```http
GET /api/knowledge-tasks/{taskId}
GET /api/knowledge-documents/{docId}/tasks
POST /api/knowledge-tasks/{taskId}/retry
```

### 11.4 检索

```http
POST /api/knowledge-bases/{kbCode}/search
POST /api/knowledge/search
```

## 12. 配置项

### 12.1 存储配置

```text
knowledge.storage.type = local
knowledge.storage.local_path = data/knowledge/raw
knowledge.storage.max_file_size_mb = 100
knowledge.storage.allowed_types = txt,pdf,doc,docx,md
```

### 12.2 向量配置

```text
knowledge.vector.enabled = true
knowledge.vector.dsn = ROBOT_VECTOR_DSN
knowledge.vector.table = knowledge_chunks
knowledge.embedding.provider = configurable
knowledge.embedding.model = configurable
knowledge.embedding.dimension = 1536
knowledge.embedding.batch_size = 32
knowledge.embedding.timeout_ms = 30000
```

### 12.3 检索配置

```text
knowledge.retrieval.mode = hybrid
knowledge.retrieval.top_k = 5
knowledge.retrieval.score_threshold = 0.65
knowledge.retrieval.vector_weight = 0.7
knowledge.retrieval.keyword_weight = 0.3
knowledge.retrieval.metadata_boost = 0.05
```

### 12.4 路由配置

```text
knowledge.route.intent_primary_threshold = 0.75
knowledge.route.knowledge_primary_threshold = 0.65
knowledge.route.intent_clarify_threshold = 0.55
knowledge.route.knowledge_clarify_threshold = 0.55
knowledge.route.intent_timeout_ms = 1200
knowledge.route.knowledge_timeout_ms = 1800
knowledge.route.max_wait_ms = 2000
knowledge.route.cancel_late_branch = true
```

## 13. 异常处理

- 文件为空：上传失败。
- 格式不支持：上传失败。
- 解析失败：任务失败并返回明确原因。
- 向量化失败：任务失败，可重试。
- 检索无结果：返回低置信提示。
- 未绑定知识空间：知识线路短路，不检索。
- 路由结果模糊：触发澄清。

## 14. 可观测性

建议记录：

- 空间总数
- 知识总数
- 成功采集数
- 失败采集数
- 平均任务耗时
- 意图命中率
- 知识命中率
- 澄清比例
- 兜底比例
- 检索平均耗时

## 15. 验收标准

- 知识库中心全屏展示，风格与现有服务机器人控制台一致。
- 首页可见新增知识空间入口和知识空间列表。
- 进入空间后可见新增知识入口和知识列表。
- 采集任务页可查看任务阶段、失败原因和重试。
- 知识检索页可展示命中片段、总结答案和引用来源。
- 用户输入可并行做意图识别和知识检索。
- 路由阈值可配置。
- 知识处理成功后立即可检索。
- 不做图片 OCR。
- MySQL 存储知识元数据和任务状态。
- 原始文件存储到本地挂载目录。
- pgvector 存储 chunk 与 embedding。
- 默认知识检索方式为混合检索。

## 16. 推荐实施顺序

1. 空间首页和详情页。
2. 知识条目上传和采集任务。
3. 混合检索与命中展示。
4. 输入路由并行聚合。
5. 引用来源与兜底话术。
6. 指标和异常补全。
