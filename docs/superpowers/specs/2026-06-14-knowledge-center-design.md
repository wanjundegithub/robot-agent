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

本次设计不重做一套独立知识服务，而是在现有知识模型基础上，切换到“知识空间 + 知识条目 + 采集任务 + 检索”的产品语义。

其中：

- `KnowledgeBase` 对应知识空间。
- `KnowledgeDocument` 对应知识条目/来源文档。
- `KnowledgeVersion` 不再作为用户可见的发布门槛，若继续保留，仅作为内部索引版本兼容。

## 4. 页面设计

### 4.1 全局布局

- 知识库中心占满整个可用屏幕空间。
- 顶部保持与现有控制台一致的全局头部和导航风格。
- 左侧二级导航仅保留文字项，不使用前置小框装饰。
- 主工作区采用白色半透明面板、Slate 色系、深色主按钮、蓝色链接。

### 4.2 知识空间首页

首页是知识库中心入口，展示：

- 空间总数、知识总数、文件总数、采集中任务、失败任务。
- 知识空间搜索与筛选。
- 知识空间列表。
- `+ 新增知识空间` 入口必须首屏可见。

首页不展示单个空间的知识条目，不承担上传入口。

### 4.3 知识空间详情页

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

### 4.4 采集任务页

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

### 4.5 知识检索页

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

## 5. 输入路由与交互链路

### 5.1 绑定上下文

会话或工作流在启动时加载一次已绑定的知识空间列表，写入运行时上下文。

运行时只读取缓存的：

- `boundKnowledgeSpaceIds`
- `bindingVersion`

不在每次用户输入时全量查询知识空间列表。

### 5.2 并行路由

用户输入后，系统并行启动两条线路：

- 意图识别线路
- 知识检索线路

知识检索线路仅在 `boundKnowledgeSpaceIds` 非空时启动；为空时直接短路，不查询知识库。

### 5.3 聚合决策

聚合器以配置阈值决定最终路由：

- 意图高置信命中，优先走意图。
- 意图未命中或低置信时，若知识命中足够，则走知识。
- 两边都处于模糊区间时，触发澄清。
- 两边都低于最低阈值时，触发兜底话术。

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

### 5.4 路由结果

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

## 6. 知识命中与总结

### 6.1 命中规则

知识检索只在绑定的知识空间内进行，并且只查可用知识：

- `READY`
- `ACTIVE`

不查未完成、失败、删除或失效索引。

### 6.2 检索方式

默认使用混合检索：

- 向量召回
- 关键词召回
- 去重合并
- 排序

### 6.3 总结输出

知识命中后，LLM 只能基于召回片段总结，不允许编造来源。

输出必须包含：

- 答案正文
- 引用列表
- 命中分数
- 片段来源

若证据不足，返回明确的低置信提示，不强行总结。

### 6.4 兜底话术

当意图识别和知识检索都失败时，返回兜底话术：

- 未识别到明确意图
- 未找到足够知识依据
- 提示用户重新描述或选择知识空间

## 7. 数据模型

### 7.1 知识空间

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

### 7.2 知识条目

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

### 7.3 采集任务

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

### 7.4 分块索引

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

## 8. 接口设计

### 8.1 知识空间

```http
GET /api/knowledge-bases
POST /api/knowledge-bases
GET /api/knowledge-bases/{kbCode}
PUT /api/knowledge-bases/{kbCode}
DELETE /api/knowledge-bases/{kbCode}
```

### 8.2 知识条目

```http
GET /api/knowledge-bases/{kbCode}/documents
POST /api/knowledge-bases/{kbCode}/documents
GET /api/knowledge-documents/{docId}
PUT /api/knowledge-documents/{docId}
DELETE /api/knowledge-documents/{docId}
POST /api/knowledge-documents/{docId}/reprocess
```

### 8.3 采集任务

```http
GET /api/knowledge-tasks/{taskId}
GET /api/knowledge-documents/{docId}/tasks
POST /api/knowledge-tasks/{taskId}/retry
```

### 8.4 检索

```http
POST /api/knowledge-bases/{kbCode}/search
POST /api/knowledge/search
```

## 9. 异常处理

- 文件为空：上传失败。
- 格式不支持：上传失败。
- 解析失败：任务失败并返回明确原因。
- 向量化失败：任务失败，可重试。
- 检索无结果：返回低置信提示。
- 未绑定知识空间：知识线路短路，不检索。
- 路由结果模糊：触发澄清。

## 10. 可观测性

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

## 11. 验收标准

- 知识库中心全屏展示，风格与现有服务机器人控制台一致。
- 首页可见新增知识空间入口和知识空间列表。
- 进入空间后可见新增知识入口和知识列表。
- 采集任务页可查看任务阶段、失败原因和重试。
- 知识检索页可展示命中片段、总结答案和引用来源。
- 用户输入可并行做意图识别和知识检索。
- 路由阈值可配置。
- 知识处理成功后立即可检索。
- 不做图片 OCR。

## 12. 推荐实施顺序

1. 空间首页和详情页。
2. 知识条目上传和采集任务。
3. 混合检索与命中展示。
4. 输入路由并行聚合。
5. 引用来源与兜底话术。
6. 指标和异常补全。

