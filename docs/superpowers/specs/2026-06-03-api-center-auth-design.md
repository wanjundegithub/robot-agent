# API 中心鉴权中心设计方案

## 一、背景

当前 API 中心已完成 API 组、API 项、请求 URL、Method、Headers、Schema 与请求测试等能力，但 API 组缺少独立的鉴权中心。现有 API 项要么不配置鉴权，要么通过手写 Header 间接完成鉴权，无法表达组级默认鉴权、单 API 继承/覆盖，也难以支持 Postman 主流鉴权方式。

本方案采用“API组鉴权中心 + API项继承/覆盖”的方案 B，先支持主流静态鉴权：No Auth、API Key、Bearer Token、Basic Auth、Digest Auth。OAuth 2.0、自动刷新 Token、环境变量与跨组凭据复用不纳入本期。

## 二、设计目标

1. API 组提供统一鉴权中心，作为组内 API 的默认鉴权配置。
2. API 项支持继承 API 组鉴权、关闭鉴权或自定义鉴权。
3. 鉴权配置与普通 Headers 分离，但不限制用户在 Headers 中手写鉴权相关参数。
4. 请求测试与工作流运行使用同一套鉴权解析逻辑，避免测试通过但运行失败。
5. 敏感字段加密存储，列表与详情展示脱敏摘要，不明文暴露密钥。
6. 前端弹窗布局、颜色、圆角、间距、按钮风格与现有 API 中心保持一致。

## 三、范围

### 3.1 本期支持

- No Auth：不注入任何鉴权信息。
- API Key：支持添加到 Header 或 Query。
- Bearer Token：生成 `Authorization: Bearer <token>`。
- Basic Auth：生成 `Authorization: Basic <base64(username:password)>`。
- Digest Auth：支持 username/password，并在请求时完成一次 `401 WWW-Authenticate` challenge-response 重试。
- API 组默认鉴权。
- API 项鉴权策略：继承 API 组、不鉴权、自定义鉴权。
- 保存校验、请求测试、运行时解析。
- 敏感字段加密与脱敏展示。

### 3.2 本期不支持

- OAuth 2.0 与自动获取/刷新 Token。
- Postman 环境变量、集合变量、脚本化 pre-request auth。
- 跨 API 组共享凭据库。
- NTLM、AWS Signature、Hawk、Akamai EdgeGrid 等高级签名方式。
- Digest 多轮复杂状态缓存；本期只对单次请求 challenge 重试一次。

## 四、鉴权模型

### 4.1 API 组鉴权

API 组增加默认鉴权配置：

- `authType`：`NO_AUTH | API_KEY | BEARER | BASIC | DIGEST`
- `authConfig`：按类型保存配置 JSON，敏感字段加密。
- `authPreview`：脱敏摘要，用于列表和详情展示。

默认新建 API 组使用 `NO_AUTH`。

### 4.2 API 项鉴权策略

API 项增加鉴权策略：

- `INHERIT`：继承所属 API 组鉴权。新建 API 默认值。
- `NONE`：显式不使用任何鉴权，即使 API 组配置了默认鉴权。
- `CUSTOM`：使用 API 项自己的鉴权配置。

API 项最终有效鉴权规则：

1. `INHERIT` 使用 API 组鉴权。
2. `NONE` 使用 No Auth。
3. `CUSTOM` 使用 API 项自定义鉴权。

### 4.3 与 Headers 的关系

普通 Headers 继续保留，用于业务请求头，如 `Content-Type`、`Accept`、业务追踪 ID 等，也允许用户继续维护鉴权相关参数，例如 `Authorization`、`X-API-Key`、自定义签名 Header 等。

鉴权中心是请求构造的辅助配置，不是唯一鉴权入口。系统不因 Headers 中存在鉴权字段而阻止保存或请求测试。原因：

- API 项选择 `NONE` 时，表示鉴权中心不注入鉴权，但 Headers 仍可携带调用方手写的鉴权参数。
- API 项选择 `CUSTOM` 时，自定义鉴权和业务 Headers 可能共同组成一个完整请求。
- 部分 API 本身没有鉴权，只需要普通 Headers。
- 部分历史 API 已经通过 Headers 配置鉴权，需要保持兼容。

当鉴权中心和 Headers 生成同名 Header 或 Query 参数时，后端按确定性规则合并，不把它视为校验错误。推荐规则是“显式配置优先”：用户在 Headers 中启用的同名 Header 覆盖鉴权中心生成值；API Key 写入 Query 时，如果 URL 中已存在同名查询参数，则保留 URL 中的显式参数，不追加重复参数。前端可以给出非阻断提示，但不能禁止保存或测试。

## 五、各鉴权类型字段

### 5.1 No Auth

无配置字段。

### 5.2 API Key

字段：

- `key`：参数名，必填。
- `value`：参数值，必填，加密保存。
- `addTo`：`HEADER | QUERY`，必填，默认 `HEADER`。

运行时：

- `HEADER`：注入请求 Header。
- `QUERY`：追加到请求 URL 查询参数。

### 5.3 Bearer Token

字段：

- `token`：必填，加密保存。

运行时注入：

- `Authorization: Bearer <token>`

### 5.4 Basic Auth

字段：

- `username`：必填，可加密保存。
- `password`：必填，加密保存。

运行时注入：

- `Authorization: Basic <base64(username:password)>`

### 5.5 Digest Auth

字段：

- `username`：必填，可加密保存。
- `password`：必填，加密保存。
- `realm`：可选。
- `nonce`：可选，一般由服务端 challenge 返回。
- `algorithm`：可选，默认兼容 `MD5`。
- `qop`：可选，默认优先 `auth`。

运行时流程：

1. 首次请求不带 Digest Authorization，或按已有可用配置尝试。
2. 若返回 `401` 且包含 `WWW-Authenticate: Digest ...`，解析 challenge。
3. 根据 method、URI、realm、nonce、qop、algorithm、username、password 生成 Digest Authorization。
4. 重新发送一次请求。
5. 第二次仍失败则返回真实状态码和错误信息。

## 六、后端设计

### 6.1 数据模型

推荐新增 `ApiAuthConfig` 实体，避免把组级和项级鉴权字段塞入现有模型导致职责混乱。

核心字段：

- `id`
- `scopeType`：`GROUP | ITEM`
- `scopeId`：API 组 ID 或 API 项 ID。
- `authType`：鉴权类型。
- `configCiphertext`：加密后的鉴权配置 JSON。
- `preview`：脱敏摘要，如 `Bearer ••••abcd`、`API Key header:X-API-Key`。
- `createdAt`
- `updatedAt`

`ApiItem` 增加：

- `authMode`：`INHERIT | NONE | CUSTOM`，默认 `INHERIT`。

`ApiGroup` 不直接保存敏感字段，只通过 `ApiAuthConfig(scopeType=GROUP, scopeId=groupId)` 关联默认鉴权。

### 6.2 服务拆分

新增或调整服务：

- `ApiAuthConfigService`：保存、读取、校验、脱敏鉴权配置。
- `ApiAuthCryptoService`：从现有 Header 加密服务泛化而来，负责敏感 JSON 加解密。
- `ApiAuthResolver`：根据 API 组和 API 项解析最终有效鉴权，并注入到请求上下文。
- `ApiDigestAuthService`：负责 Digest challenge 解析与 Authorization 生成。

请求测试与运行时必须复用 `ApiAuthResolver`。不能在 `testDraft` 和 `ApiRuntimeResolver` 中各写一套逻辑。

### 6.3 API 接口

在现有 `/api/api-center` 下扩展：

- `GET /groups/{groupId}/auth-config`：读取 API 组默认鉴权。
- `PUT /groups/{groupId}/auth-config`：保存 API 组默认鉴权。
- `GET /groups/{groupId}/items/{apiId}/auth-config`：读取 API 项鉴权策略与自定义鉴权。
- `PUT /groups/{groupId}/items/{apiId}/auth-config`：保存 API 项鉴权策略与自定义鉴权。

也允许在保存组或 API 项详情时内联提交鉴权配置，但后端仍应通过 `ApiAuthConfigService` 统一处理。

### 6.4 保存校验

保存 API 组鉴权时：

- 校验 `authType` 是否在允许列表。
- 校验当前类型必填字段。
- 敏感字段不能为空且不能只由空白字符组成。

保存 API 项时：

- 校验 `authMode`。
- `CUSTOM` 必须提供合法自定义鉴权配置。
- `INHERIT` 不允许携带自定义敏感配置。
- 合法保留普通 Headers 中的鉴权相关参数，不把同名 Header 或 Query 参数视为保存错误。

### 6.5 请求测试

请求测试流程调整：

1. 校验 URL、Method、Schema、URL 变量、Body、普通 Headers。
2. 解析当前草稿下的有效鉴权。
3. 合并普通 Headers 与鉴权注入结果；同名字段按“显式 Headers/URL 参数优先”处理。
4. 构造请求。
5. Digest Auth 在收到 challenge 后自动重试一次。
6. 校验 HTTP 状态码与输出 Schema。
7. 更新 last test 状态。

## 七、前端设计

### 7.1 API 组弹窗

在现有 API 组新建/编辑弹窗中增加“鉴权中心”区域。

布局要求：

- 复用 `api-center-modal`、`form-input`、`form-select`、`form-textarea`、现有按钮样式。
- 卡片背景、边框、圆角、阴影、间距与 API 中心现有弹窗保持一致。
- 区域标题使用现有灰蓝色标题层级，不新增跳脱的品牌色。
- 鉴权类型切换后只展示该类型需要的字段。
- 敏感输入使用 password 类型，并提供“已配置/未配置”的脱敏提示。

### 7.2 API 项弹窗

在 API 项新建/编辑弹窗中增加“鉴权策略”区域，放在请求连接配置之后、Headers 之前。

字段：

- 鉴权策略：继承 API 组、不鉴权、自定义鉴权。
- 继承 API 组时显示组级鉴权摘要。
- 自定义鉴权时显示同 API 组鉴权中心一致的类型与字段配置。

布局要求：

- 与现有 API 项弹窗的双列栅格、输入框宽度、字段间距保持一致。
- 不新增独立主题色，不使用与 API 中心不一致的高饱和背景。
- 错误提示复用现有表单错误文案风格。
- Header 区域保留，并明确文案为“业务 Headers”，避免和鉴权混淆。
- 如果 Headers 中存在 `Authorization`、`X-API-Key` 等常见鉴权字段，只做温和提示，不作为错误阻断。

### 7.3 请求测试弹窗

请求测试弹窗展示当前有效鉴权摘要：

- 继承时显示“继承 API 组：Bearer ••••abcd”等摘要。
- 自定义时显示“自定义：API Key header:X-API-Key”。
- 不展示明文密钥。

测试时使用当前表单草稿里的鉴权配置，而不是只使用已保存配置。

## 八、兼容性与迁移

现有 API 项中的 Headers 保持原样，不自动迁移为鉴权配置，避免误判业务 Header。

对于已有 `Authorization` Header：

- 继续保留在普通 Headers 中。
- 即使用户开启鉴权中心，也不阻止保存和测试。
- 合并时优先使用用户显式配置的 Header。
- 用户可按需保留旧 Header，或手动删除后改用鉴权中心。

默认行为：

- 已有 API 组默认 `NO_AUTH`。
- 已有 API 项默认 `INHERIT`，由于组默认 `NO_AUTH`，实际行为不变。
- 删除 API 项时同步删除该 API 项的自定义鉴权配置。
- 删除 API 组时同步删除组默认鉴权配置和组内 API 项自定义鉴权配置。
- API 项从 `CUSTOM` 切换为 `INHERIT` 或 `NONE` 时清理旧的 API 项自定义鉴权配置，避免敏感配置残留。

## 九、安全要求

1. API Key、Token、Password 等敏感字段必须加密保存。
2. 后端返回详情时默认不返回敏感明文，只返回脱敏摘要和是否已配置。
3. 更新鉴权配置时，如果用户未填写新的敏感值且已有旧值，可保留旧密文。
4. 请求测试结果不得回显请求 Authorization 明文。
5. 日志、异常、审计记录不得输出敏感值。
6. 加密服务继续使用 UTF-8，不能改变文件编码或破坏中文文案。

## 十、验收标准

1. API 组可以配置 No Auth、API Key、Bearer Token、Basic Auth、Digest Auth。
2. API 项可以选择继承 API 组、不鉴权、自定义鉴权。
3. 请求测试与工作流运行使用相同有效鉴权解析结果。
4. API Key Header/Query、Bearer、Basic 能正确注入请求。
5. Digest Auth 能完成一次 401 challenge 后重试。
6. 普通 Headers 中可以保留鉴权字段；与鉴权中心生成字段同名时按明确优先级合并，不阻断保存或测试。
7. API Key 写入 Query 时兼容 URL 模板变量，不因 `{变量}` 触发 URL 解析错误。
8. 敏感字段加密保存，前端不展示明文。
9. 现有 API 项未配置鉴权中心时行为保持不变。
10. 前端弹窗布局、颜色、圆角、间距与现有 API 中心一致。
11. 后端与前端测试覆盖保存、继承、覆盖、同名字段优先级、配置清理和请求测试关键路径。
