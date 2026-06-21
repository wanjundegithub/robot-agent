# 知识命中流式输出与检索降延时设计

日期：`2026-06-21`
适用项目：`robot-agent`

## 目标

用户在聊天中输入问题并命中已绑定知识库时，机器人答案通过现有 WebSocket `message.delta` 通道流式输出，而不是仅在同步响应的 `clarificationQuestion` 中一次性返回。同时降低知识检索耗时，目标是把常规知识命中链路从超过 4 秒降到 2 秒级以内，并保持现有阈值判断和软删除过滤，不降低命中准确率。

本次还把知识向量维度从硬编码默认 `4096` 改为可配置默认 `1024`，减少 pgvector 存储、索引、距离计算和 embedding 网络载荷成本。

## 现状

- 聊天输入走 `ExecutionService.startExecution`。
- `WorkflowService.tryBuildKnowledgeRoutingDecision` 在知识库绑定存在时同步调用 `KnowledgeService.searchKnowledge`。
- 知识命中后返回 `RoutingDecision("knowledge_answer", ..., clarificationQuestion=<知识内容>)`。
- `ExecutionService.buildKnowledgeAnswerResponse` 不创建工作流执行，也不发送 `message.delta`，前端只能从同步响应字段拿到完整答案。
- Java 到前端已有 WebSocket `message.delta` 通道，普通工作流执行和 Python SSE 事件会复用该通道。
- Python 知识检索当前会在 `hybrid/vector` 模式下先调用远程 embedding，再查 pgvector 和关键词结果。
- `python-ai` 的 `ROBOT_VECTOR_DIMENSION` 默认是 `4096`，pgvector 表定义为 `VECTOR(4096)`。

## 方案

### 聊天知识命中流式输出

知识命中后继续不启动完整工作流执行，避免引入额外调度成本；但 Java 要生成一个轻量级知识消息执行标识，异步通过 `UserConnectionManager.sendMessageDeltaFrame` 推送答案。

响应行为：

- 同步响应仍返回 `routeDecision=knowledge_answer`，用于前端识别这是知识命中。
- `status` 改为语义明确的 `knowledge_answer_streaming`。
- `executionId` 使用轻量标识，例如 `knowledge_<uuid>`，仅用于前端把 delta 合并到同一条机器人消息，不写入完整工作流执行表。
- `clarificationQuestion` 可保留完整答案用于向后兼容，但前端展示以 `message.delta` 为准。

流式帧：

1. 检索命中后立即发送首帧，可为空内容，`is_complete=false`。
2. 将知识答案按自然段或固定长度切片发送多帧 `message.delta`。
3. 最后一帧发送 `content=""`、`is_complete=true`。

准确率约束：

- 只有 `KnowledgeRouteDecisionService` 最终判定为 `KNOWLEDGE` 时才发送知识答案。
- `CLARIFY` 和 `INTENT` 路由不走知识答案流式输出。
- 软删除文档过滤后如果命中被清空，不发送知识答案。

### 检索降延时

优先做不影响准确率的路径优化：

- 聊天知识路由调用 `KnowledgeSearchRequest.generateAnswer=false`。Python 当前并不生成答案，显式关闭可以避免后续新增总结生成后拖慢路由。
- Java 检索请求带上 `usage=chat_route` 或等价内部标记，用于后续区分知识库测试页和聊天路由。
- 对同一会话、同一绑定知识库集合、同一规范化问题增加短 TTL 检索缓存，缓存完整 `KnowledgeSearchResponse`，TTL 建议 30 到 120 秒。
- 对聊天路由保留 `hybrid` 默认，但将 `vector_top_k` 和 `keyword_top_k` 保持可配置，默认仍为 20，避免为了速度直接降低召回面。
- 对 Python 检索增加阶段耗时日志：embedding、vector query、keyword query、merge/filter，便于确认超过 4 秒的真实瓶颈。

可选降级策略：

- 当 embedding 调用超时且关键词分数已达到知识主阈值时，可以返回关键词高置信结果。
- 当关键词分数未达主阈值时，不降级为知识答案，保持原有准确率边界。

### 向量维度降低

默认配置从 `4096` 调整为 `1024`：

```text
ROBOT_VECTOR_DIMENSION=1024
ROBOT_VECTOR_TABLE=knowledge_chunks
```

模型配置要求：

- embedding 模型记录的 `default_options.dimensions` 或 `default_options.embedding_dimension` 应与 `ROBOT_VECTOR_DIMENSION` 一致。
- Python embedding 请求使用 `dimensions` 参数时，应兼容 `dimensions` 和 `embedding_dimension` 两种配置键。
- 如果上游模型不支持降维参数，必须改用原生 1024 维 embedding 模型，不能把 4096 维向量截断后写入 1024 维表。

数据迁移要求：

- pgvector 的 `VECTOR(n)` 维度不能就地接收不同长度向量。
- 从 4096 降到 1024 时，默认保留表名 `knowledge_chunks`。启动初始化如果发现该表 embedding 维度不是 1024，会自动删除并重建该表，然后需要重新处理知识文档生成 1024 维 chunk embedding。
- 启动时如果表维度与配置不一致，继续保持当前失败保护，不自动破坏已有数据。

推荐迁移路径：

1. 停止 Python AI 服务。
2. 设置 `ROBOT_VECTOR_DIMENSION=1024`。
3. 确认 embedding 模型配置输出 1024 维。
4. 启动 Python AI，让初始化流程自动重建 `knowledge_chunks` 为 1024 维表。
5. 重试或重跑知识采集任务，重新入库所有知识条目。
6. 验证聊天知识命中和知识搜索页均能返回结果。

## 测试

Java 单元测试：

- 知识命中时 `ExecutionService` 返回 `knowledge_answer_streaming`，并调用 `sendMessageDeltaFrame` 至少一次内容帧和一次完成帧。
- 知识命中仍不创建完整工作流执行。
- 聊天知识路由构造的 `KnowledgeSearchRequest.generateAnswer=false`。
- 软删除命中过滤后不发送知识答案。

Python 单元测试：

- 默认 `vector_dimension` 为 1024。
- `KnowledgeStore.initialize` 使用 `VECTOR(1024)`。
- embedding runtime 同时识别 `dimensions` 和 `embedding_dimension`，请求体发送 `dimensions`。
- 检索 API 使用配置维度校验 query embedding。

集成验证：

- `mvn test` 覆盖 Java 后端相关测试。
- `pytest` 覆盖 Python AI 知识检索和 embedding 测试。
- 手动验证聊天页：用户输入知识命中问题后，机器人消息通过流式 delta 逐步出现，最终完成态收敛。

## 非目标

- 不引入 Elasticsearch、Milvus、Qdrant 或新的消息队列。
- 不把知识命中强制转成完整工作流执行。
- 不在本次实现 LLM 知识总结 token 级流式生成。
- 不自动删除或迁移生产 pgvector 数据。
