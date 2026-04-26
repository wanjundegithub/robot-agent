# 流程设计编排页重构设计

**日期：** 2026-04-26

## 目标

本次重构只处理“流程设计编排页”本身，不把运行调试能力继续堆回设计页。

本次方案的核心目标如下：

1. 以当前 `design/工作流设计.md` 的想法为基础，重构为一套前端、Java 后端、Python 执行器一致的正式工作流模型。
2. 将现有“能画但不一定能稳定执行”的流程编排页，收敛为“可保存、可校验、可发布、可在聊天页真实联调”的设计器。
3. 明确主流程与子流程的关系：子流程不独立存在，属于同一个工作流定义中的内嵌子图。
4. 支持“无条件连线 + LLM 决策下一跳”的多分支执行模式，但保证后端可校验、运行时可回放。
5. 保证全链路 UTF-8 编码安全，不允许前端用 mock 数据替代真实后端和 Python 执行链路。

## 范围

### 纳入本次设计

- 流程设计编排页的信息架构与交互重构。
- 工作流定义模型重构。
- 工作流草稿保存、校验、发布的数据结构和接口调整。
- Java 后端的定义校验、运行时快照构建、旧定义兼容迁移策略。
- Python 执行器的节点模型、子图执行、LLM 路由决策、函数节点执行模型。
- 聊天页联调入口和联调约束。
- 全链路 UTF-8、防乱码、真实接口联调和测试策略。
- 后续实施时的前端 / Java 后端 / Python agent 拆分边界。

### 明确不纳入本次设计

- 不在流程设计页内实现完整运行调试台。
- 不在流程设计页内实现断点调试、节点单步执行、变量实时监控面板。
- 不支持子流程独立版本管理界面。
- 不允许仅靠前端本地 mock 数据演示流程执行结果。
- 不允许在函数处理节点中直接书写任意 Python 脚本或任意远程调用脚本。

## 现状问题

### 1. 当前三层节点语义不一致

当前实现里：

- 前端画布节点类型是 `start / coordinate / sub_agent / tool / message / end`
- Java 后端校验围绕上述类型做了一层简化判断
- Python 执行器真实运行的节点类型却主要是 `start / llm / condition / form / tool / subflow / knowledge / end`

这意味着当前“设计器里配置的节点”与“Python 真正能执行的节点”不是同一套模型，设计页更像一个半成品编辑器，而不是可执行编排器。

### 2. 当前工具节点定义与校验要求不一致

前端当前保存 `tool` 节点时主要写入：

- `group_id`
- `capability_code`
- `capability_version`
- `payload_mapping`

但 Java 后端校验 `capability` 调用时还要求：

- `group_snapshot_version`
- `capability_type`

两边字段不一致，导致设计器生成的数据结构并不能稳定满足校验与运行时装配。

### 3. 当前设计页没有正式的子图模型

目前前端只有单一 React Flow 画布，没有“主流程 + 内嵌子流程视图”的正式定义结构。用户希望点击子代理节点进入子流程画布，但当前定义结构没有 `subGraphs` 的概念，后端和 Python 也没有围绕这个概念设计。

### 4. 当前多分支路由语义不清

你已经确认：

- 连线不携带条件
- 节点可以有多条出边
- 实际走哪条由大模型判断

但当前系统没有一个正式约束去限制“谁可以选下一跳、怎么选、怎么校验返回的目标节点是否合法”，这会导致执行不可预测、问题难复盘。

### 5. 当前“子代理”和“子流程”概念容易混乱

如果把子流程当成独立工作流，会带来版本漂移、运行快照不一致、回放困难等问题；如果把子流程做成主图里的一块视觉区域，又无法支持“点击进入子流程画布”的清晰交互。

因此需要一个中间模型：同一工作流内部的内嵌子图。

## 已确认约束

以下约束已作为本设计的正式前提：

1. 流程页面只做设计编排，不做调试执行页。
2. 联调测试必须在聊天页面进行交互。
3. 节点出边可以有多条，但连线本身不携带条件。
4. 真正的下一跳只能由协调者节点或子代理节点产出，并且必须返回明确的 `targetNodeId`。
5. 子代理流程内不允许再包含协调者节点。
6. 函数处理节点只做本地确定性处理，不调用外部 API，不调用外部能力。
7. 工具节点才负责 capability / API / 外部工具调用。
8. 子流程不独立存在，属于同一工作流中的内嵌子图。
9. 子流程不做独立版本；工作流发布时整体固化为一个快照。
10. 主流程点击子代理节点后进入子流程画布，使用面包屑返回主流程。

## 方案比较

### 方案 A：维持单画布单图结构，局部修补

做法：

- 继续使用单一画布
- 在单图中新增更多节点文案和字段
- 不引入正式子图模型

优点：

- 表面改动较少

缺点：

- 无法满足“点击进入子流程画布”
- 多层流程会快速失控
- 运行时仍然难以表达主图和子图边界

### 方案 B：主图 + 内嵌子图模型

做法：

- 一个工作流定义包含 `mainGraph` 和 `subGraphs`
- 子代理节点通过 `subgraphId` 引用当前工作流内部子图
- 页面支持进入子流程画布、面包屑返回
- 发布时把整份定义整体固化为一个工作流快照

优点：

- 满足“同一工作流、进入子流程画布、无子流程独立版本”三项要求
- 前端交互清晰
- 后端与 Python 可围绕统一定义进行校验和执行

缺点：

- 需要同时改前端定义、Java 校验、Python 执行器

### 方案 C：把子流程做成右侧配置展开，不切换画布

优点：

- 对现有 React Flow 改动较小

缺点：

- 子流程一复杂就无法阅读
- 不符合“点击进入子流程画布”的要求

### 结论

采用方案 B。

## 总体设计

### 一、工作流领域模型

新版工作流定义统一为“一个工作流 + 一个主图 + 多个内嵌子图”的结构。

建议的定义结构如下：

```json
{
  "schema_version": "workflow-designer/v2",
  "workflow_code": "travel_assistant",
  "workflow_name": "差旅协作助手",
  "description": "主流程和子流程统一定义在同一快照中",
  "main_graph_id": "main",
  "graphs": {
    "main": {
      "graph_id": "main",
      "graph_type": "main",
      "name": "主流程",
      "entry_node_id": "start_main",
      "nodes": {},
      "edges": []
    },
    "sub_booking": {
      "graph_id": "sub_booking",
      "graph_type": "subflow",
      "name": "订票子流程",
      "entry_node_id": "start_sub_booking",
      "nodes": {},
      "edges": []
    }
  },
  "variables": {
    "global": [],
    "temporary": []
  },
  "model_bindings": {
    "routing_profile_code": "intent-router-v2",
    "default_model_profile_code": "general-chat-v1"
  },
  "editor_meta": {
    "layout_engine": "reactflow",
    "last_open_graph_id": "main",
    "graph_viewports": {}
  }
}
```

#### 关键设计点

1. `graphs` 是整个工作流内部所有图的注册表。
2. `main_graph_id` 永远指向主流程图。
3. `subflow` 只是 `graph_type`，不是独立工作流，不单独发布。
4. `schema_version` 必须显式保存，用于后续兼容旧定义迁移。

### 二、正式节点模型

新版设计器正式支持 7 种节点：

1. `start`
2. `coordinator`
3. `sub_agent`
4. `function`
5. `tool`
6. `message`
7. `end`

#### 命名纠正

当前前端与 Java 使用 `coordinate`，这是一个不准确的命名。  
新版定义统一采用 `coordinator` 作为正式保存值。

为兼容已有旧版本：

- 读取旧定义时允许把 `coordinate` 迁移为 `coordinator`
- 新版设计器保存时不再写入 `coordinate`

#### 节点职责

##### 1. start

- 负责声明本图入口
- 可配置输入变量提取说明
- 不做多分支决策

##### 2. coordinator

- 仅允许出现在主图
- 负责根据共享内存、用户意图、候选出边决定下一跳
- 当存在多条出边时，必须返回明确的 `targetNodeId`
- 不直接调用工具

##### 3. sub_agent

- 负责进入当前工作流内部某个 `subgraphId`
- 可以配置：
  - `subgraph_id`
  - `input_mapping`
  - `output_mapping`
  - `system_prompt`
- 子图内部禁止再次出现 `coordinator`

##### 4. function

- 只做本地确定性处理
- 不允许发 HTTP
- 不允许直接调用 capability、MCP、Skill 或外部工具
- 推荐采用声明式操作，而不是用户写任意脚本

##### 5. tool

- 专门负责 capability / API / 外部工具调用
- 调用前由 Java 完成能力解析和运行时参数装配
- Python 执行器只消费已解析后的正式工具定义

##### 6. message

- 负责固定消息输出、验证提示、阶段性说明
- 不承担路由职责

##### 7. end

- 负责声明当前图输出变量
- 主图 `end` 表示整个工作流结束
- 子图 `end` 表示返回父级 `sub_agent`

### 三、连线与分支语义

#### 核心规则

1. 连线只表达“可到达关系”，不表达条件。
2. `edge` 数据结构不保存 `condition`、`label`、`branch_type`。
3. 一个节点可以有 0 到多条出边。

#### 下一跳决策规则

1. 节点只有 1 条出边时，系统自动进入唯一下一跳。
2. 节点有多条出边时：
   - 若节点类型是 `coordinator` 或 `sub_agent`，由 LLM 返回 `targetNodeId`
   - 若节点类型不是 `coordinator` 或 `sub_agent`，校验时报错
3. LLM 返回的 `targetNodeId` 必须属于当前节点的直接出边集合，否则执行失败并记录结构化错误。

#### 这样设计的原因

如果把“所有多出边节点都能靠 LLM 自由跳转”，流程会不可控；  
如果把所有分支都做成显式条件连线，又会破坏你已经确认的“连线不带条件”的要求。

因此本设计采用折中方案：

- 连线保持简单
- 只有特定节点有决策权
- 决策结果必须落在合法候选集内

### 四、子流程模型

#### 子流程不是独立工作流

子流程属于当前工作流内部 `graphs` 的一个 `subflow` 图，不单独存储为另外一条工作流记录，也不允许独立发布。

#### 子流程不允许独立版本

用户侧不提供子流程版本概念。  
发布工作流时，主图和全部子图一起固化为一个整体快照。

#### 子流程进入方式

- 主图中点击 `sub_agent` 节点
- 前端进入对应 `subgraphId` 的子流程画布
- 顶部显示面包屑：`工作流名称 / 主流程 / 子流程名称`
- 点击面包屑返回主流程

#### 子流程结构限制

子流程允许包含：

- `start`
- `sub_agent`
- `function`
- `tool`
- `message`
- `end`

子流程禁止包含：

- `coordinator`

#### 这样限制的原因

如果子流程里继续允许协调者节点，会引入“协调者的协调者”这种嵌套决策结构，前端虽然能画，执行和回放会变得极难维护，这一版必须禁止。

### 五、变量模型

变量继续保留“全局变量”和“临时变量”两类，但需要正式化。

```json
{
  "variables": {
    "global": [
      {
        "id": "var_user_intent",
        "name": "user_intent",
        "type": "string",
        "description": "用户识别后的主意图"
      }
    ],
    "temporary": [
      {
        "id": "tmp_candidate_ticket",
        "name": "candidate_ticket",
        "type": "object",
        "description": "子流程内部候选票据对象"
      }
    ]
  }
}
```

#### 变量使用规则

1. 全局变量在主图与全部子图可见。
2. 临时变量默认在当前图上下文中可见。
3. 子代理节点通过 `input_mapping / output_mapping` 明确读写边界。
4. `function` 节点不得隐式写入未声明变量。
5. `end` 节点只能输出已声明变量。

### 六、函数处理节点设计

函数处理节点必须保持“确定性、可回放、可校验”。

#### 不采用自由脚本模式

本设计明确不建议在画布中允许用户直接写 Python 或 JavaScript 脚本。原因：

1. 安全边界差
2. 无法稳定校验
3. 回放时难以保证一致
4. 与“设计器配置化”目标冲突

#### 推荐采用声明式操作 DSL

函数处理节点配置建议采用固定 `operation_type`：

- `assign`
- `template`
- `extract`
- `transform`
- `filter`
- `merge`
- `pick`

示例：

```json
{
  "operation_type": "template",
  "inputs": {
    "city": "$global.departure_city",
    "date": "$global.departure_date"
  },
  "template": "请查询 {{city}} 在 {{date}} 的航班",
  "outputs": {
    "search_prompt": "$temp.search_prompt"
  }
}
```

#### Java 与 Python 分工

- Java 负责结构校验：字段是否齐全、引用变量是否存在、操作类型是否合法
- Python 负责真正执行该 DSL，并写回执行上下文

### 七、工具节点设计

工具节点与函数节点必须彻底分离。

#### 正式字段建议

```json
{
  "invoke_type": "capability",
  "group_id": 12,
  "group_snapshot_version": "v20260426",
  "capability_code": "flight_search_api",
  "capability_version": "v20260426",
  "capability_type": "API",
  "payload_mapping": {
    "departure_city": "$global.departure_city",
    "arrival_city": "$global.arrival_city"
  }
}
```

#### 关键约束

1. 设计器保存工具节点时必须保存运行时需要的完整字段，不能只存部分字段。
2. Java 在发布或执行前要把 capability 解析为 Python 可直接执行的正式工具定义。
3. Python 收到 `invoke_type=capability` 的未解析节点时应直接报错，避免运行时隐式猜测。

### 八、执行语义

#### 1. 统一执行上下文

Python 执行器需要在 `ExecutionContext` 之上补充图级上下文：

- `current_graph_id`
- `graph_stack`
- `parent_node_stack`
- `available_targets`
- `node_input_snapshot`
- `node_output_snapshot`

#### 2. coordinator 执行

输入：

- 当前节点 prompt
- 当前共享内存
- 当前图的候选下一跳列表
- 用户本轮输入

输出：

```json
{
  "targetNodeId": "tool_search_flights",
  "reason": "用户已经明确提供出发地、目的地和日期，进入查询分支"
}
```

校验：

- `targetNodeId` 必须属于当前节点直接出边
- 不允许返回空值

#### 3. sub_agent 执行

步骤：

1. 解析 `input_mapping`
2. 将映射后的输入写入子图上下文
3. 进入子图 `entry_node_id`
4. 子图运行直到 `end`
5. 应用 `output_mapping`
6. 回到父图

#### 4. end 执行

- 如果当前图是主图，结束整个工作流
- 如果当前图是子图，结束子图并返回父节点

### 九、前端页面设计

#### 页面布局

建议保留现有大体左右结构，但重构为四个明确区域：

1. 顶部操作栏
2. 左侧流程导航栏
3. 中央画布区
4. 右侧节点与工作流属性编辑器

#### 1. 顶部操作栏

建议包含：

- 工作流名称
- 当前视图面包屑
- 校验按钮
- 保存草稿按钮
- 发布按钮
- 跳转聊天页联调按钮
- 保存状态 / 当前发布版本提示

#### 2. 左侧流程导航栏

建议包含两部分：

- 主流程入口
- 子流程列表

功能：

- 点击主流程回到 `main`
- 点击某个子流程进入对应画布
- 点击“新增子流程”创建空白子图
- 在导航树中显示每个图的节点数量与校验状态

#### 3. 中央画布区

继续使用 React Flow，但改为“单次只显示当前图”：

- 进入主图时只显示主图节点
- 进入子图时只显示该子图节点
- 切换图时保留各自 viewport

#### 4. 右侧属性面板

分两种状态：

- 未选中节点时：显示当前图信息、图级校验摘要、图描述
- 选中节点时：显示节点类型专属编辑表单

#### 5. 节点创建方式

保留顶部快捷按钮，但建议按语义分组：

- 流程节点：开始、结束、消息
- 决策节点：协调者、子代理
- 执行节点：函数、工具

#### 6. 子代理导航交互

点击 `sub_agent` 节点时提供两个动作：

- `进入子流程`
- `编辑映射`

其中“进入子流程”是一级主操作。

### 十、前端保存结构

前端不应再临时拼装“节点列表 + transitions 的半运行时结构”，而应直接围绕新版 `graphs` 模型维护本地状态。

建议前端状态中心拆为：

- `workflowMetaState`
- `graphRegistryState`
- `variableRegistryState`
- `editorViewportState`
- `validationState`

#### 关键前端改动

1. `Orchestrator.tsx` 从单画布状态升级为“当前 graphId + graph registry”状态模型。
2. `hydrateWorkflowSelection()` 需要兼容旧版 definition 并迁移到新版内存结构。
3. `normalizeNodeConfig()` / `denormalizeNodeConfig()` 需要支持 `function` 与 `subgraphId`。
4. 当前 `coordinate` 节点模板需要迁移为 `coordinator`。
5. 前端保存 draft 时不再构造旧版 `transitions: { nodeId: targetId }` 结构，而应保存显式 `edges` 列表。

## Java 后端设计

### 一、定义存储策略

仍然沿用现有：

- `Workflow`
- `WorkflowVersion`

不新增“子流程实体表”。

原因：

- 子流程不独立存在
- 一个 `WorkflowVersion.definition` 就应完整保存主图和全部子图

### 二、接口层调整

现有接口可以保留路径，但 `definition` 结构需要升级：

- `POST /api/workflows/{code}/drafts`
- `POST /api/workflows/{code}/validate-draft`
- `POST /api/workflows/{code}/publish`
- `GET /api/workflows/{code}/versions`

#### validate-draft 的新增校验项

1. `schema_version` 是否合法
2. 是否存在且只存在一个主图
3. 每个图是否有且仅有一个 `start`
4. 每个图是否有且仅有一个 `end`
5. `subflow` 图中是否错误包含 `coordinator`
6. `sub_agent.subgraph_id` 是否引用当前定义中存在的子图
7. 多出边节点是否仅为 `coordinator` 或 `sub_agent`
8. 多出边节点是否至少有两条合法出边
9. `function.operation_type` 是否在支持列表中
10. `tool` 节点 capability 字段是否完整
11. 变量引用是否存在
12. 图之间是否存在不可达子图、死图或环路异常

### 三、运行时快照构建

`WorkflowService.buildRuntimeExecutionBundle()` 需要改为返回新版运行时结构：

```json
{
  "workflow_definition": {},
  "entry_rule": {},
  "workflow_config": {},
  "workflow_catalog": {},
  "provider_configs": [],
  "model_profiles": [],
  "routing_profile_code": "intent-router-v2"
}
```

重点不是返回更多字段，而是保证 `workflow_definition` 已经是整份完整快照：

- 主图
- 全部子图
- 变量定义
- 模型绑定
- 工具节点已解析所需引用信息

### 四、旧定义兼容迁移

后端需要提供一层兼容适配：

1. 如果定义没有 `schema_version`，视为旧版
2. 旧版单图 definition 加载时包装成：
   - `main_graph_id = main`
   - `graphs.main = 旧图`
3. `coordinate` 自动映射为 `coordinator`
4. `subflow` 节点自动映射为 `sub_agent`，并补齐 `subgraph_id` 兼容逻辑

这里要注意：兼容读取可以保留，但重新保存时必须按新版结构落库。

## Python 执行器设计

### 一、执行器目标

Python 执行器不再假设自己收到的是旧版“单图 + llm/condition/form/subflow”的结构，而是正式支持新版：

- `coordinator`
- `sub_agent`
- `function`
- `tool`
- `message`
- `start`
- `end`

### 二、节点执行层重构

建议新增或重构以下节点类：

- `CoordinatorNode`
- `SubAgentNode`
- `FunctionNode`
- `MessageNode`
- `ToolNode`
- `StartNode`
- `EndNode`

当前旧节点如 `llm`、`condition`、`form`、`knowledge` 不必在本轮强行删除，但不再作为设计器默认产物。

### 三、图执行引擎

现有运行时更接近“当前节点顺序推进”。  
新版需要显式支持“图 + 子图”执行栈：

1. 主图从 `entry_node_id` 开始
2. 普通节点完成后根据出边集合决定下一跳
3. `sub_agent` 进入子图执行栈
4. 子图 `end` 返回父级
5. 主图 `end` 完成全局输出

### 四、coordinator 路由协议

Java 提供候选节点列表，Python 将其传给模型。  
模型必须返回结构化结果，建议协议：

```json
{
  "targetNodeId": "node_123",
  "reason": "根据用户当前需求进入查询子流程"
}
```

不得允许模型自由返回任意文本后再靠正则猜测节点 ID。

### 五、function 节点执行

函数节点执行器只支持声明式 DSL：

- 从上下文取变量
- 进行纯函数式转换
- 写回上下文

任何需要远程调用的逻辑都必须转移到 `tool` 节点。

### 六、事件与回放

为保障联调定位问题，Python 侧事件应补充：

- `graph.entered`
- `graph.exited`
- `branch.candidates_prepared`
- `branch.decided`
- `function.executed`

这样聊天页联调时，前后端都能看到到底进入了哪个子图、为什么走了哪个分支。

## 聊天页联调设计

### 一、联调原则

流程设计页只负责：

- 建模
- 校验
- 保存
- 发布

真正联调必须走聊天页，不允许只看前端画布假装执行成功。

### 二、联调入口

设计页顶部增加“跳转聊天页联调”按钮：

- 默认跳转当前已发布版本
- 若当前没有发布版本，则按钮禁用并提示“请先发布工作流”

### 三、聊天页联调方式

聊天页收到来自设计页的显式工作流选择参数：

- `workflowCode`
- `workflowVersion`

Java `ExecutionService.startExecution()` 继续走显式工作流执行逻辑，但必须绑定新版快照定义，不能退回旧版运行时假设。

### 四、禁止 mock

联调要求：

1. 前端使用真实 `/api/workflows`、`/api/workflows/{code}/drafts`、`/api/workflows/{code}/publish`
2. Java 使用真实数据库和真实工作流快照
3. Python 使用真实执行器和真实 capability 解析结果
4. 聊天页通过真实聊天交互触发执行

## UTF-8 与防乱码设计

这是本次方案的强约束，不是附加项。

### 一、统一编码要求

1. 所有新增或修改文件必须使用 UTF-8 无 BOM。
2. Java 返回的 JSON 必须保持 UTF-8。
3. 前端保存与读取 definition、entryRule、config 时不得做编码转换。
4. Python 事件流和日志文本必须按 UTF-8 处理。

### 二、前端文本完整性

前端继续保留并扩展 `frontend/scripts/check-text-integrity.mjs` 的使用范围，用于检查：

- 组件文案是否出现乱码
- 工作流定义示例 JSON 中的中文是否损坏
- 聊天页联调显示的中文是否异常

### 三、后端文本处理要求

Java 与 Python 不允许依赖“先序列化成字符串、再多轮转义解码”的脆弱方式来兜底中文。  
当前已有一些 lenient JSON 解析逻辑，本次可以保留兼容读取，但新版保存链路必须尽量直接写入规范 JSON，减少二次转义。

## 测试策略

### 一、前端测试

1. 设计页单元测试：
   - 主图 / 子图切换
   - 面包屑导航
   - 子代理进入子流程
   - 节点类型表单切换
2. 设计页集成测试：
   - 保存草稿
   - 校验失败提示
   - 发布成功
   - 聊天页联调按钮状态
3. 文本完整性检查：
   - 执行 UTF-8 文案检查脚本

### 二、Java 后端测试

1. `validateWorkflowDefinition` 新增结构校验用例：
   - 子图里出现 `coordinator` 报错
   - `subgraph_id` 引用不存在报错
   - 非 `coordinator/sub_agent` 多出边报错
   - capability 字段缺失报错
2. 旧定义迁移兼容用例：
   - `coordinate -> coordinator`
   - 单图定义包装为 `main`
3. 运行时快照构建测试：
   - 返回完整 graphs
   - 保留模型绑定

### 三、Python 测试

1. `CoordinatorNode`：
   - 单出边自动流转
   - 多出边返回合法 `targetNodeId`
   - 返回非法 `targetNodeId` 失败
2. `SubAgentNode`：
   - 进入子图
   - 子图返回父图
   - 输入输出映射正确
3. `FunctionNode`：
   - `assign` / `template` / `filter` 等操作可回放
4. 图执行栈：
   - 主图 -> 子图 -> 主图
5. UTF-8 文本：
   - 中文 prompt、中文消息、中文变量值不乱码

### 四、联调测试

联调必须通过聊天页完成，且必须走真实后端与 Python。

建议联调步骤：

1. 在设计页创建主流程与至少一个子流程。
2. 保存草稿并校验通过。
3. 发布工作流整体快照。
4. 从设计页跳转聊天页，指定该工作流版本。
5. 发送真实中文消息。
6. 验证：
   - 路由进入主图
   - 进入正确子流程
   - 工具节点调用真实 capability / API
   - 结束节点返回真实输出
   - 事件流与节点日志一致

## 验收标准

满足以下条件，才可判定本次重构完成：

1. 设计页支持主流程与内嵌子流程画布切换。
2. 子流程不能独立存在，也不能独立发布。
3. 新定义模型在前端、Java、Python 三层保持一致。
4. 连线不带条件，但 `coordinator` / `sub_agent` 可在多出边中返回合法 `targetNodeId`。
5. 子流程中出现 `coordinator` 会被后端校验阻止。
6. 函数节点不能发起外部调用，工具节点负责真实能力调用。
7. 工作流可以从设计页保存、校验、发布，并在聊天页完成真实联调。
8. 联调链路中不使用前端 mock 数据。
9. 中文文案、工作流定义、聊天交互全链路无乱码。

## 实施拆分原则

本节不是详细实施计划，而是后续 agent 拆分的边界定义。

### 前端 agent 负责

- `Orchestrator.tsx` 画布状态模型重构
- 子流程导航、面包屑、进入子流程交互
- 节点编辑器新增 `function` / `subgraph_id`
- 保存结构升级为 `graphs` 模型
- 跳转聊天页联调入口
- UTF-8 文案与前端测试

### Java 后端 agent 负责

- 工作流 definition v2 校验
- 旧定义兼容迁移
- draft / publish / runtime bundle 适配
- capability 字段完整性校验
- 工作流整体快照语义落地
- 后端单元测试

### Python agent 负责

- 节点类型与执行栈重构
- `CoordinatorNode` / `SubAgentNode` / `FunctionNode`
- 子图执行与事件补充
- UTF-8 文本执行验证
- Python 单元测试

### 联调阶段共同负责

- 不允许前端本地 mock 替代真实后端
- 不允许跳过 capability 解析链路
- 不允许只看页面渲染不做聊天页真实执行
- 必须完成前端、Java、Python 三段合流后的聊天页联调

## 风险与控制

### 风险 1：旧定义兼容失败

控制：

- 先做读取兼容，再做保存升级
- 增加迁移测试

### 风险 2：多出边路由不稳定

控制：

- 只允许 `coordinator` / `sub_agent` 决策
- 强制 `targetNodeId` 落在候选集合中

### 风险 3：函数节点失控演化为脚本平台

控制：

- 明确限制为声明式 DSL
- 不开放任意脚本输入

### 风险 4：联调时使用假数据掩盖问题

控制：

- 验收必须经过聊天页真实执行
- 禁止前端 mock 通过验收

### 风险 5：中文乱码

控制：

- 保持 UTF-8 无 BOM
- 前端文本完整性检查
- 聊天页中文联调用例

## 结论

本次流程设计编排页重构的关键，不是简单改 React Flow 画布，而是把“前端设计器、Java 定义校验、Python 执行器”统一到同一套工作流模型上。

采用“主图 + 内嵌子图”的方案后，可以同时满足以下要求：

- 子流程属于同一个工作流
- 点击子代理节点进入子流程画布
- 不做子流程独立版本
- 连线无条件
- 由 LLM 决定合法下一跳
- 函数与工具职责分离
- 最终通过聊天页做真实联调

后续进入实施阶段时，应先生成单独的 implementation plan，再按前端 agent、Java 后端 agent、Python agent 三线并行推进，最后进行真实联调和 UTF-8 完整性验证。
