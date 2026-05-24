# 工作流快照持久化设计

## 背景

当前工作流编辑器在保存、发布、重新打开之间依赖 `definition`、`entry_rule`、`editor_meta`、`config` 多个 JSON 字段组合还原。虽然 `workflow_version` 已经存储 JSON 字段，但“完整工作流信息”没有一个原子快照契约。修改编辑工作流后，再打开工作流可能因为读取链路没有优先使用完整快照，导致节点、连线、变量、画布状态或其他元信息丢失。

## 目标

1. 工作流发布版本必须以完整 JSON 快照保存到数据库。
2. 快照必须包含节点、连线、变量、模型绑定、入口规则、兼容配置、画布布局、当前图、图顺序、工作流基础信息。
3. 下次打开工作流时优先从数据库完整快照读取，保证当前保存的信息不丢失。
4. 兼容旧数据：没有快照的版本继续通过旧字段还原，并在再次保存时写入新快照。
5. 保持 UTF-8，无 BOM，不做任何编码转换。

## 推荐方案

在 `workflow_version` 增加 `workflow_snapshot` JSON 字段。前端保存草稿和发布版本时构建同一份 `workflow-snapshot/v1` 快照；后端持久化快照，并继续维护旧字段用于兼容运行时和历史接口。读取版本时返回 `workflowSnapshot`，前端打开时优先 hydrate 快照。

## 快照结构

```json
{
  "schema_version": "workflow-snapshot/v1",
  "workflow": {
    "workflow_code": "workflow_001",
    "workflow_name": "示例工作流",
    "workflow_version": "v20260430230000"
  },
  "designer": {
    "definition": {
      "schema_version": "workflow-designer/v2",
      "workflow_code": "workflow_001",
      "workflow_name": "示例工作流",
      "workflow_version": "v20260430230000",
      "main_graph_id": "main",
      "graphs": {
        "main": {
          "graph_id": "main",
          "graph_type": "MAIN",
          "graph_name": "主流程",
          "entry_node_id": "coordinator_main",
          "nodes": {},
          "edges": []
        }
      },
      "variables": {
        "global": [],
        "temporary": []
      },
      "model_bindings": {},
      "editor_meta": {}
    },
    "entry_rule": {},
    "workflow_config": {},
    "editor_meta": {}
  }
}
```

## 后端设计

### 数据库与实体

- `WorkflowVersion` 新增 `workflowSnapshot` 字段，对应 `workflow_snapshot` JSON 列。
- `WorkflowSchemaRepairService` 新增 `ensureWorkflowSnapshotColumnSupported()`，启动/保存前自动补齐列。
- `WorkflowVersionResponse` 和 `CreateWorkflowVersionRequest` 新增 `workflowSnapshot`，JSON 属性名为 `workflow_snapshot`。

### 保存草稿

`WorkflowService.saveWorkflowDraft()` 的写入策略：

1. 规范化 `definition`，继续写入 `definition` 字段。
2. 保存 `entryRule`、`editorMeta`、`config`。
3. 如果请求带 `workflowSnapshot`，校验 `schema_version = workflow-snapshot/v1`，并把其中 `designer.definition` 规范化后保存。
4. 如果请求没有 `workflowSnapshot`，由旧字段组装兼容快照。
5. 落库前统一修正快照中的 `workflow.workflow_code`、`workflow.workflow_name`、`workflow.workflow_version`，避免前端状态滞后。

### 发布版本

`WorkflowService.publishWorkflow()` 不重新生成业务内容，但发布前必须确保版本有完整快照：

1. 如果 `workflow_snapshot` 为空，则由当前版本旧字段补齐快照并保存。
2. 再将版本状态设为 `PUBLISHED`。
3. 发布后的 `workflow.currentVersion` 指向该完整快照版本。

### 读取版本

`GET /api/workflows/{code}/versions` 返回 `workflowSnapshot`。旧字段保持不变，方便兼容运行时和旧前端逻辑。

## 前端设计

### 类型与 API

- `WorkflowVersionSummary` 新增 `workflowSnapshot?: string`。
- `saveWorkflowDraft()` payload 新增 `workflowSnapshot: Record<string, unknown>`。
- 请求体新增 `workflow_snapshot: JSON.stringify(payload.workflowSnapshot)`。

### 保存与发布

前端以 `currentDefinition` 为事实来源生成快照：

1. `buildWorkflowSnapshot()` 接收 `definition`、`entryRule`、`workflowConfig`、`workflowCode`、`workflowName`、`version`。
2. `persistDraft(version)` 每次保存草稿或发布前保存版本时都构建完整快照。
3. `publish` 流程保持“校验 → 保存发布版本快照 → 发布状态”的顺序。

### 打开工作流

`hydrateWorkflowSelection()` 读取顺序：

1. 优先解析 `selection.version.workflowSnapshot`。
2. 如果快照有效，取 `snapshot.designer.definition`、`snapshot.designer.editor_meta`、`snapshot.designer.workflow_config` 还原。
3. 如果快照不存在或无效，降级到旧 `definition/editorMeta/config`。
4. 无论来源如何，内存态仍是现有 `graphs`、`graphOrder`、`currentGraphId`、变量和模型绑定结构。

## 验收标准

1. 修改工作流节点名称、节点配置、连线、变量、子流程名称并保存草稿后，重新打开可以完整还原。
2. 发布版本后，重新打开发布版本可以完整还原。
3. 数据库 `workflow_version.workflow_snapshot` 包含完整 JSON，且包含 `graphs.*.nodes`、`graphs.*.edges`、`variables`、`editor_meta.graph_layouts`。
4. 旧版本没有 `workflow_snapshot` 时仍能打开。
5. 前后端构建和相关测试通过。

## 风险与约束

- 不改变运行时执行协议，运行时仍可使用规范化后的 `definition`。
- 不拆分子流程实体表，避免扩大改动范围。
- 不转换文件编码，所有新增/修改文件保持 UTF-8 无 BOM。
