# Workflow Designer Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将流程设计编排页重构为基于 `main graph + nested subgraphs` 的真实可执行设计器，并打通前端、Java 后端、Python 执行器与聊天页联调链路。

**Architecture:** 前端改为维护 `graphs` 注册表和当前 `graphId` 视图；Java 后端负责 definition v2 校验、旧定义兼容与 capability 解析；Python 执行器负责 `coordinator / sub_agent / function / tool / message / start / end` 的统一图执行栈。设计页只负责建模、校验、保存、发布，真实执行统一在聊天页完成。

**Tech Stack:** React + TypeScript + React Flow, Spring Boot + Jackson + JUnit, FastAPI + Pydantic + pytest, Playwright, PowerShell 启停脚本

---

## 执行顺序

1. 先落地前后端共享的数据结构与兼容层，避免前端先改成新结构后后端无法保存。
2. 再改前端画布与交互，让设计器能编辑主图和内嵌子图。
3. 随后改 Python 执行器，让聊天页能消费新版快照并真实运行。
4. 最后做前后端 + Python 联调与 UTF-8 验证，禁止使用前端 mock 假装通过。

## Agent 拆分

- 前端 agent：负责 `frontend/` 下设计器状态、子流程导航、聊天页跳转和 Playwright 页面用例。
- Java 后端 agent：负责 `java-backend/` 下 definition v2 校验、运行时快照、capability 解析与服务端测试。
- Python agent：负责 `python-ai/` 下图执行栈、节点实现、运行时事件与 pytest。
- 主控 agent：负责按任务顺序派发、审阅结果、处理跨端接口对齐，并完成最终联调。

### Task 1: Shared Workflow Schema and Contract Baseline

**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/services/api.ts`
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowService.java`
- Modify: `java-backend/src/main/java/robot/agent/service/CapabilityRuntimeResolver.java`
- Test: `java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java`
- Test: `java-backend/src/test/java/robot/agent/service/CapabilityRuntimeResolverTest.java`

- [ ] **Step 1: 写后端 definition v2 校验失败测试**

```java
@Test
void validateWorkflowDefinitionRejectsCoordinatorInsideSubflow() {
    String definition = """
        {
          "schema_version":"workflow-designer/v2",
          "main_graph_id":"main",
          "graphs":{
            "main":{
              "graph_id":"main",
              "graph_type":"main",
              "entry_node_id":"start_main",
              "nodes":{
                "start_main":{"id":"start_main","type":"start","config":{"prompt":"开始"}},
                "sub_agent_1":{"id":"sub_agent_1","type":"sub_agent","config":{"subgraph_id":"sub_a","input_mapping":{},"output_mapping":{}}},
                "end_main":{"id":"end_main","type":"end","config":{"prompt":"结束","output_format":{}}}
              },
              "edges":[
                {"id":"e1","source":"start_main","target":"sub_agent_1"},
                {"id":"e2","source":"sub_agent_1","target":"end_main"}
              ]
            },
            "sub_a":{
              "graph_id":"sub_a",
              "graph_type":"subflow",
              "entry_node_id":"start_sub",
              "nodes":{
                "start_sub":{"id":"start_sub","type":"start","config":{"prompt":"子流程开始"}},
                "coordinator_bad":{"id":"coordinator_bad","type":"coordinator","config":{"prompt":"非法协调者"}},
                "end_sub":{"id":"end_sub","type":"end","config":{"prompt":"子流程结束","output_format":{}}}
              },
              "edges":[
                {"id":"s1","source":"start_sub","target":"coordinator_bad"},
                {"id":"s2","source":"coordinator_bad","target":"end_sub"}
              ]
            }
          },
          "variables":{"global":[],"temporary":[]}
        }
        """;

    List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(definition, "{}");

    assertThat(issues)
            .extracting(item -> item.get("message"))
            .contains("子流程中不允许出现 coordinator 节点");
}
```

- [ ] **Step 2: 运行后端定向测试并确认失败**

Run: `mvn -pl java-backend -Dtest=WorkflowServiceTest,CapabilityRuntimeResolverTest test`

Expected: FAIL，提示 `validateWorkflowDefinition` 仍然只理解旧版单图结构，或 capability 解析没有遍历 `graphs`。

- [ ] **Step 3: 实现 definition v2 的基础解析、旧定义兼容和多图 capability 遍历**

```java
private Map<String, Object> normalizeWorkflowDefinition(Map<String, Object> rawDefinition) {
    if (rawDefinition.containsKey("schema_version")) {
        return rawDefinition;
    }

    Map<String, Object> legacyMainGraph = new LinkedHashMap<>();
    legacyMainGraph.put("graph_id", "main");
    legacyMainGraph.put("graph_type", "main");
    legacyMainGraph.put("entry_node_id", rawDefinition.getOrDefault("entry", "start"));
    legacyMainGraph.put("nodes", rawDefinition.getOrDefault("nodes", Map.of()));
    legacyMainGraph.put("edges", legacyTransitionsToEdges(asMap(rawDefinition.get("transitions"))));

    Map<String, Object> normalized = new LinkedHashMap<>();
    normalized.put("schema_version", "workflow-designer/v2");
    normalized.put("workflow_code", rawDefinition.get("workflow_code"));
    normalized.put("workflow_name", rawDefinition.get("workflow_name"));
    normalized.put("main_graph_id", "main");
    normalized.put("graphs", Map.of("main", legacyMainGraph));
    normalized.put("variables", rawDefinition.getOrDefault("config", Map.of()));
    normalized.put("model_bindings", asMap(asMap(rawDefinition.get("config")).get("llm_defaults")));
    return normalized;
}

private List<Map<String, Object>> collectAllNodes(Map<String, Object> definition) {
    Map<String, Object> graphs = asMap(definition.get("graphs"));
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (Object graphValue : graphs.values()) {
        Map<String, Object> graph = asMap(graphValue);
        for (Object nodeValue : asMap(graph.get("nodes")).values()) {
            Map<String, Object> node = asMap(nodeValue);
            if ("coordinate".equals(node.get("type"))) {
                node.put("type", "coordinator");
            }
            nodes.add(node);
        }
    }
    return nodes;
}
```

- [ ] **Step 4: 运行后端定向测试并确认通过**

Run: `mvn -pl java-backend -Dtest=WorkflowServiceTest,CapabilityRuntimeResolverTest test`

Expected: PASS，且 capability 解析测试不再只扫描顶层 `nodes`。

- [ ] **Step 5: Commit**

```bash
git add java-backend/src/main/java/robot/agent/service/WorkflowService.java java-backend/src/main/java/robot/agent/service/CapabilityRuntimeResolver.java java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java java-backend/src/test/java/robot/agent/service/CapabilityRuntimeResolverTest.java frontend/src/types/index.ts frontend/src/services/api.ts
git commit -m "feat: add workflow definition v2 contract baseline"
```

### Task 2: Frontend Multi-Graph Workflow Designer

**Files:**
- Modify: `frontend/src/components/Orchestrator.tsx`
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/services/api.ts`
- Test: `frontend/tests/e2e/workflow-designer.spec.ts`

- [ ] **Step 1: 写前端 E2E 失败用例，固定“进入子流程画布”行为**

```ts
import { expect, test } from '@playwright/test'

test('workflow designer enters nested subflow canvas from sub-agent node', async ({ page }) => {
  await page.goto('/#workflow')

  await page.getByRole('button', { name: '+ 子代理节点' }).click()
  await page.getByLabel('子流程名称').fill('订票子流程')
  await page.getByRole('button', { name: '创建子流程' }).click()
  await page.getByRole('button', { name: '进入子流程' }).click()

  await expect(page.getByText('工作流设置')).toBeVisible()
  await expect(page.getByTestId('workflow-breadcrumb')).toContainText('主流程')
  await expect(page.getByTestId('workflow-breadcrumb')).toContainText('订票子流程')
  await expect(page.getByTestId('graph-title')).toContainText('订票子流程')
})
```

- [ ] **Step 2: 运行前端定向用例并确认失败**

Run: `npm --prefix frontend run test:e2e -- workflow-designer.spec.ts`

Expected: FAIL，原因是当前 `Orchestrator.tsx` 只有单画布状态，没有 `subgraph_id`、面包屑和图切换。

- [ ] **Step 3: 重构前端状态为 graph registry，并实现进入子流程画布**

```ts
type WorkflowGraphType = 'main' | 'subflow'

interface WorkflowEdgeDefinition {
  id: string
  source: string
  target: string
}

interface WorkflowGraphDefinition {
  graphId: string
  graphType: WorkflowGraphType
  name: string
  entryNodeId: string
  nodes: Node<CanvasNodeData>[]
  edges: Edge[]
}

const [activeGraphId, setActiveGraphId] = useState('main')
const [graphs, setGraphs] = useState<Record<string, WorkflowGraphDefinition>>({
  main: createInitialMainGraph(),
})

const enterSubgraph = (subgraphId: string) => {
  if (!graphs[subgraphId]) return
  setActiveGraphId(subgraphId)
}

const buildDefinition = () => ({
  schema_version: 'workflow-designer/v2',
  workflow_code: workflowMeta.workflowCode,
  workflow_name: workflowName.trim(),
  main_graph_id: 'main',
  graphs: Object.fromEntries(
    Object.values(graphs).map((graph) => [
      graph.graphId,
      {
        graph_id: graph.graphId,
        graph_type: graph.graphType,
        name: graph.name,
        entry_node_id: graph.entryNodeId,
        nodes: serializeCanvasNodes(graph.nodes, variableNameMap),
        edges: serializeCanvasEdges(graph.edges),
      },
    ])
  ),
  variables: {
    global: globalVariables,
    temporary: tempVariables,
  },
  model_bindings: {
    routing_profile_code: 'intent-router-v2',
    default_model_profile_code: 'general-chat-v1',
  },
  editor_meta: {
    layout_engine: 'reactflow',
    last_open_graph_id: activeGraphId,
  },
})
```

- [ ] **Step 4: 运行前端定向用例并确认通过**

Run: `npm --prefix frontend run test:e2e -- workflow-designer.spec.ts`

Expected: PASS，且手工打开 `/#workflow` 时可以在主流程和子流程画布之间切换。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/Orchestrator.tsx frontend/src/types/index.ts frontend/src/services/api.ts frontend/src/App.tsx frontend/tests/e2e/workflow-designer.spec.ts
git commit -m "feat: add nested subgraph workflow designer"
```

### Task 3: Java Validation, Runtime Snapshot and Explicit Chat Binding

**Files:**
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowService.java`
- Modify: `java-backend/src/main/java/robot/agent/service/ExecutionService.java`
- Modify: `java-backend/src/main/java/robot/agent/controller/WorkflowController.java`
- Test: `java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java`
- Test: `java-backend/src/test/java/robot/agent/service/ExecutionServiceTest.java`

- [ ] **Step 1: 写后端失败测试，固定“非 coordinator/sub_agent 多出边必须报错”和“聊天页显式执行绑定新版快照”**

```java
@Test
void validateWorkflowDefinitionRejectsMultiOutgoingMessageNode() {
    String definition = """
        {
          "schema_version":"workflow-designer/v2",
          "main_graph_id":"main",
          "graphs":{
            "main":{
              "graph_id":"main",
              "graph_type":"main",
              "entry_node_id":"start_main",
              "nodes":{
                "start_main":{"id":"start_main","type":"start","config":{"prompt":"开始"}},
                "message_1":{"id":"message_1","type":"message","config":{"message_text":"处理中"}},
                "tool_a":{"id":"tool_a","type":"tool","config":{"invoke_type":"capability","group_id":"1","group_snapshot_version":"v1","capability_code":"a","capability_version":"v1","capability_type":"API","payload_mapping":{}}},
                "tool_b":{"id":"tool_b","type":"tool","config":{"invoke_type":"capability","group_id":"1","group_snapshot_version":"v1","capability_code":"b","capability_version":"v1","capability_type":"API","payload_mapping":{}}},
                "end_main":{"id":"end_main","type":"end","config":{"prompt":"结束","output_format":{}}}
              },
              "edges":[
                {"id":"e1","source":"start_main","target":"message_1"},
                {"id":"e2","source":"message_1","target":"tool_a"},
                {"id":"e3","source":"message_1","target":"tool_b"},
                {"id":"e4","source":"tool_a","target":"end_main"},
                {"id":"e5","source":"tool_b","target":"end_main"}
              ]
            }
          },
          "variables":{"global":[],"temporary":[]}
        }
        """;

    List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(definition, "{}");

    assertThat(issues)
            .extracting(item -> item.get("message"))
            .contains("只有 coordinator 或 sub_agent 节点允许存在多条出边");
}

@Test
void explicitWorkflowExecutionUsesPublishedSnapshotBundle() {
    SendMessageRequest request = new SendMessageRequest();
    request.setUserId("demo-admin");
    request.setContent("执行发布版本");
    request.setWorkflowCode("travel_assistant");
    request.setWorkflowVersion("v20260426");

    SendMessageResponse response = executionService.startExecution("session-1", request);

    assertThat(response.getWorkflowCode()).isEqualTo("travel_assistant");
    verify(pythonClient).execute(argThat(executeRequest ->
            "travel_assistant".equals(executeRequest.getWorkflowCode())
                    && executeRequest.getWorkflowDefinition().containsKey("graphs")
    ));
}
```

- [ ] **Step 2: 运行 Java 定向测试并确认失败**

Run: `mvn -pl java-backend -Dtest=WorkflowServiceTest,ExecutionServiceTest test`

Expected: FAIL，原因是当前校验尚未基于 `graphs/edges`，`ExecutionService` 也没有对新版快照做断言。

- [ ] **Step 3: 实现多图校验、显式工作流快照绑定和新版错误消息**

```java
private void validateGraph(
        String graphId,
        Map<String, Object> graph,
        boolean subflow,
        List<Map<String, Object>> issues
) {
    Map<String, Object> nodes = asMap(graph.get("nodes"));
    List<Map<String, Object>> edges = asListOfMaps(graph.get("edges"));

    Map<String, Long> outgoingCount = edges.stream()
            .collect(Collectors.groupingBy(edge -> stringValue(edge.get("source")), Collectors.counting()));

    for (Map.Entry<String, Object> entry : nodes.entrySet()) {
        String nodeId = entry.getKey();
        Map<String, Object> node = asMap(entry.getValue());
        String type = normalizeNodeType(stringValue(node.get("type")));

        if (subflow && "coordinator".equals(type)) {
            issues.add(issue(nodeId, "type", "子流程中不允许出现 coordinator 节点"));
        }

        long count = outgoingCount.getOrDefault(nodeId, 0L);
        if (count > 1 && !Set.of("coordinator", "sub_agent").contains(type)) {
            issues.add(issue(nodeId, "edges", "只有 coordinator 或 sub_agent 节点允许存在多条出边"));
        }
    }
}

private String normalizeNodeType(String value) {
    if ("coordinate".equals(value)) {
        return "coordinator";
    }
    if ("subflow".equals(value)) {
        return "sub_agent";
    }
    return value;
}
```

- [ ] **Step 4: 运行 Java 定向测试并确认通过**

Run: `mvn -pl java-backend -Dtest=WorkflowServiceTest,ExecutionServiceTest test`

Expected: PASS，且 `ExecutionService` 下发给 Python 的 definition 包含 `schema_version/main_graph_id/graphs`。

- [ ] **Step 5: Commit**

```bash
git add java-backend/src/main/java/robot/agent/service/WorkflowService.java java-backend/src/main/java/robot/agent/service/ExecutionService.java java-backend/src/main/java/robot/agent/controller/WorkflowController.java java-backend/src/test/java/robot/agent/service/WorkflowServiceTest.java java-backend/src/test/java/robot/agent/service/ExecutionServiceTest.java
git commit -m "feat: validate workflow graphs and bind explicit runtime snapshots"
```

### Task 4: Python Graph Runtime, Coordinator and Function Nodes

**Files:**
- Modify: `python-ai/src/core/context.py`
- Modify: `python-ai/src/core/registry.py`
- Modify: `python-ai/src/core/scheduler.py`
- Modify: `python-ai/src/nodes/__init__.py`
- Create: `python-ai/src/nodes/coordinator.py`
- Create: `python-ai/src/nodes/function.py`
- Modify: `python-ai/src/nodes/subflow.py`
- Test: `python-ai/tests/test_nodes/test_coordinator.py`
- Test: `python-ai/tests/test_nodes/test_function.py`
- Test: `python-ai/tests/test_core/test_graph_runtime.py`

- [ ] **Step 1: 写 Python 失败测试，固定图执行栈和 function DSL**

```python
async def test_sub_agent_enters_nested_graph_and_returns_to_parent():
    registry = ExecutionRegistry()
    scheduler = WorkflowScheduler()
    workflow = {
        "schema_version": "workflow-designer/v2",
        "main_graph_id": "main",
        "graphs": {
            "main": {
                "graph_id": "main",
                "graph_type": "main",
                "entry_node_id": "start_main",
                "nodes": {
                    "start_main": {"id": "start_main", "type": "start", "config": {"prompt": "开始"}},
                    "sub_agent_1": {"id": "sub_agent_1", "type": "sub_agent", "config": {"subgraph_id": "sub_a", "input_mapping": {}, "output_mapping": {}}},
                    "end_main": {"id": "end_main", "type": "end", "config": {"prompt": "结束", "output_format": {"done": "execution.done"}}},
                },
                "edges": [
                    {"id": "e1", "source": "start_main", "target": "sub_agent_1"},
                    {"id": "e2", "source": "sub_agent_1", "target": "end_main"},
                ],
            },
            "sub_a": {
                "graph_id": "sub_a",
                "graph_type": "subflow",
                "entry_node_id": "start_sub",
                "nodes": {
                    "start_sub": {"id": "start_sub", "type": "start", "config": {"prompt": "子开始"}},
                    "function_1": {"id": "function_1", "type": "function", "config": {"operation_type": "assign", "assignments": {"done": True}}},
                    "end_sub": {"id": "end_sub", "type": "end", "config": {"prompt": "子结束", "output_format": {"done": "execution.done"}}},
                },
                "edges": [
                    {"id": "s1", "source": "start_sub", "target": "function_1"},
                    {"id": "s2", "source": "function_1", "target": "end_sub"},
                ],
            },
        },
    }

    runtime = await registry.create_execution({
        "execution_id": "exec-1",
        "session_id": "session-1",
        "workflow_code": "travel_assistant",
        "workflow_version": "v20260426",
        "workflow_definition": workflow,
        "input_variables": {"user_message": "帮我订一张票"},
    })
    await scheduler.run(runtime)

    assert runtime.context.execution_variables["done"] is True
    assert runtime.context.status == "completed"
```

- [ ] **Step 2: 运行 Python 定向测试并确认失败**

Run: `pytest python-ai/tests/test_nodes/test_coordinator.py python-ai/tests/test_nodes/test_function.py python-ai/tests/test_core/test_graph_runtime.py -q`

Expected: FAIL，原因是当前 `scheduler.py` 仍然依赖旧版 `entry/transitions` 和 `LLMNode/subflow` 语义。

- [ ] **Step 3: 实现执行上下文扩展、coordinator/function 节点和图执行栈**

```python
@dataclass
class GraphFrame:
    graph_id: str
    parent_graph_id: str | None = None
    parent_node_id: str | None = None
    return_node_id: str | None = None


@dataclass
class ExecutionContext:
    ...
    current_graph_id: str = "main"
    graph_stack: list[GraphFrame] = field(default_factory=list)
    available_targets: list[str] = field(default_factory=list)


class CoordinatorNode(BaseNode):
    async def execute(self, context) -> Dict[str, Any]:
        target = self.select_target_from_candidates(context)
        return self.prepare_output({
            "status": "completed",
            "next_node": target,
            "output": {"targetNodeId": target},
        })


class FunctionNode(BaseNode):
    async def execute(self, context) -> Dict[str, Any]:
        operation_type = self.config.get("operation_type")
        if operation_type == "assign":
            assignments = self.config.get("assignments", {})
            context.add_execution_variables(assignments)
            return self.prepare_output({"status": "completed", "output": assignments})
        raise ValueError(f"Unsupported function operation_type: {operation_type}")
```

- [ ] **Step 4: 运行 Python 定向测试并确认通过**

Run: `pytest python-ai/tests/test_nodes/test_coordinator.py python-ai/tests/test_nodes/test_function.py python-ai/tests/test_core/test_graph_runtime.py -q`

Expected: PASS，且运行事件中出现 `graph.entered / graph.exited / branch.decided / function.executed`。

- [ ] **Step 5: Commit**

```bash
git add python-ai/src/core/context.py python-ai/src/core/registry.py python-ai/src/core/scheduler.py python-ai/src/nodes/__init__.py python-ai/src/nodes/coordinator.py python-ai/src/nodes/function.py python-ai/src/nodes/subflow.py python-ai/tests/test_nodes/test_coordinator.py python-ai/tests/test_nodes/test_function.py python-ai/tests/test_core/test_graph_runtime.py
git commit -m "feat: add nested graph runtime and coordinator/function nodes"
```

### Task 5: Chat Handoff, Real Integration and UTF-8 Verification

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/WorkflowPanel.tsx`
- Test: `frontend/tests/e2e/chat-flow.spec.ts`
- Test: `tests/phase1/contract_smoke/test_contract_smoke.py`
- Test: `tests/phase1/contract_smoke/test_sse_stream_contract.py`
- Test: `tests/phase1/contract_smoke/test_websocket_contract.py`

- [ ] **Step 1: 写前端失败用例，固定“从设计页跳转聊天页并绑定当前已发布工作流”**

```ts
test('workflow page sends published workflow selection into chat page', async ({ page }) => {
  await page.goto('/#workflow')

  await page.getByRole('button', { name: '跳转聊天页联调' }).click()

  await expect(page).toHaveURL(/#chat/)
  await expect(page.getByText('当前目标：')).toContainText('travel_assistant')
})
```

- [ ] **Step 2: 运行前端聊天定向用例并确认失败**

Run: `npm --prefix frontend run test:e2e -- chat-flow.spec.ts workflow-designer.spec.ts`

Expected: FAIL，原因是当前 workflow 页还没有“跳转聊天页联调”动作，也没有把已发布工作流显式传递给聊天页。

- [ ] **Step 3: 实现聊天跳转、真实联调脚本和 UTF-8 检查收口**

```ts
const handleJumpToChat = () => {
  if (!workflowSidebarState?.workflowCode || !workflowSidebarState?.publishedVersion) return
  const params = new URLSearchParams({
    workflowCode: workflowSidebarState.workflowCode,
    workflowVersion: workflowSidebarState.publishedVersion,
  })
  window.location.hash = `chat?${params.toString()}`
}

useEffect(() => {
  const hash = window.location.hash.replace('#', '')
  if (!hash.startsWith('chat?')) return
  const params = new URLSearchParams(hash.slice('chat?'.length))
  const workflowCode = params.get('workflowCode') || ''
  const workflowVersion = params.get('workflowVersion') || ''
  if (workflowCode && workflowVersion) {
    setSelectedPublishedWorkflowCode(workflowCode)
  }
}, [])
```

- [ ] **Step 4: 跑完整自动化验证与真实联调命令**

Run: `npm --prefix frontend run test:e2e -- workflow-designer.spec.ts chat-flow.spec.ts`

Expected: PASS

Run: `mvn -pl java-backend test`

Expected: PASS

Run: `pytest python-ai/tests -q`

Expected: PASS

Run: `npm --prefix frontend run check:text`

Expected: 输出无乱码检查失败项

Run: `powershell -File .\start-all.ps1`

Expected: 前端、Java 后端、Python 服务全部启动

Run: `pytest tests/phase1/contract_smoke -q`

Expected: PASS，SSE / WebSocket / 基本契约全部通过

- [ ] **Step 5: Commit**

```bash
git add frontend/src/App.tsx frontend/src/components/WorkflowPanel.tsx frontend/tests/e2e/chat-flow.spec.ts tests/phase1/contract_smoke/test_contract_smoke.py tests/phase1/contract_smoke/test_sse_stream_contract.py tests/phase1/contract_smoke/test_websocket_contract.py
git commit -m "feat: wire workflow designer to real chat integration"
```

## 联调验收清单

- [ ] 设计页可以创建主流程与内嵌子流程。
- [ ] 子流程不可独立发布，只随主流程整体发布。
- [ ] `coordinator` 和 `sub_agent` 节点在多出边时能返回合法 `targetNodeId`。
- [ ] `function` 节点只做本地确定性处理，不发生外部调用。
- [ ] `tool` 节点走真实 capability / API 解析与执行链路。
- [ ] 聊天页能显式绑定设计页当前已发布工作流。
- [ ] 设计页、聊天页、后端返回、Python 事件流中的中文均无乱码。
- [ ] 联调过程不使用前端 mock 数据。

## Subagent Dispatch Order

### Frontend agent

- 先执行 Task 2
- 再执行 Task 5 中前端跳转和 Playwright 补强
- 不修改 `java-backend/` 与 `python-ai/`

### Java backend agent

- 先执行 Task 1 与 Task 3
- 负责把新版 `graphs` 定义稳定下发给 Python
- 不修改 `frontend/` 与 `python-ai/`

### Python agent

- 在 Task 3 完成后执行 Task 4
- 负责消费 Java 下发的新版工作流快照
- 不修改 `frontend/` 与 `java-backend/`

## 完成标准

本计划只有在以下条件全部满足时才能算完成：

1. 三个 agent 的改动已经合并到同一工作区。
2. 前端、Java、Python 的自动化测试都已运行并通过。
3. 已完成真实服务启动和聊天页联调。
4. 已完成 UTF-8 文本完整性检查。
5. 没有使用前端 mock 数据替代最终验收。
