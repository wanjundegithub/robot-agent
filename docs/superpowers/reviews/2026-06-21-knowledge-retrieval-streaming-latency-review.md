# 知识检索与流式回答低延迟架构独立评审

**评审日期：** 2026-06-21  
**评审对象：** `docs/superpowers/specs/2026-06-21-knowledge-retrieval-streaming-latency-design.md`  
**结论：** **不通过**

## 1. 结论摘要

方案的方向总体合理，并且有两点表述是诚实的：

- 知识检索 P95≤2 秒被限定在数据规模、并发、资源和上游 embedding 延迟条件内，没有写成无条件 SLA。
- 首个非空回答 Token P95≤1 秒被明确列为非阻断优化目标，没有把低延迟模型或伪造占位文本设为上线门槛。

但是，当前版本还不能进入实施计划，原因不是缺少细节润色，而是存在会直接阻断实现或破坏准确率、协议正确性的结构性问题：

1. 当前仓库使用 `VECTOR(4096)`，而设计给出的 pgvector HNSW `vector_cosine_ops` 方案不能为 4096 维 `vector` 建索引；主性能路径在现有数据模型上不可执行。
2. 检索发生在 execution 创建之前，但设计同时要求 `retrieval.completed` 使用 execution 级 `event_id/seq`、写入 execution Stream 并支持重放，事件身份模型自相矛盾。
3. `asyncio.to_thread` 不能取消已经运行的同步 psycopg 查询；设计没有给出可验证的 DB 取消、连接回收和晚结果隔离机制。
4. Python 当前模型流 callback 是同步接口，Java 当前 `subscribe(Consumer)` 会无界请求；仅把队列改为有界、检查 `Channel.isWritable()`，不能形成端到端背压。

在以上问题修正并重新评审前，不能承诺该方案能在保证准确率和真实流语义的同时达到声明目标。

## 2. 仓库抽查依据

本次评审完整阅读了设计文档，并按文档中的真实路径核查了以下关键实现：

- Java 路由与事务：
  - `java-backend/src/main/java/robot/agent/service/WorkflowService.java`
  - `java-backend/src/main/java/robot/agent/service/ExecutionService.java`
  - `java-backend/src/main/java/robot/agent/channel/handler/TextMessageHandler.java`
- Java 检索、流与连接：
  - `java-backend/src/main/java/robot/agent/service/KnowledgeService.java`
  - `java-backend/src/main/java/robot/agent/service/knowledge/PythonKnowledgeClient.java`
  - `java-backend/src/main/java/robot/agent/service/PythonClient.java`
  - `java-backend/src/main/java/robot/agent/service/UnifiedModelService.java`
  - `java-backend/src/main/java/robot/agent/channel/core/UserConnectionManager.java`
  - `java-backend/src/main/java/robot/agent/channel/netty/UserFrameInboundHandler.java`
  - `java-backend/src/main/java/robot/agent/service/NettyGatewayServer.java`
- Python 检索与流：
  - `python-ai/src/api/main.py`
  - `python-ai/src/core/embedding_runtime.py`
  - `python-ai/src/core/knowledge_store.py`
  - `python-ai/src/core/runtime.py`
  - `python-ai/src/core/events.py`
  - `python-ai/src/core/registry.py`
  - `python-ai/src/core/scheduler.py`
  - `python-ai/src/core/model_runtime.py`
  - `python-ai/src/nodes/knowledge.py`
  - `python-ai/src/core/protection.py`
  - `python-ai/src/core/settings.py`
- 前端协议与消息合并：
  - `frontend/src/App.tsx`
  - `frontend/src/services/frameProtocol.ts`
  - `frontend/src/types/index.ts`
- 依赖与部署：
  - `java-backend/pom.xml`
  - `java-backend/Dockerfile`
  - `python-ai/requirements.txt`
  - `python-ai/Dockerfile`
  - `docker-compose.yml`
  - `docker-compose.prod.yml`

抽查确认：设计对当前串行路由、同步 psycopg 占用 event loop、每请求新建 httpx client、Java 全库文档过滤、事务内订阅、SSE id 丢失、无界 Python queue、Netty 无写背压和前端错误终态语义的描述基本准确。

## 3. Findings

### Blocker

#### B1. 现有 4096 维向量无法使用设计给出的 HNSW 索引

- **位置：**
  - 设计第 7.7、13.2 节。
  - `python-ai/src/core/settings.py:13`：`vector_dimension=4096`。
  - `python-ai/src/core/knowledge_store.py:85`：表字段为 `VECTOR(4096)`。
  - `docker-compose.yml:106`、`docker-compose.prod.yml:72`：`pgvector/pgvector:pg16` 未固定扩展版本或镜像摘要。
- **问题：** pgvector HNSW 的 `vector` 类型索引最多支持 2000 维；`halfvec` 最多支持 4000 维，也仍小于当前 4096 维。文档中的 `USING hnsw (embedding vector_cosine_ops)` 在当前 schema 上不可创建。容器标签还没有固定 pgvector 扩展版本，无法保证 iterative scan 等能力存在。
- **影响：** P1 的主性能路径不可实施，100 万 chunk 场景的 P95≤2 秒没有可执行的 ANN 基础。若临时更换 embedding 维度或模型，又会触发全量重嵌入、索引迁移和准确率重新基线，绝非普通参数调整。
- **建议修正：**
  1. 在实施计划前完成 ANN 可行性决策，只能从经过验证的方案中选定一种：迁移到不超过 2000 维的 embedding 并全量重建；对 4096 维做 binary quantization/subvector HNSW 后用原向量精排；或采用明确支持该维度的检索引擎。
  2. 为选定方案提供迁移、双写/重建、回滚和黄金集结果。
  3. 固定 pgvector 镜像版本或 digest，启动时校验 `extversion` 和所需 GUC/能力，不能依赖漂移的 `pg16` 标签。

#### B2. `retrieval.completed` 的事件身份与当前 execution 创建顺序冲突

- **位置：**
  - 设计第 7.2 节要求知识分支完成后立即发送 `retrieval.completed`。
  - 第 9.2、9.3、9.8 节要求事件带 `execution_id`、execution 内单调 `seq`，并写入 `events:{workspace_id}:{execution_id}`。
  - `java-backend/src/main/java/robot/agent/service/ExecutionService.java:224-228` 先完成路由，`437-457` 才创建并保存 execution。
- **问题：** 路由阶段尚无 `execution_id`，因此检索事件无法同时满足“立即发送”“execution 内排序”“写入 execution Stream”“按 execution cursor 重放”。文档没有定义 message/request 级预执行事件空间，也没有提前持久化 execution。
- **影响：** `retrieval_e2e_ms` 的浏览器终点无法按设计产生；重放、去重、唯一序列和 ACK 顺序会出现不可消除的竞态。
- **建议修正：** 在设计中明确选择一种状态模型：
  1. 推荐先做短事务 intake：以数据库唯一幂等键创建 message/execution，状态为 `ROUTING`，提交后发送 `message.accepted`，后续检索事件即可进入同一 execution 序列。
  2. 或将 `retrieval.*` 明确定义为 request/message 级事件，使用独立序列和 Stream，execution 创建后再建立关联。
  3. 两种模型不能混用；事件存储、cursor、终态和指标口径必须随选定模型统一。

#### B3. deadline 到期不能按当前设计真正取消同步 DB 工作

- **位置：**
  - 设计第 7.1、7.3、9.7、15.1、15.3 节。
  - `python-ai/src/api/main.py:345` 当前直接同步查询。
  - 设计建议保留同步 psycopg 并通过 `asyncio.to_thread` 执行。
- **问题：** 取消等待 `asyncio.to_thread` 的 Task 不会停止已经在线程中执行的 psycopg 查询。若仅让协程超时返回，数据库查询仍会占用连接、CPU/IO，并可能在结束后把状态不明确的连接放回池。设计提到 `statement_timeout`，但没有规定事务作用域、取消控制句柄、pool reset 和晚结果隔离。
- **影响：** deadline、主动取消和高置信分支短路都可能只取消“等待者”，不能释放最昂贵的 DB 资源；并发超时时会形成资源雪崩，直接破坏 P95、连接池稳定性和唯一终态。
- **建议修正：**
  1. 优先选用 `psycopg_pool.AsyncConnectionPool` 与真正可取消的 async 查询；若保留同步连接，必须保存每个查询的连接句柄，并在控制路径调用安全取消，同时以服务端 `statement_timeout` 作为硬兜底。
  2. 每个查询在独立事务中设置本次剩余预算，超时/取消后执行 rollback 和连接健康检查，再决定是否归还池。
  3. Python registry 必须保存 scheduler Task、embedding/httpx Task 和 DB 查询控制对象；FastAPI SSE 断开、Java cancel endpoint 和 deadline 必须汇聚到同一个 cancellation scope。
  4. 验收不仅检查接口返回，还要检查 `pg_stat_activity` 中查询在预算后终止、连接数恢复、上游 HTTP socket 关闭、晚结果未写缓存或覆盖路由。

#### B4. 有界队列与 `isWritable()` 不能构成端到端背压

- **位置：**
  - 设计第 9.5、9.6 节。
  - `python-ai/src/core/model_runtime.py:468-491`：`stream_callback` 为同步函数。
  - `python-ai/src/core/scheduler.py:718-728`：同步 callback 调用同步 `runtime.emit`。
  - `python-ai/src/core/runtime.py:18,32-44`：当前无界 queue 和 `put_nowait`。
  - `java-backend/src/main/java/robot/agent/service/ExecutionService.java:528-529`：lambda `subscribe`。
  - `java-backend/src/main/java/robot/agent/channel/core/UserConnectionManager.java:83-98`：直接 `writeAndFlush`。
- **问题：** Python 若把 `emit` 改为 async，现有同步 callback 无法等待队列空间；Java 的 `subscribe(Consumer)` 默认请求无界数据，事后检查 channel 可写性不能撤回已经请求和解码的 SSE 数据。把事件先写 Redis 也不是背压，只是增加另一个缓冲层。
- **影响：** 慢浏览器仍可能让 Python、Java、Netty 或 Redis 任一层积压；“真实逐流”“有限内存”“慢消费者失败”和“取消传播”无法同时保证。
- **建议修正：**
  1. 把模型流发布接口改为可 `await` 的 async sink，provider 读取循环必须等待 sink；所有 provider 适配器统一这一约束。
  2. Java 使用自定义 `BaseSubscriber` 或等价手动 demand 管理，只在事件已持久化且 channel/本地缓冲低于低水位时 `request(1)`。
  3. 监听 `channelWritabilityChanged` 和 write future，明确每 channel 的事件数、字节数、最长积压时间及终态保留策略。
  4. 用慢消费者测试证明上游读取速率下降、内存稳定、取消后无继续生成，而不是只断言收到了 `slow_consumer` 错误。

### High

#### H1. P0 同时改动过多高风险边界，不能称为“低风险、最小侵入”

- **位置：** 设计第 1、13.1、14 节。
- **问题：** P0 同时包含路由并行、统一 deadline、Java client 异步化、Python HTTP/DB pool、线程隔离、两路 SQL 并行、RRF、真实 embedding、软删除路径、事务后 dispatch、SSE id、v2 字段、TTFT/span、Python 背压、Netty 背压和 Unicode 合并。这些改动跨越事务、线程、连接池、排序、协议和前端状态机，失败模式相互耦合。
- **影响：** 无法定位性能收益或准确率退化来源；feature flag 回滚也难覆盖 schema、任务生命周期和事件顺序变化。
- **建议修正：** 按第 6 节的精简切片拆分，每片只改变一个主要不变量，并给出独立基线、验收和回滚。RRF、v2、完整背压不应与连接复用、伪向量修复放在同一个 P0 发布单元。

#### H2. 多知识库和多条件过滤下，候选扩大不能可靠修复 filter-after-ANN

- **位置：** 设计第 7.6、7.7、8.2、16 节。
- **问题：** pgvector approximate index 在常规模式下先扫描 ANN 候选，再应用 `workspace/kb/status/index_version` 过滤。设计写道“不支持 iterative scan 时通过候选过采样和 SQL 过滤避免候选不足”，但同一条带过滤的 ANN SQL 增大 `LIMIT` 并不能保证索引扫描访问了足够多的未过滤候选；高选择性、多 kb、软删除或旧 generation 比例高时仍可能少于 top_k，甚至错误返回空结果。
- **影响：** 延迟看似改善但 Recall@K、路由置信度和无答案判断退化，违反“不能以准确率换速度”的目标。
- **建议修正：**
  1. 若使用 pgvector 0.8+，明确启用并压测 `hnsw.iterative_scan`，同时规定 `strict_order/relaxed_order`、`max_scan_tuples` 和过滤选择性分桶。
  2. 对高选择性过滤采用 workspace/generation 分区、适当的 partial index，或按 kb 独立 ANN 后在应用层合并。
  3. 候选扩大只能作为校准参数，不能作为正确性保证；验收必须覆盖 0.1%、1%、10%、50% 过滤命中率和多 kb 倾斜数据。

#### H3. 2 秒预算的 deadline 起点和 P95 算法不足以支撑端到端承诺

- **位置：** 设计第 3.2、7.1、7.13 节。
- **问题：**
  - `retrieval_e2e_ms` 从浏览器 send 前开始，但 Java 在身份校验后才生成 `now + max_wait_ms`；若 `max_wait_ms=2000`，再加浏览器到 Java、认证和 Java 到浏览器转发，端到端天然可能超过 2 秒。
  - 把多个阶段各自的 P95 与“400 ms 抖动余量”相加，不等于端到端 P95 的统计证明。
  - P0 只验收 10 万 chunk，而正式容量边界写为 100 万 chunk；在 P1 完成前不能发布 100 万容量下的 2 秒声明。
- **影响：** 即使各组件满足局部 timeout，浏览器指标仍可能稳定超标；容量声明会先于证据。
- **建议修正：**
  1. 服务端 root deadline 在 frame 解码后立即建立，并为返回浏览器预留固定 egress 预算；内部检索预算应小于 2 秒，而不是等于 2 秒。
  2. 浏览器端指标只能由端到端压测验收，阶段预算用于诊断而非数学证明。
  3. 分别发布“10 万/P0”和“100 万/P1”的容量边界，未完成的场景不得沿用同一结论。

#### H4. psycopg 连接数、线程数和 asyncio 边界没有闭合

- **位置：** 设计第 7.3、7.8、13.1 节。
- **问题：** hybrid 每请求需要两个独立 DB 连接。主验收 10 并发时理论上需要 20 个查询连接，但建议初始 pool 上限为 16；50 并发时需求更大。文档称“有界线程池 `asyncio.to_thread`”，但 `to_thread` 使用事件循环默认 executor，除非显式配置，否则并不是该检索服务独享的有界 executor。`psycopg_pool` 还不在当前 requirements 中。
- **影响：** pool pending 和线程等待会进入尾延迟；其他 `to_thread` 工作可能被检索占满；取消时会留下阻塞线程。
- **建议修正：**
  1. 明确选择 async pool，或显式专用 `ThreadPoolExecutor` 加同步 pool，禁止模糊双轨。
  2. 给出 `实例数 × 检索并发 × 每请求连接数` 的容量公式、DB `max_connections` 预算和过载时的 admission control。
  3. pool acquire 也必须受剩余 deadline；连接不足时允许按策略串行或 keyword-only 降级，但要独立记录准确率。

#### H5. “逐 Token”表述不准确，协议实际只能保证上游 delta 的非缓冲转发

- **位置：** 设计第 1、9.5、10 节。
- **问题：** 上游 SSE 的一个 delta 可能包含零个、一个或多个模型 token，且设计又主动做 20–50 ms/64 code point 合并。当前 Python 也只有部分 OpenAI-compatible 协议走流式分支，Java `UnifiedModelService` 仍聚合完整流。
- **影响：** 对外宣称“逐 Token”会形成不可验证承诺；不同 provider 的 TTFT 和流粒度不可比较。
- **建议修正：** 将契约改为“对支持流式的 provider，收到首个非空上游文本 delta 后不等待完整响应，并在合并窗口内转发”。列出 provider 能力矩阵；TTFT 统计首个非空用户可见文本，不声称一 token 一帧。

#### H6. Redis Stream 与 Pub/Sub 的职责及重放切实时算法不明确

- **位置：** 设计第 7.11、9.8、13.3 节。
- **问题：** 文档写“Redis Pub/Sub/Stream 消费把事件送到本地连接实例”，但两者语义不同：Pub/Sub 是瞬时通知，Stream 是持久日志；共享 consumer group 会把消息分发给一个消费者，而不是广播给所有持有连接的 Java 实例。文档也没有定义“先重放、再订阅实时”期间如何避免丢失或重复。
- **影响：** 重连切换点可能丢事件或重复展示；多实例可能只有一个实例收到消息；Pub/Sub 断线期间无法补偿。
- **建议修正：**
  1. 以 Stream 作为唯一事实日志，Pub/Sub 只允许携带“有新 Stream ID”的唤醒提示。
  2. 本地连接实例收到提示后按自身 cursor 从 Stream 读取；不要用共享 consumer group 实现广播。
  3. 定义原子 handoff：记录上界、重放到上界、订阅/轮询后继续按 ID 读取，并用 seq 去重。
  4. Stream 被 trim 且 cursor 过旧时返回明确 `replay.reset` 和文本快照，不能假装连续重放。

#### H7. `retrieval.completed` 对浏览器和 Redis 的数据最小化不足

- **位置：** 设计第 3.2、9.3、9.8、12 节。
- **问题：** 文档仍允许 `retrieval.completed` 下发 citations，正文是否下发“由权限和 UI 需要决定”；`retrieval.started` 还包含 `query_hash`。当前 Python 检索结果本身带完整 `content`，若桥接层误透传，浏览器、Redis Stream、前端日志和重放都会扩大敏感正文暴露面。普通 SHA-256 query hash 对常见短查询可被字典反推。
- **影响：** 增加数据泄露、长期重放暴露和日志扩散风险；该事件对完成路由并非必要。
- **建议修正：**
  1. 把浏览器事件 schema 固定为计数、耗时、cache/partial/degradation 和经过授权的最小引用标识，明确禁止正文、完整 query、prompt、内部 metadata。
  2. `query_hash` 不下发浏览器；服务端如需关联，使用带密钥 HMAC 或 trace 字段。
  3. 若 UI 不展示检索进度，默认不发送该事件；指标可在服务端完成，浏览器端只为真实端到端抽样开启安全事件。

#### H8. 身份认证是重放和多实例协议的前置条件，但实施范围没有覆盖

- **位置：** 设计第 4.5、9.8、12、13 节。
- **问题：** 当前仓库没有可见的 Spring Security/principal 建立链路；CONNECT 只校验客户端自报 `user_id/session_id`，前端也可直接设置 user id。设计虽然指出风险，却未设置独立的认证前置切片，Java 变更清单也没有认证依赖、握手 token 校验或 principal 注入方案。
- **影响：** 在现状上启用 cursor/replay，相当于把持久事件访问控制建立在可伪造身份上，存在跨用户、跨 session 重放风险。
- **建议修正：** 在任何 v2 replay 或共享事件上线前，先完成认证握手、principal→workspace/session 绑定和负向越权测试。若认证由外部网关提供，必须写明可信 header/token、签名校验、直连阻断和部署边界。

#### H9. cache key 有较好方向，但失效与上下文隔离仍不完整

- **位置：** 设计第 7.4、7.5、7.10、12、17 节。
- **问题：**
  - contextual query rewrite 依赖 session 上下文，而结果缓存 key 只列原始规范化 query，没有 rewrite 结果、上下文指纹或“禁止缓存 contextual rewrite”的规则。
  - `permissions_filter_version`、`filters_hash` 和 `index_generation` 没有定义规范化算法、权威存储和原子发布时点。
  - chunk schema 未明确持久化 embedding 模型/修订；同维度换模型时可能在一个索引中混入不同向量空间。
  - provider config hash 必须只覆盖影响 embedding 语义的配置，不能包含密钥，也不能遗漏 input type、dimensions、encoding/preprocessing 等参数。
- **影响：** 可能跨上下文复用错误结果、返回删除/未发布数据、混用向量空间或造成无法解释的 cache miss。
- **建议修正：**
  1. contextual rewrite 默认禁用结果缓存，或把 rewrite model/version、重写后 query hash 和最小上下文版本纳入 key。
  2. generation 只能在新索引完整可用后原子切换；旧 generation 保留到在途请求和回滚窗口结束。
  3. chunk 和查询都必须绑定同一 embedding model revision/index generation，SQL 强制过滤。
  4. 结果缓存保持默认关闭，直到删除、发布、权限变化、模型升级和 outbox 延迟测试全部通过。

#### H10. Java 21 支持虚拟线程，但“有界虚拟线程 executor”和事务边界仍未设计

- **位置：**
  - 设计第 7.2、13.1 节。
  - `java-backend/pom.xml:21`、`java-backend/Dockerfile` 确认编译和运行均为 Java 21。
  - `WorkflowService.java:37` 为类级 `@Transactional`。
- **问题：** Java 版本确实支持虚拟线程，但常用 `newVirtualThreadPerTaskExecutor()` 本身不提供业务并发上限。更重要的是 Spring 事务上下文基于线程绑定，子虚拟线程不会自动继承父线程事务；把 JPA entity 或 lazy state 直接交给并行分支会引入脱离事务和线程安全风险。
- **影响：** 并行路由可能突破下游容量，或在实现时出现事务缺失、连接占用和实体访问异常。
- **建议修正：** 在 fork 前把所需数据物化为不可变 DTO；远程分支不运行在长事务中；需要数据库读取时使用显式短事务。虚拟线程 executor 外加 semaphore/bulkhead 和队列拒绝策略，不能把“虚拟”误当成“无限资源”。

#### H11. 幂等要求缺少数据库唯一约束

- **位置：**
  - 设计第 9.4、9.10、15.1 节。
  - `ExecutionService.java:191-203` 先查询再创建。
  - `Execution.java:22` 的 `client_message_id` 未见唯一约束。
- **问题：** `session_id + client_message_id` 的“查后写”在重试并发下不是原子幂等。ACK 丢失、浏览器重连或多 Java 实例会创建重复 execution。
- **影响：** 重复回答、重复计费、冲突终态和重放重复都可能发生，协议层去重无法修复已经重复执行的副作用。
- **建议修正：** 增加数据库唯一约束 `(session_id, client_message_id)`，创建时处理唯一冲突并返回已有 execution；dispatch record 和唯一终态更新同样采用条件更新/唯一键保证。

#### H12. 客户端 TTFT 与服务端 span 的关联和样本偏差未闭合

- **位置：** 设计第 3.2、10、11、15.5 节。
- **问题：** 浏览器用 `performance.now()` 测端到端是正确的，但它不能与不同主机的服务端单调时钟做绝对时间相减；只能通过 `message_id/trace_id` 关联各自的 duration。只统计成功产生首 delta 的请求还会系统性排除失败、无答案、取消、断线和超时；后台 tab 的事件调度也会抬高观测值。重放到达的历史首 delta不能当实时 TTFT。
- **影响：** 仪表盘可能显示漂亮的 P95，却掩盖“没有 Token 的请求”和客户端样本选择偏差，无法诚实评价 1 秒目标。
- **建议修正：**
  1. 浏览器端只用同一 `performance.now()` 计算端到端 duration；服务端 span 分别记录 duration，通过 trace 关联，不跨时钟相减。
  2. 同时报告 `first_token_observed_rate`、失败/无 Token/取消率和超时上界；这些请求不得静默从成功 P95 中消失。
  3. 实时、replay、后台 tab、断线恢复和确定性节点分桶；记录 `document.visibilityState` 和采样/上传失败率。
  4. 1 秒继续保持非 release gate，并按 provider、模型族、路由类型分组，不发布无法由系统控制的总体保证。

### Medium

#### M1. code point 安全不等于 grapheme cluster 的增量显示完整

- **位置：** 设计第 9.5、15.4 节。
- **问题：** 按 code point 计数可避免拆开 UTF-16 surrogate pair，但仍可能在组合字符、肤色修饰符、ZWJ 家庭 emoji 和区域旗帜序列中间刷新。最终字符串可完全一致，增量 UI 却会短暂显示破碎字形。
- **影响：** 不会必然导致最终文本损坏，但与“emoji 完整性”的用户可见含义不完全一致。
- **建议修正：** 将要求拆成两层：传输层保证 code point/JSON/UTF-8 不损坏且最终文本逐 code point 相等；展示层若要求无破碎字形，coalescer 应保留少量尾部并按 Unicode grapheme boundary 刷新，前端测试同时验证最终文本和增量渲染。

#### M2. absolute epoch deadline 需要时钟偏差和单调时钟转换规则

- **位置：** 设计第 7.1 节。
- **问题：** 跨 Java/Python 使用 `deadline_epoch_ms` 会受主机时钟偏差和 wall clock 跳变影响。
- **影响：** 小偏差会误触发提前超时或超预算执行，压测中表现为难复现的尾部异常。
- **建议修正：** 服务收到 absolute deadline 后立即基于本机当前 wall clock 计算 remaining，并转成单调时钟截止点；规定最大允许偏差、NTP 监控和负 remaining 的立即降级行为。header 与 JSON 不一致时拒绝或采用更早值。

#### M3. Stream 保留上限后的快照恢复协议尚未定义

- **位置：** 设计第 9.8、17 节。
- **问题：** “超过 10,000 事件或 5 MiB 后保留终态和文本快照”没有说明快照事件类型、对应 seq、客户端如何替换已有文本、旧 cursor 如何判定失效。
- **影响：** 长回答或长断线后可能重复拼接、缺字或无法恢复。
- **建议修正：** 定义 `replay.reset`/`message.snapshot` 的版本化 payload、基准 seq 和客户端替换语义；trim 前必须先持久化覆盖该区间的快照。

#### M4. Java 侧 Redis 能力和依赖变更漏列

- **位置：** 设计第 9.8、13.2、13.3、14.1、14.4 节。
- **问题：** 当前 `java-backend/pom.xml` 没有 Redis client/starter，未来文件清单也没有列出 pom 变更，却要求 Java 负责 Stream/PubSub、cursor 和 event store。
- **影响：** 实施范围和工作量低估，无法提前决定同步/Reactive Redis、连接池和故障隔离方式。
- **建议修正：** 把 Java Redis 依赖、配置、连接管理、序列化版本、超时和健康检查纳入 P1/P2 文件及回滚清单。

#### M5. 回滚策略没有覆盖持久状态和双栈序列变化

- **位置：** 设计第 13 节。
- **问题：** “切回 v1/串行路由/exact scan”只描述开关，没有说明 v2 已持久化事件、提前创建的 execution 状态、generation 切换和 Redis cache schema 如何与旧版本共存。
- **影响：** 回滚后可能无法读取新状态、重复 dispatch，或旧客户端接收不完整事件。
- **建议修正：** 每个切片定义向后兼容读路径、数据库 migration expand/contract 顺序、事件 schema version 和回滚后的在途 execution 处理规则。

### Low

#### L1. `ensure_ascii=True` 不是字符完整性缺陷，不应列为 P0 正确性修复

- **位置：**
  - 设计第 4.4、9.5、13.1 节。
  - `python-ai/src/core/events.py:10-12`。
- **问题：** `ensure_ascii=True` 会把 Unicode 转成 JSON 转义，增加中文和 emoji 的载荷字节数、降低人工可读性，但标准 JSON 解析后字符可完整恢复；它本身不会造成乱码，也没有证据表明切换为 `False` 是显著性能优化。
- **影响：** 把非必要序列化偏好混入 P0，会增加变更面并错误归因 Unicode 问题。
- **建议修正：** 将其降为可选优化。正确性验收应基于解析后的字符串和最终 code point 一致性；只有压测证明载荷或 CPU 有收益时再切换，并同时明确 `charset=utf-8`。

## 4. 评审维度结论

| 维度 | 结论 |
| --- | --- |
| 正确性 | 不通过：ANN 维度不可行，预执行检索事件身份冲突，幂等缺少数据库约束。 |
| 可实现性 | 不通过：取消、背压和 Redis 多实例语义尚不能按文档直接实现。 |
| 延时预算 | 有条件：方向合理，但 deadline 起点、容量分阶段和端到端 P95 证明需重写。 |
| 准确率 | 不通过：4096 维 ANN 方案和 filter-after-ANN 可能造成不可控召回损失。 |
| 并发/资源 | 不通过：双连接查询、线程池、pool 和过载控制没有闭合。 |
| 协议语义 | 不通过：retrieval 事件时序、真实流粒度、backpressure 和 replay handoff 不完整。 |
| 故障恢复 | 有条件：唯一终态方向正确，但取消、trim/reset、owner 和晚事件处理需明确。 |
| 兼容性 | 有条件：v1/v2 双栈方向可行，持久状态回滚和 provider 流能力矩阵缺失。 |
| 安全 | 不通过：principal 仍是未实现的前置条件，retrieval 事件数据最小化不足。 |
| 可观测性 | 有条件：span 设计较完整，客户端/服务端时钟关联和无 Token 样本偏差需修正。 |
| 测试与回滚 | 有条件：测试矩阵覆盖面好，但需加入真实取消、filter selectivity、demand 和状态迁移验证。 |

## 5. 重新送审前必须修正

1. 选定可支持当前向量维度的 ANN 方案，或明确全量迁移到可索引维度；固定并校验 pgvector 版本。
2. 统一 message、execution、retrieval 事件的创建顺序、序列空间、ACK 和 Stream key。
3. 给出同步/异步 psycopg 的唯一实现选择，以及 DB 查询可验证取消、statement timeout、pool reset 和 late-result 隔离。
4. 给出 Python async sink 到 Java manual demand 再到 Netty writability 的完整背压链路。
5. 将 P0 拆小，不把检索正确性、连接优化、协议 v2、背压和重放放在同一发布单元。
6. 明确 filtered HNSW 的 iterative scan/分区/按 kb 查询策略，候选扩大不得作为正确性保证。
7. 重写 2 秒 budget：内部 deadline 必须给浏览器入口和返回预留预算，100 万容量声明只能在 P1 全矩阵通过后发布。
8. 把认证 principal 设为 replay 前置门槛，并严格限制 `retrieval.*` 浏览器 payload。
9. 补齐 result cache 的 contextual rewrite、权限指纹、embedding revision 和 generation 原子切换规则。
10. 增加数据库幂等唯一约束、terminal CAS 和回滚兼容方案。

## 6. 建议的精简实施切片

### 切片 0：基线与硬约束

- 建立黄金集、exact scan 基线、浏览器端真实 retrieval/TTFT 观测。
- 固定 pgvector 版本，完成 4096 维 ANN feasibility spike。
- 不改协议、不启用结果缓存、不承诺 100 万容量。

### 切片 1：检索正确性

- `KnowledgeNode` 和 API 统一真实 query embedding。
- 生产路径彻底禁止 SHA-256 伪向量。
- Java 只批量校验候选 docId，保留现有 exact/keyword 查询和融合。
- 验收准确率、软删除和模型 revision 一致性。

### 切片 2：连接复用与 event loop 隔离

- Python 复用 httpx client。
- 只选一种 DB 实现：AsyncConnectionPool，或专用有界 executor 加同步 pool。
- 先保持向量/关键词串行，验证连接、超时、取消和 event loop lag。

### 切片 3：检索内部并行

- 使用两条独立 DB 连接并行 vector/keyword。
- 加入 pool admission control、统一 remaining deadline 和服务端 statement timeout。
- 仅在 shadow 中引入 RRF，分别测延时和准确率。

### 切片 4：路由并行与 intake 状态机

- 先创建具备数据库唯一幂等键的 message/execution `ROUTING` 记录。
- 提交后 ACK；intent/knowledge 并行；路由结果条件更新。
- dispatch 不等待浏览器 ACK flush，但通过单一事件序列保证 ACK 在 execution 事件之前展示。

### 切片 5：单实例真实流、背压与取消

- Python async sink、有界字节/事件队列。
- Java manual demand、Netty 水位和 slow consumer 策略。
- 主动取消、SSE 断开、httpx、DB query、唯一终态全链路验证。
- 暂不做 Redis replay 和多实例 owner。

### 切片 6：ANN 与 cache

- 按已批准的 4096 维方案建设 ANN。
- 解决 filtered ANN 召回，完成 1 万/10 万/100 万和过滤选择性矩阵。
- 先启用 embedding cache；结果缓存继续默认关闭，直到 generation/权限/上下文失效测试通过。

### 切片 7：认证、持久事件与重放

- 先完成 principal、session/execution 归属。
- Stream 作为事实日志，Pub/Sub 只作提示。
- 实现 replay/live handoff、trim reset、snapshot、seq 去重和 v1/v2 双栈。

### 切片 8：多实例 owner

- 最后实现 execution owner lease、command channel、滚动重启恢复和跨实例 cancel。
- 在单实例协议语义和资源边界稳定前，不扩展 stateful execution worker。

## 7. 对目标的最终判断

- **显著降低知识检索延时：** 连接复用、event loop 隔离、候选级状态检查和并行检索有明确收益，方向成立。
- **在明确条件下接近/达到 P95≤2 秒：** 有可能，但当前设计尚不能证明；必须先解决 4096 维 ANN、过滤召回、内部 deadline 和容量分阶段问题。
- **保证准确率：** 当前版本不能保证，主要风险是不可实施的 HNSW 和 filter-after-ANN 候选不足。
- **真实流式：** 可以做到上游文本 delta 的非缓冲增量转发，但不能诚实承诺一 token 一帧；当前背压接口仍需重构。
- **取消、重连和故障恢复：** 设计意图正确，执行机制未闭合，尤其是同步 DB 取消、Stream/PubSub handoff 和预执行事件身份。
- **首个非空回答 Token P95≤1 秒：** 文档将其作为非阻断目标是正确的，应保持这一政策；不得因用户不把低延迟模型设为门槛而在其他章节暗示必达。最终发布应同时展示按模型/路由分桶的 TTFT、无 Token 率和系统可控前置耗时。

## 8. 外部语义参考

- pgvector HNSW 类型维度、过滤与 iterative scan：<https://github.com/pgvector/pgvector>
- Psycopg 连接取消 API：<https://www.psycopg.org/psycopg3/docs/api/connections.html>
- Reactor 手动 demand/backpressure：<https://projectreactor.io/docs/core/release/reference/coreFeatures/simple-ways-to-create-a-flux-or-mono-and-subscribe-to-it.html>
- Redis Pub/Sub 投递语义：<https://redis.io/docs/latest/develop/pubsub/>
- Redis Streams：<https://redis.io/docs/latest/develop/data-types/streams/>
