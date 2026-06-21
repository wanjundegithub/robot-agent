# 机器人配置与绑定设计方案

日期：`2026-06-21`
适用项目：`robot-agent`

## 目标

新增“机器人配置”作为用户聊天的唯一业务入口。机器人配置同时绑定可用工作流和可检索知识空间，但工作流链路与知识空间链路保持独立：

- 工作流用于意图识别后进入业务流程。
- 知识空间用于知识检索和知识问答。
- 用户与机器人交互时必须携带已发布机器人配置。
- 知识检索必须限定在该机器人绑定的知识空间内，不能由前端任意指定检索范围。
- 知识命中后通过现有 `message.delta` 通道流式输出，首帧目标小于 1 秒。

本方案选择“方案 B：机器人配置作为唯一交互入口”。

## 背景

当前聊天入口主要围绕会话、工作流和已存在的知识绑定服务展开。知识检索链路已有 `KnowledgeService.searchKnowledge`、`KnowledgeRouteDecisionService` 和 `ExecutionService` 中的 `knowledge_answer_streaming` 输出能力，但知识空间与机器人交互入口没有稳定绑定关系。

这会导致两个问题：

1. 用户和机器人交互时，系统无法明确知道应该检索哪些知识空间。
2. 如果允许前端临时传入知识空间，容易出现越权检索、跨空间误检索和运行结果不可复现。

因此需要新增独立的机器人配置概念，把“用户正在和哪个机器人交互”作为运行时上下文的根。

## 非目标

- 不把知识空间绑定到工作流。
- 不让工作流配置承担机器人入口职责。
- 不在首版实现复杂权限矩阵、审批流或多级机器人市场。
- 不引入新的向量数据库、消息队列或检索中间件。
- 不重写现有知识中心、工作流设计器和流式消息通道。

## 产品模型

### 机器人配置

机器人配置是用户聊天入口，承载机器人名称、编码、开场白、状态、绑定关系和路由策略。

建议字段：

```text
robot_config
- id
- workspace_id
- robot_code
- name
- description
- avatar
- opening_message
- status
- default_model_code
- route_strategy
- created_by
- created_at
- updated_at
```

`status` 建议取值：

```text
DRAFT
PUBLISHED
DISABLED
ARCHIVED
```

聊天入口只允许使用 `PUBLISHED` 状态的机器人。

### 机器人绑定

机器人绑定独立记录机器人可使用的工作流和知识空间。

建议统一表：

```text
robot_binding
- id
- workspace_id
- robot_code
- binding_type
- target_code
- enabled
- binding_version
- created_at
- updated_at
```

`binding_type` 取值：

```text
WORKFLOW
KNOWLEDGE_SPACE
```

其中：

- `WORKFLOW.target_code` 对应 `workflow.workflow_code`。
- `KNOWLEDGE_SPACE.target_code` 对应 `knowledge_base.kb_code`。

机器人发布时生成新的 `binding_version`，聊天运行时记录使用的机器人版本和绑定版本，便于排查历史会话。

## 页面交互设计

已生成一版评审原型：

- HTML 原型：`output/prototypes/robot-config-prototype.html`
- 截图：`output/playwright/robot-config-prototype.png`

页面结构：

1. 左侧机器人列表
   - 展示机器人名称、编码、状态、绑定工作流数量、绑定知识空间数量。
   - 支持搜索和新增机器人。

2. 中间配置主区
   - 基础信息：名称、编码、状态、默认模型、开场白。
   - 工作流绑定：选择该机器人可进入的业务流程。
   - 知识空间绑定：选择该机器人可检索的知识空间。
   - 路由策略：配置意图阈值、知识阈值、首帧目标和 Top K。
   - 发布操作：保存草稿、保存并发布。

3. 右侧运行预览
   - 展示运行链路：选择机器人、加载上下文、并行路由、输出结果。
   - 展示聊天态示例，强调 `robot_code` 和知识流式输出。

前端关键约束：

- 聊天页必须先选择机器人。
- WebSocket `CONNECT` 和消息发送 payload 都应携带 `robot_code`。
- 如果没有机器人配置，发送按钮应禁用或引导用户选择机器人。
- 绑定知识空间为空时，可以聊天，但知识检索链路必须短路，并在配置页显示“未绑定知识空间，无法检索知识”。
- 绑定工作流为空时，可以做知识问答，但意图识别链路必须短路。

## 聊天运行流程

```text
用户发送消息
  -> 校验 robot_code
  -> 加载机器人配置
  -> 校验机器人状态为 PUBLISHED
  -> 加载机器人绑定的 workflowCodes 和 kbCodes
  -> 并行执行：
       A. 在 workflowCodes 范围内做意图识别
       B. 在 kbCodes 范围内做知识检索
  -> 聚合决策
       高置信意图 -> 进入绑定工作流
       高置信知识 -> 流式输出知识答案
       两边都不确定 -> 澄清
       缺少必要绑定 -> 返回配置提示
```

## 路由规则

路由输入必须包含：

```json
{
  "robot_code": "robot_after_sale",
  "session_id": "session_001",
  "user_id": "demo-user",
  "content": "这个产品保修期多久？"
}
```

后端根据 `robot_code` 解析运行上下文：

```json
{
  "robotCode": "robot_after_sale",
  "bindingVersion": 12,
  "workflowCodes": ["after_sale_ticket", "return_exchange"],
  "kbCodes": ["kb_after_sale_product", "kb_warranty_policy"],
  "routeStrategy": "PARALLEL_AGGREGATE"
}
```

聚合建议沿用现有阈值思想：

```text
intent_primary_threshold = 0.75
knowledge_primary_threshold = 0.65
intent_clarify_threshold = 0.55
knowledge_clarify_threshold = 0.55
```

判定顺序：

1. 如果 `intentConfidence >= intent_primary_threshold`，进入工作流。
2. 如果意图未达主阈值，且 `knowledgeBestScore >= knowledge_primary_threshold`，输出知识答案。
3. 如果两边都处于澄清区间，返回澄清问题。
4. 如果两边都低于澄清阈值，返回兜底问题。

工作流候选只允许来自机器人绑定的 `workflowCodes`。
知识检索只允许来自机器人绑定的 `kbCodes`。

## 强制知识空间边界

聊天知识检索不得信任前端传入的 `kbCodes`。

正确做法：

1. 前端只传 `robot_code`。
2. 后端通过 `RobotConfigService` 获取机器人绑定的知识空间。
3. 后端构造 `KnowledgeSearchRequest.kbCodes`。
4. Python AI 检索接口只接收后端传入的 `kbCodes` 并在 pgvector 查询中过滤。

如果机器人未绑定知识空间：

- 不调用 `KnowledgeService.searchKnowledge`。
- 路由日志记录 `knowledge_skip_reason=no_robot_bound_knowledge`。
- 如果用户问题明显是知识类问题，返回“当前机器人未绑定知识空间，无法检索知识，请先在机器人配置中绑定知识空间。”

## 流式输出

知识命中后继续使用现有 WebSocket `message.delta` 通道。

首帧策略：

1. 后端确认 `robot_code` 有效后即可准备轻量执行标识。
2. 如果最终路由为知识答案，检索命中后立即发送首帧：

```json
{
  "event_type": "message.delta",
  "execution_id": "knowledge_xxx",
  "session_id": "session_001",
  "content": "",
  "is_complete": false
}
```

3. 后续按自然段或固定长度切片发送答案。
4. 最后一帧：

```json
{
  "event_type": "message.delta",
  "execution_id": "knowledge_xxx",
  "session_id": "session_001",
  "content": "",
  "is_complete": true
}
```

首帧小于 1 秒的实现建议：

- 机器人配置和绑定关系使用短 TTL 缓存。
- 聊天路由时禁止全量查询所有工作流和知识空间。
- 意图识别和知识检索并行启动。
- 知识检索请求使用机器人绑定的 `kbCodes` 作为强过滤条件。
- 检索阶段保留 `generateAnswer=false` 的快速路由能力；答案生成可作为后续独立优化。

## 后端改造点

### 新增模型与服务

新增：

```text
RobotConfig
RobotBinding
RobotConfigRepository
RobotBindingRepository
RobotConfigService
RobotRuntimeContext
RobotConfigController
```

`RobotRuntimeContext` 建议结构：

```java
public record RobotRuntimeContext(
        String robotCode,
        Long workspaceId,
        Integer bindingVersion,
        List<String> workflowCodes,
        List<String> kbCodes,
        String routeStrategy
) {}
```

### 请求模型

`SendMessageRequest` 增加：

```java
@JsonProperty("robot_code")
private String robotCode;
```

`UserChannelContext` 增加 `robotCode`，`CONNECT` payload 支持读取：

```json
{
  "robot_code": "robot_after_sale",
  "session_id": "session_001",
  "user_id": "demo-user"
}
```

### 路由服务

`ExecutionService.startExecution` 负责：

1. 校验 `robotCode`。
2. 加载 `RobotRuntimeContext`。
3. 将上下文传入路由服务。

`WorkflowService.routeMessage` 调整为：

```java
routeMessage(content, activeExecution, sessionId, userId, robotRuntimeContext)
```

路由服务内部：

- 工作流版本只查询机器人绑定的 `workflowCodes`。
- 知识检索只使用 `robotRuntimeContext.kbCodes()`。
- 旧的 `KnowledgeBindingScope.SESSION` 和 `KnowledgeBindingScope.WORKFLOW` 不作为机器人聊天主链路依据，可以保留用于兼容或后台调试。

## 接口设计

机器人配置：

```http
GET /api/robots
POST /api/robots
GET /api/robots/{robotCode}
PUT /api/robots/{robotCode}
DELETE /api/robots/{robotCode}
POST /api/robots/{robotCode}/publish
```

机器人绑定：

```http
GET /api/robots/{robotCode}/bindings
PUT /api/robots/{robotCode}/bindings
```

绑定请求示例：

```json
{
  "workflow_codes": ["after_sale_ticket", "return_exchange"],
  "kb_codes": ["kb_after_sale_product", "kb_warranty_policy"]
}
```

聊天请求：

```http
POST /api/sessions/{sessionId}/messages
```

```json
{
  "robot_code": "robot_after_sale",
  "message_id": "msg_001",
  "content": "这个产品保修期多久？",
  "user_id": "demo-user"
}
```

## 前端改造点

新增机器人配置页面：

- 机器人列表
- 机器人基础信息表单
- 工作流绑定选择器
- 知识空间绑定选择器
- 路由策略配置
- 运行预览
- 发布入口

聊天页改造：

- 新增机器人选择入口。
- WebSocket connect payload 携带 `robot_code`。
- 发送消息 payload 携带 `robot_code`。
- 未选择机器人时禁用发送。
- 机器人无知识空间绑定时，知识类提示应明确说明无法检索。

## 错误处理

建议新增状态：

```text
robot_required
robot_not_found
robot_disabled
robot_no_workflow_binding
robot_no_knowledge_binding
robot_binding_invalid
```

行为：

- `robot_required`：不进入路由。
- `robot_not_found` / `robot_disabled`：不进入路由。
- `robot_no_workflow_binding`：意图链路短路，但允许知识链路。
- `robot_no_knowledge_binding`：知识链路短路，但允许意图链路。
- 两类绑定都为空：返回配置错误，不进入业务处理。

## 可观测性

建议记录：

- `robot_code`
- `robot_binding_version`
- `workflow_binding_count`
- `knowledge_binding_count`
- `final_route`
- `intent_confidence`
- `knowledge_best_score`
- `first_delta_latency_ms`
- `knowledge_search_latency_ms`
- `route_total_latency_ms`
- `knowledge_skip_reason`

这些字段应进入日志和后续运营指标。

## 测试策略

后端单元测试：

- 未传 `robot_code` 时返回 `robot_required`。
- 机器人不存在时不进入路由。
- 机器人停用时不进入路由。
- 机器人只绑定工作流时不调用知识检索。
- 机器人只绑定知识空间时不做工作流意图候选。
- 机器人同时绑定工作流和知识空间时按阈值聚合。
- 知识检索请求的 `kbCodes` 只来自机器人绑定。
- 前端传入任意 `kbCodes` 不影响聊天检索范围。
- 知识命中时通过 `message.delta` 输出首帧、内容帧和完成帧。

前端测试：

- 机器人配置页可新增、编辑、发布。
- 工作流绑定和知识空间绑定互相独立。
- 聊天未选择机器人时不能发送。
- 选择机器人后 WebSocket 和消息请求都带 `robot_code`。
- 知识命中消息能按 delta 合并展示。

## 验收标准

1. 用户聊天必须绑定机器人配置。
2. 机器人配置可以独立选择工作流和知识空间。
3. 知识空间与工作流不再互相绑定。
4. 意图识别只在机器人绑定的工作流范围内执行。
5. 知识检索只在机器人绑定的知识空间范围内执行。
6. 未绑定知识空间时，知识检索不会发生。
7. 知识命中后通过 `message.delta` 流式输出。
8. 常规知识命中链路首帧目标小于 1 秒。
9. 路由日志可以看到机器人编码、绑定版本、最终路由和耗时。
10. 页面原型中的机器人配置、绑定和运行预览交互可以落地到前端实现。

