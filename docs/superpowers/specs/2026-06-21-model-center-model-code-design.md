# 模型中心模型编码与 API Key 显示设计修改文档

日期：2026-06-21

## 背景

当前模型中心已经有 `LlmModelRecord.modelCode`，数据库字段 `llm_model_record.model_code` 也带唯一约束，运行链路已经大量按 `model_code` 解析模型。但管理端仍存在几个与新要求冲突的点：

- 创建模型时后端自动生成隐藏模型编码，用户页面没有模型编码输入。
- 管理接口详情、更新、删除仍以数字 `id` 作为路径标识。
- 前端表单强制填写自定义模型名、供应商、上游 Model 名称、API Key、Base URL，但没有强制填写模型编码。
- 模型列表和编辑响应当前会携带 `api_key` 明文，前端用 `type="password"` 隐藏显示，但没有眼睛按钮切换。

本次调整目标是让模型编码成为用户可见、用户必填、后台配置可直接引用的模型唯一标识，并给 API Key 输入提供显式显示/隐藏交互。

## 目标

1. 模型编码 `model_code` 暴露到模型中心用户页面。
2. 新建模型时用户必须填写 `model_code`。
3. `model_code` 成为模型记录的业务唯一标识，后台配置、运行时查询、管理接口都优先使用它。
4. 后台按配置中的 `model_code` 查询 `LlmModelRecord`，不再依赖隐藏生成编码或数字 `id`。
5. API Key 默认隐藏，点击眼睛按钮后显示明文，再点击恢复隐藏。
6. 模型列表不主动暴露完整 API Key；编辑详情可在管理员权限下返回或使用已有响应中的完整密钥。
7. 保持 UTF-8 无 BOM，不改变文件编码，不破坏中文内容。

## 非目标

- 不重构整个模型调用协议。
- 不引入新的模型路由策略。
- 不修改 Python 运行时模型调用字段名，仍使用 `model_records` 和 `model_code`。
- 不把 `id` 从数据库主键中删除；`id` 继续作为内部技术主键存在，但不作为用户和后台配置标识。

## 当前代码落点

- 实体：`java-backend/src/main/java/robot/agent/model/LlmModelRecord.java`
  - `model_code` 已唯一。
  - 当前还有 `id`、`api_key`、`base_url` 等字段。
- Repository：`java-backend/src/main/java/robot/agent/repository/LlmModelRecordRepository.java`
  - 已有 `findByModelCode`、`findByModelCodeIn`。
  - 搜索已覆盖 `modelCode`、`modelName`、`upstreamModelCode`。
- Controller：`java-backend/src/main/java/robot/agent/controller/ModelConfigController.java`
  - 当前 `GET/PUT/DELETE /models/{id}` 使用 Long id。
- Service：`java-backend/src/main/java/robot/agent/service/ModelConfigService.java`
  - `saveModelRecord` 当前调用 `generateHiddenModelCode()`。
  - `updateModelRecord` 和 `deleteModelRecord` 当前通过 `findById`。
  - `modelRecordToAdminResponseMap` 当前返回 `api_key`。
- 前端组件：`frontend/src/components/ModelConfigPanel.tsx`
  - `ModelFormState` 没有 `model_code`。
  - 表单没有“模型编码”输入。
  - `isFormComplete` 未校验模型编码。
  - API Key 输入为 `type="password"`，没有显示按钮。
- 前端 API：`frontend/src/services/api.ts`
  - `saveModelRecord` payload 没有 `model_code`。
  - `getModelRecord/update/delete` 仍使用数字 `id`。
- 前端类型：`frontend/src/types/index.ts`
  - `ModelRecordConfig` 没有 `model_code`。

## 方案比较

### 方案 A：只在页面显示自动生成的模型编码

做法：后端仍自动生成 `model_code`，保存后在页面显示。

优点：

- 改动小。

缺点：

- 不满足“用户强制填写模型编码”。
- 后台配置仍依赖用户保存后复制隐藏生成值，使用体验差。

结论：不采用。

### 方案 B：用户填写 `model_code`，管理接口仍按 `id` 更新删除

做法：新增前端输入和后端保存校验，但保留 `/models/{id}`。

优点：

- 改动中等。
- 数据层能满足唯一标识。

缺点：

- 管理接口语义仍与“模型编码是唯一标识”不一致。
- 前端仍需保留 `id` 选择态，后台配置和管理资源标识割裂。

结论：可作为过渡，但不是推荐方案。

### 方案 C：用户填写 `model_code`，管理接口以 `model_code` 为资源标识

做法：

- 新建模型必须提交 `model_code`。
- 详情、更新、删除、测试接口以 `model_code` 为路径参数。
- 数字 `id` 只保留在数据库内部，不再作为前端管理主标识。
- 运行时继续通过 `findByModelCode` 查询模型。

优点：

- 与“模型编码是唯一标识”完全一致。
- 后台配置值、页面展示值、接口路径值、运行时查询值统一。
- 后续排查日志和配置问题更直接。

缺点：

- 需要同步改前端 API、E2E、后端 controller/service 测试。

结论：推荐采用方案 C。

## 推荐设计

### 一、模型编码字段规则

`model_code` 是模型记录的业务唯一标识。

建议校验规则：

- 必填。
- 创建后不可修改。
- 长度 2 到 64。
- 允许字符：英文字母、数字、下划线、短横线、点号。
- 禁止空格和中文，避免进入 URL 路径、配置文件、日志检索时产生歧义。
- 保存前 trim。
- 唯一性由服务层预查和数据库唯一约束共同保证。

示例合法值：

- `general-chat-v1`
- `intent-router`
- `modelscope-qwen3-8b`
- `embedding.qwen3.8b`

### 二、后端接口契约

保留列表接口：

- `GET /api/model-config/models?page=0&pageSize=20&keyword=xxx`

列表响应必须包含 `model_code`，但不返回完整 `api_key`。

推荐列表 item：

```json
{
  "id": 1,
  "model_code": "general-chat-v1",
  "custom_model_name": "通用对话模型",
  "provider": "openai_compatible",
  "model_name": "gpt-4o-mini",
  "base_url": "https://api.example.com/v1",
  "api_key_configured": true,
  "api_key_masked": "sk-a****wxyz",
  "default_options": {},
  "created_at": "2026-06-21T10:00:00",
  "updated_at": "2026-06-21T10:00:00"
}
```

新增或调整编码资源接口：

- `GET /api/model-config/models/{modelCode}`
- `PUT /api/model-config/models/{modelCode}`
- `DELETE /api/model-config/models/{modelCode}`
- `POST /api/model-config/models/{modelCode}/test`

保留创建接口：

- `POST /api/model-config/models`

创建 payload 必须包含：

```json
{
  "model_code": "general-chat-v1",
  "custom_model_name": "通用对话模型",
  "provider": "openai_compatible",
  "model_name": "gpt-4o-mini",
  "api_key": "sk-xxx",
  "base_url": "https://api.example.com/v1",
  "default_options": {
    "temperature": 0.2
  }
}
```

更新 payload 不允许修改 `model_code`。如果请求体包含 `model_code`，必须与路径 `{modelCode}` 一致；不一致返回 400。

本次不保留旧 `/models/{id}` 兼容接口，也不新增 `/models/id/{id}` 降级路径。所有管理端详情、更新、删除、测试入口都必须使用 `model_code` 作为资源标识，数字 `id` 只保留为数据库内部主键。

### 三、后端服务调整

`saveModelRecord`：

- 删除 `generateHiddenModelCode()` 创建逻辑。
- 从 `request.getModelCode()` 读取编码。
- 校验格式和唯一性。
- 使用 `model_code + "-provider"` 继续生成内部 provider_code，除非后续决定恢复显式 provider 管理。
- 审计日志 resourceId 使用用户填写的 `model_code`。

`getModelRecord`：

- 改为 `getModelRecord(String modelCode)`。
- 通过 `modelRecordRepository.findByModelCode(modelCode)` 查询。

`updateModelRecord`：

- 改为 `updateModelRecord(String modelCode, request)`。
- 通过 `findByModelCode` 查询。
- 不允许修改 `model_code`。
- 继续允许修改自定义名称、上游 Model 名称、供应商、Base URL、API Key、默认参数。

`deleteModelRecord`：

- 改为 `deleteModelRecord(String modelCode)`。
- 删除前继续使用现有 `collectModelRecordReferences(modelCode)` 做引用保护。

`modelRecordToAdminResponseMap`：

- 必须加入 `model_code`。
- 列表响应不返回完整 `api_key`，只返回 `api_key_configured` 和 `api_key_masked`。
- 详情响应是否返回 `api_key` 取决于安全策略。推荐管理员详情返回完整值用于编辑页眼睛显示，但列表不返回。

### 四、API Key 显示策略

推荐策略：列表不回显明文，详情/编辑态回显明文。

原因：

- 满足“点击眼睛显示按钮显示出来，不点击就隐藏显示”。
- 避免模型列表接口一次性泄露所有密钥。
- 维持当前编辑体验，用户不用每次修改模型都重新填写 API Key。

前端表现：

- API Key 输入默认 `type="password"`。
- 输入框右侧放眼睛图标按钮。
- 点击后切换为 `type="text"` 并显示完整值。
- 再次点击恢复 `type="password"`。
- 切换新建或选择其他模型时重置为隐藏。
- 按钮必须有 `aria-label`，例如 `显示 API Key` / `隐藏 API Key`。

后端响应建议：

- `GET /models`：不返回 `api_key`，只返回 `api_key_masked`、`api_key_configured`。
- `GET /models/{modelCode}`、保存响应、更新响应：可返回 `api_key` 给管理员编辑态。
- 审计日志和普通日志不得打印 `api_key`。

如果后续安全要求更高，可改成点击眼睛时调用单独接口：

- `GET /api/model-config/models/{modelCode}/api-key`

但第一版不建议引入额外接口，除非已有权限模型能区分“编辑模型”和“查看密钥”。

### 五、前端页面调整

表单状态新增字段：

```ts
type ModelFormState = {
  id?: number
  model_code: string
  custom_model_name: string
  provider: string
  model_name: string
  api_key: string
  base_url: string
  default_options: string
}
```

表单新增“模型编码”字段，放在“自定义模型名”之前。

交互规则：

- 新建态：模型编码可编辑且必填。
- 编辑态：模型编码显示但不可编辑，避免破坏已有后台配置和工作流引用。
- 列表每行显示 `model_code`，并把它作为主要可复制标识之一。
- 搜索占位文案改为“输入模型编码、自定义模型名或 Model 名称”。
- 保存前校验 `model_code`、自定义模型名、供应商、上游 Model 名称、API Key、Base URL。
- 保存 payload 包含 `model_code`。
- 选择列表行时建议调用 `getModelRecord(model_code)` 获取详情，避免列表接口明文返回 API Key。

API Key 眼睛按钮建议：

- 使用现有图标库时优先用 `lucide-react` 的 `Eye` 和 `EyeOff`。
- 若当前项目未安装图标库，可先使用文本按钮“显示/隐藏”，但实现阶段应检查依赖；如已有 lucide，则使用图标。
- 按钮不能提交表单，必须 `type="button"`。

### 六、前端 API 与类型调整

`ModelRecordConfig` 增加：

```ts
model_code: string
api_key?: string
api_key_masked?: string
api_key_configured?: boolean
```

`saveModelRecord` payload 增加 `model_code`。

`getModelRecord` 改为：

```ts
getModelRecord(modelCode: string): Promise<ModelRecordConfig>
```

`saveModelRecord` 更新参数改为：

```ts
saveModelRecord(payload, currentUserId, existingModelCode?: string)
```

`deleteModelRecord` 改为：

```ts
deleteModelRecord(modelCode: string, currentUserId: string)
```

所有路径中的 `modelCode` 必须使用 `encodeURIComponent`。

### 七、后台配置与运行时链路

现有配置已经使用类似：

```yaml
robot:
  model:
    default:
      model-code: model-c51996235023
      purpose-model-codes:
        routing: model-c51996235023
```

调整后约束：

- 这些配置值必须来自用户页面可见的 `model_code`。
- `ModelConfigService.buildRuntimeBundleForModel(modelCode)` 继续通过 `findByModelCode` 查询。
- 如果配置了不存在的 `model_code`，维持当前告警和空 bundle 行为，或在启动自检中报告配置错误。
- 文档和初始化数据应把示例编码改成可读编码，例如 `general-chat-v1`，避免继续使用隐藏生成风格的 `model-xxxxxxxxxxxx`。

### 八、删除和引用保护

删除模型记录时仍以 `model_code` 做引用扫描。

必须检查：

- workflow definition 中的 `model_code`。
- workflow config 中的 `routing_model_code`、`llm_defaults.model_code`。
- API Center schema 中可能嵌入的模型引用。
- 知识库 embedding/answer 相关配置中如有模型编码，也应纳入检查。

删除失败提示应包含被引用的资源摘要，例如：

```text
model record is still referenced: workflow_config:flight_booking@1.0.0
```

### 九、测试策略

#### Java 单元测试

新增或调整：

- 创建模型记录时缺少 `model_code` 返回 400。
- 创建模型记录时 `model_code` 重复返回 400。
- 创建模型记录时非法编码返回 400。
- `GET /models/{modelCode}` 通过编码查询成功。
- `PUT /models/{modelCode}` 不允许请求体修改编码。
- `DELETE /models/{modelCode}` 按编码执行引用保护。
- 列表响应包含 `model_code`，但不包含完整 `api_key`。
- 详情响应包含编辑态所需字段。

#### 前端 E2E

新增或调整：

- 模型编码输入存在且必填。
- 保存模型时请求 payload 包含 `model_code`。
- 编辑已有模型时模型编码不可编辑。
- 搜索可按模型编码命中。
- API Key 默认 password 类型。
- 点击眼睛按钮后变成 text 类型且显示值。
- 再次点击后恢复 password 类型。
- 选择不同模型或新建模型后 API Key 恢复隐藏。
- 删除模型调用 `/models/{modelCode}`。

#### 回归验证

建议命令：

```bash
mvn -pl java-backend -Dtest=ModelConfigServiceTest,UnifiedModelServiceTest,WorkflowServiceTest,KnowledgeServiceDocumentTest,KnowledgeSearchServiceTest test
npm --prefix frontend run test:e2e -- --grep "模型配置|model config"
npm --prefix frontend run build
npm --prefix frontend run check:text
pytest python-ai/tests/test_core/test_model_runtime.py python-ai/tests/test_nodes/test_llm.py python-ai/tests/test_nodes/test_knowledge.py -q
```

## 实施步骤建议

1. 后端先补测试，固定 `model_code` 必填、唯一、路径资源标识、API Key 列表不明文的契约。
2. 修改 Controller 和 Service，从 `id` 路径切到 `modelCode` 路径。
3. 删除或停用 `generateHiddenModelCode()`，创建时使用请求体 `model_code`。
4. 调整前端类型和 API 调用，所有管理操作使用 `model_code`。
5. 调整模型中心 UI，新增模型编码输入和 API Key 眼睛按钮。
6. 更新 E2E mock 数据和断言。
7. 更新配置样例和文档中的模型编码示例。

## 验收标准

- 用户能在模型中心看到每条模型记录的模型编码。
- 新建模型时不填写模型编码无法保存。
- 新建模型保存后，数据库 `llm_model_record.model_code` 等于用户填写值。
- 后台配置填写同一个模型编码后，运行时能按该编码查询并调用对应模型。
- 编辑、删除、测试接口以模型编码定位记录。
- API Key 默认隐藏，点击眼睛按钮显示，再点击隐藏。
- 模型列表接口不批量返回完整 API Key。
- 现有工作流、知识库、Python 模型运行测试不因编码调整回退到 `id`。

## Agent Review

### Summary

该设计把模型中心的业务主键统一收口到 `model_code`，与当前运行时按模型编码查询的方向一致。推荐方案 C 是正确方向，因为它消除了“页面/配置用模型编码，管理接口用 id”的双标识问题。

### Major Issues

1. 必须明确 API Key 明文返回边界。
   - 当前服务层 `modelRecordToAdminResponseMap` 会把 `api_key` 放进响应。如果列表接口继续复用这个响应，眼睛按钮只是前端遮挡，不能降低批量泄露风险。
   - 设计已要求列表不返回明文，详情/编辑态才返回，这是必须执行的安全边界。

2. 创建逻辑必须删除隐藏编码生成。
   - 当前 `saveModelRecord` 使用 `generateHiddenModelCode()`，即使 DTO 已有 `model_code` 也不会采用用户输入。
   - 如果只改前端输入框而不改服务层，用户填写的编码会被静默丢弃，后台配置仍无法稳定引用。

3. 更新路径切换到 `model_code` 后必须禁止修改编码。
   - 如果允许编辑态修改 `model_code`，已有 workflow config、knowledge config、application.yml 中的引用会立刻失效。
   - 正确做法是创建后锁定，必要时通过“复制新模型记录”实现改名。

4. 不应保留旧 `id` 管理路径。
   - 同时支持 `/models/{id}` 和 `/models/{modelCode}` 会产生路径歧义，尤其模型编码可能是纯数字。
   - 本次要求不兼容旧设计，因此实现必须直接切到 `{modelCode}`，数字 `id` 不能继续作为前端或后台配置入口。

### Minor Suggestions

- 模型编码输入旁建议提供短提示：用于后台配置和工作流引用，创建后不可修改。
- 列表行中应优先显示 `custom_model_name`，但 `model_code` 应紧随其下，方便复制和排查。
- 搜索字段已经覆盖 `modelCode`，前端只需更新占位文案和 E2E 断言。
- API Key 显示按钮应重置隐藏状态，避免从一个模型切到另一个模型时继续明文显示。

### Test Plan Review

测试范围合理，覆盖了后端契约、前端交互和运行时回归。实现时建议优先写后端服务测试，因为 `model_code` 必填与唯一性属于核心不变量；前端 E2E 再验证用户路径和眼睛按钮行为。

### Final Recommendation

按方案 C 实施。不要只做页面展示；必须同时完成后端创建逻辑、资源路径、列表/详情响应拆分和前端强校验，否则需求会在运行时配置链路上继续不闭环。
