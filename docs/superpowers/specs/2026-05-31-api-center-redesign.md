# API 中心重构设计方案

**日期：** 2026-05-31

**状态：** 待实施

## 一、背景

现有系统中“能力中心”承担 API / Skill / MCP 多类型能力治理，并包含发布快照功能。本次重构将其收敛为专门的 API 治理页面：API 中心、API 组、API 项。重构目标是降低概念复杂度，强化 API 请求配置、Draft-07 Schema 校验与真实联通性测试。

## 二、命名与范围

### 2.1 命名替换

- “能力中心”改为“API中心”。
- “能力组”改为“API组”。
- “能力项”改为“API项”。
- “新增能力”改为“新增API”。
- API 项编辑表单中的“能力”文案全部改为“API”。
- “真实请求测试”改为“请求测试”。

### 2.2 删除能力类型

- API 项不再区分 `API / SKILL / MCP`。
- 前端移除“能力类型”筛选。
- 新增/编辑 API 时不再要求用户选择能力类型。
- 后端不兼容原有能力类型模型，直接全链路重构为 API Center。
- 后端模型、DTO、表结构、接口语义中不再保留 `capabilityType` 或同类类型字段。
- 原 `SKILL / MCP` 类型数据不迁入 API 中心；如仍需保留，应另行设计独立模块。

### 2.3 去除发布快照

- 前端移除“发布快照”入口、快照列表与相关状态展示。
- 后端不再提供用户可见的组快照发布流程。
- 不保留快照版本机制。
- 流程运行与审计若需要追溯 API 配置，使用当前 API 配置与调用日志，不引入新的快照版本。

## 三、API 项核心字段

API 项编辑至少包含以下信息：

- 基础信息：API 名称、所属 API 组、描述、启用状态。
- 请求连接配置：请求 URL、HTTP Method、Header、URL 变量映射。
- Schema 配置：输入 Schema、输出 Schema。
- 测试信息：最近一次请求测试状态、测试时间、测试错误信息。

Header 为可选项；没有认证或额外请求头的 API 不需要强制用户输入 Header。

API 编码不再由用户输入或维护。系统可使用数据库主键或系统生成 ID 作为内部标识，但不在 API 项表单中暴露“API 编码”。

输入 Schema 只负责请求体 Body 中的参数结构，不负责 URL Path 参数和 Query 参数。URL Path 参数和 Query 参数通过请求 URL 中的 `{变量名}` 自动识别并生成变量映射，例如 `?userId={userId}` 自动生成 `userId` 变量输入项。

## 四、Schema 校验设计

### 4.1 用户输入要求

- 输入 Schema 和输出 Schema 均由用户手动输入。
- 格式参照仓库根目录 `API中心.md` 中的 Draft-07 示例。
- Schema 必须是合法 JSON。
- Schema 必须符合 JSON Schema Draft-07。
- 默认启用 Draft-07 format 校验，至少覆盖 `email`、`uuid`、`date-time`。

### 4.2 输入 Schema 职责边界

输入 Schema 只描述请求体 Body 的参数结构，不描述 URL Path 参数和 Query 参数。

- 如果 API 有 JSON Body，输入 Schema 直接描述 Body 对象。
- 如果 API 没有 Body，输入 Schema 可以为空对象 Schema 或不启用 Body 参数输入。
- URL 中的 Path 参数和 Query 参数不写入输入 Schema。
- Header 不写入输入 Schema，由 Header 配置单独维护。

示例：

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "title": "创建用户_请求体",
  "properties": {
    "username": {
      "type": "string",
      "minLength": 4,
      "maxLength": 20
    },
    "email": {
      "type": "string",
      "format": "email"
    }
  },
  "required": ["username", "email"],
  "additionalProperties": false
}
```

### 4.3 URL 变量映射

请求 URL 支持 `{变量名}` 占位符。前端在用户输入 URL 后自动解析变量并生成测试输入项。

示例：

- URL：`https://example.com/users/{userId}/orders?status={status}&page={page}`。
- 自动生成 URL 变量：`userId`、`status`、`page`。
- 请求测试时用户填写这些变量的实际值。
- 后端测试时用变量值替换 URL 占位符后发起真实请求。

变量规则建议：

- 变量名只允许字母、数字、下划线，且不能以数字开头。
- 同一个 URL 中重复出现同名变量时只生成一个输入项。
- URL 中存在 `{变量名}` 但测试时未填写变量值，不能发起请求测试。
- URL 变量值不受输入 Schema 校验；如需类型约束，后续可扩展“URL 变量约束”，本次先按字符串处理。

### 4.4 保存前强校验

保存 API 项前必须校验：

1. 输入 Schema JSON 语法合法。
2. 输出 Schema JSON 语法合法。
3. 两个 Schema 都声明 Draft-07：`$schema` 建议为 `http://json-schema.org/draft-07/schema#`。
4. 两个 Schema 都能通过 Draft-07 meta-schema 校验。
5. 两个 Schema 中使用的 `format` 按 Draft-07 语义进行校验。

校验不通过时：

- 用户可以继续编辑。
- 禁止保存。
- 前端展示具体错误位置或错误路径。
- 后端保存接口再次拦截，避免绕过前端校验。

## 五、请求测试设计

### 5.1 文案与目的

“真实请求测试”改为“请求测试”。请求测试用于验证 API 配置的真实连通性、输入 Schema 与输出 Schema 是否可用。

### 5.2 用户输入

请求测试时用户需要输入或确认：

- 请求 URL：必填。
- HTTP Method：必填。
- Header：可选。
- URL 变量：由请求 URL 中的 `{变量名}` 自动生成，测试时必填。
- 请求体参数：按输入 Schema 填写；输入 Schema 只描述 Body。

### 5.3 测试流程

1. 校验输入 Schema 和输出 Schema 本身是否合法。
2. 从请求 URL 中解析 `{变量名}` 并校验测试变量是否完整填写。
3. 使用输入 Schema 校验用户提供的请求体 Body。
4. 后端用 URL 变量替换 URL 占位符，并按照 URL、Method、Header、Body 发起真实 HTTP 请求。
5. 判断 HTTP 响应是否成功，默认 `2xx` 视为通过。
6. 解析响应体 JSON。
7. 使用输出 Schema 校验响应 JSON。
8. 全部通过后记录本次请求测试成功。

### 5.4 保存门禁

- 请求测试不通过时，用户可以继续编辑，但不能保存。
- 请求测试通过后，若用户修改 URL、Method、Header、输入 Schema、输出 Schema、URL 变量值或测试 Body 中任意一项，则测试状态失效，需要重新测试。
- 保存接口必须检查最近一次成功测试是否匹配当前表单内容。

## 六、安全与风险提示

### 6.1 真实请求风险提示

请求测试会向用户填写的真实 URL 发起请求，可能产生数据写入、状态变更、扣费、通知发送等副作用。前端在测试前提示用户确认，尤其是 `POST / PUT / PATCH / DELETE` 等非幂等方法。

### 6.2 Header 密钥处理

- Header 中可能包含密钥，由用户自行负责填写与授权。
- 后端持久化 Header 时需要加密存储。
- 前端回显时显示正常明文，便于用户编辑。
- 测试记录、审计日志、错误日志不应输出敏感 Header 明文。

### 6.3 后端请求安全

建议实现以下防护：

- 请求超时限制。
- 响应体大小限制。
- 禁止重定向到不可信地址或至少记录重定向链路。
- SSRF 防护：限制访问内网、回环地址、链路本地地址和云元数据地址，或支持可配置白名单。

## 七、实施方案

### 方案 B：全链路重命名为 API Center

- 后端类、表、DTO、接口路径统一改为 `Api*`。
- 删除旧 Capability 模型，不保留 `capabilityType` 等能力类型兼容字段。
- 前端组件、类型、服务方法、E2E 测试统一改为 API Center 语义。
- 数据表统一改为 API 组、API 项、API 测试记录、API 审计记录等命名。
- 接口路径统一从 Capability 语义迁移到 API Center 语义。
- 前后端语义完全一致。

优点：长期维护成本低，概念清晰。

缺点：迁移范围大，影响已有流程、测试、数据兼容；实施时需要同步更新流程引用、运行时解析和测试用例。

本次采用方案 B，不做兼容式保留。

## 八、待评审的不合理点与建议

1. “测试不通过无法编辑保存”已调整为“允许继续编辑，但禁止保存”，否则用户无法修复失败配置。
2. 不保留快照版本符合当前要求，但会降低历史运行完全复现能力；如后续需要追溯，可在调用日志中记录当次请求配置摘要。
3. Header 明文回显便于编辑，但存在前端可见风险；建议至少对日志和测试记录脱敏。
4. 请求测试是真实请求，存在副作用；建议对非 GET 请求增加二次确认。
5. Draft-07 `format` 校验需确认具体前后端库默认行为，避免 `email`、`uuid`、`date-time` 只做注解不做实际校验。
6. SSRF 防护建议作为后端请求测试的必备安全项，否则 API 中心可能被用于探测内网。
7. 输入 Schema 不应混合描述 URL 参数和 Body 参数；建议输入 Schema 只描述 Body，URL 参数通过 `{变量名}` 自动生成映射变量。
8. 删除 API 编码后，流程引用 API 应使用系统生成 ID；如果需要跨环境迁移，后续可单独设计导入导出映射机制。

## 九、验收标准

- 页面标题和入口展示为“API中心”。
- 列表组织为 API 组和 API 项。
- 新增按钮显示“新增API”。
- 无能力类型筛选。
- 无发布快照功能入口。
- API 项编辑表单无“能力”文案残留。
- API 项编辑表单不包含“API 编码”。
- 输入 Schema 与输出 Schema 不符合 Draft-07 时不能保存。
- `email`、`uuid`、`date-time` format 校验生效。
- Header 可为空。
- 输入 Schema 只描述 Body 参数。
- URL 中的 `{变量名}` 能自动生成 URL 变量映射输入项。
- 请求测试失败时允许继续编辑但禁止保存。
- 请求测试成功后修改关键字段会使测试状态失效。
- Header 后端加密存储，回显明文，日志脱敏。
