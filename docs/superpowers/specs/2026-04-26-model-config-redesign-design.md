# 模型配置页与统一模型调用重构设计

**日期：** 2026-04-26

## 目标

本次重构围绕“模型配置页”和“全系统模型调用收口”两件事展开，目标如下：

1. 将当前模型配置页重构为真正可维护的双栏工作区，删除不再需要的业务模型配置区域。
2. 保留 `provider` 作为独立概念，但右侧主视图改为“模型记录列表”，支持分页、编辑、删除。
3. 删除“默认 OpenAI 提供方”相关前端文案、控件和对应后端功能。
4. 删除旧的 `profile / purpose` 语义，不再以业务用途绑定模型。
5. 在 Java 后端提供统一模型调用封装，要求调用方输入和输出尽量简单。
6. 将工作流、能力中心、执行链路等所有模型调用入口统一改为按“模型记录”调用。
7. 本次为硬切方案：旧数据不保留，不做兼容迁移，不保留旧 `profile` 兼容层。
8. 全链路保持 UTF-8 无 BOM，不允许因重构引入乱码。

## 范围

### 纳入本次设计

- 模型配置页的页面布局、交互、分页和删除约束重构。
- `provider` 资源模型保留与简化。
- 新增独立“模型记录”实体。
- Java 后端模型配置接口重构。
- Java 后端统一模型调用服务设计。
- 工作流、执行链路、能力中心等模型引用方式由 `profile_ref` 切换到 `model_code`。
- 测试策略、删除策略、回归范围和上线边界。

### 明确不纳入本次设计

- 不保留旧 `LlmModelProfile` 数据迁移兼容层。
- 不保留“按场景代码自动选模型”的调用模式。
- 不在本次设计中扩展新的模型路由策略、模型推荐能力或自动兜底策略。
- 不新增单独的 provider 管理页面。

## 现状问题

### 1. 当前页面概念混杂

现有模型配置页把两类职责混在一起：

- 上半部分维护 provider 草稿
- 下半部分维护业务模型用途绑定

这导致“服务商配置”和“模型使用配置”耦合严重，页面重心不清晰，且下半部分业务模型区会占据大量空间。

### 2. 当前右侧没有“已保存模型记录列表”

你已经确认右侧需要显示分页模型列表，并支持编辑和删除。但当前页面没有真正的“模型记录”概念，只有：

- `provider`
- `profile`

而 `profile` 本质上是按 `purpose` 做绑定，不是通用的模型记录。

### 3. 当前 profile 语义与目标冲突

当前 `LlmModelProfile` 包含：

- `purpose`
- `fallback_profile_code`
- 按用途预置采样参数

这套设计适合“按场景选模型”，但你已经明确要求：

- 统一调用按模型记录
- 右侧显示模型记录列表
- 旧的业务模型配置整块删除

因此 `profile` 语义需要整体退出。

### 4. 当前全系统模型调用未统一收口

目前工作流、执行服务、前端设计器默认配置等位置仍存在：

- `intent_profile_ref`
- `model_profile_ref`
- `*_profile_ref`

这意味着模型选择逻辑分散在多个模块中，后续维护成本高，接口语义也不统一。

### 5. provider 仍错误承载“默认模型”概念

当前 `LlmProviderConfig` 中的 `default_model_code` 实际让 provider 同时承担：

- 厂商连接信息
- 默认模型选择

但重构后真正应该被调用的是“模型记录”，真实上游模型也应挂在模型记录上，而不是挂在 provider 上。

## 已确认约束

以下约束已经确认并作为本设计的正式前提：

1. 保留 `provider` 独立概念。
2. 删除业务模型配置区域，前后端逻辑一起删除。
3. 右侧主区域展示“模型记录列表”，不是服务商列表。
4. 统一调用服务按“模型记录”调用，不按场景代码调用。
5. 当前页仍要维护 provider。
6. 旧数据不需要保留，允许直接删除旧 `profile` 数据和相关逻辑。
7. 页面重构后左右区域需要占满可用屏幕，底部和右侧不留空白。

## 方案比较

### 方案 A：继续复用 `LlmModelProfile`，只做页面改名

做法：

- 前端把 `profile` 伪装成模型记录
- 后端保留 `purpose` 字段但不再显示
- 统一调用服务继续基于 `profile_code`

优点：

- 改动相对少

缺点：

- 数据模型仍然是错的
- 代码语义会长期残留 `purpose/profile`
- 与“按模型记录调用”的目标不一致

### 方案 B：新增独立模型记录实体，硬切删除旧 profile

做法：

- 保留 `LlmProviderConfig`
- 新增独立 `LlmModelRecord`
- 统一调用服务只按 `model_code` 工作
- 所有 `profile_ref` 引用直接改为 `model_code`
- 旧 profile 相关代码、接口、数据全部删除

优点：

- 数据模型最清晰
- 页面结构与后端语义一致
- 后续扩展能力最稳

缺点：

- 改动面大
- 需要同步替换工作流、执行链路、能力中心引用

### 结论

采用方案 B。

原因：

- 你已经明确右侧主视图必须是“模型记录列表”
- 统一调用必须按“模型记录”调用
- 旧数据不需要保留

因此没有必要继续保留 `profile` 兼容层，应该直接建立正确的实体和引用关系。

## 总体设计

### 一、页面布局重构

页面重构为真正的双栏工作区。

#### 左栏：provider 维护区

左侧只负责维护 provider，本次保留的字段如下：

- `provider_code`
- `provider_name`
- `provider_type`
- `base_url`
- `api_key_secret_ref`
- `enabled`

删除以下内容：

- “默认 OpenAI 提供方”下拉框
- “当前草稿: 默认 OpenAI 提供方”文案
- `default_model_code` 作为 provider 默认模型的配置与展示
- 业务模型配置整块

交互要求：

- 左栏表单垂直铺满可用高度
- 左栏内部滚动，外层不出现底部留白
- 保留“测试连通性”和“保存 provider”
- provider 删除功能可以放在左栏顶部或编辑态工具区

#### 右栏：模型记录列表区

右侧是页面主视图，用于维护模型记录。

右栏包含三个层次：

1. 顶部工具栏
2. 中部列表区
3. 底部分页栏

顶部工具栏建议包含：

- 新建模型记录
- 关键字搜索
- provider 过滤
- 启用状态过滤

列表字段建议为：

- `model_code`
- `model_name`
- `provider_name`
- `provider_type`
- `upstream_model_code`
- `capabilities`
- `enabled`
- `updated_at`

支持动作：

- 新建
- 编辑
- 删除
- 启用 / 禁用

分页要求：

- 默认按 `updated_at desc` 排序
- 支持服务端分页
- 推荐默认 `pageSize = 10` 或 `12`
- 分页栏固定在右栏底部，列表内容区单独滚动

#### 全屏占用要求

布局必须满足：

- `ModelConfigPanel` 自身占满主内容区域高度
- 左右栏共同占满宽度
- 不允许页面底部留下大块空白
- 不允许右侧列表区因内容少而塌陷

建议主区域采用：

- 外层 `flex`
- 内层 `min-h-0`
- 左右子区 `h-full`
- 列表区使用内部滚动容器

### 二、数据模型重构

#### 保留 `LlmProviderConfig`

provider 继续作为连接配置实体存在，但职责收缩为“提供方连接信息”。

保留字段：

- `provider_code`
- `provider_name`
- `provider_type`
- `base_url`
- `api_key_secret_ref`
- `enabled`
- `created_at`
- `updated_at`

删除字段：

- `default_model_code`

原因：

- provider 不再承载默认模型概念
- 真正可被调用的模型由模型记录自己定义

#### 新增 `LlmModelRecord`

新增独立模型记录实体，作为统一调用主键。

建议字段如下：

- `model_code`
- `model_name`
- `provider_code`
- `upstream_model_code`
- `capabilities_json`
- `default_system_prompt`
- `default_options_json`
- `enabled`
- `created_at`
- `updated_at`

字段说明：

##### 1. `model_code`

- 业务内唯一编码
- 全系统统一调用主键
- 工作流、能力中心、执行服务都引用它

##### 2. `model_name`

- 用于页面展示和搜索

##### 3. `provider_code`

- 关联 provider

##### 4. `upstream_model_code`

- 真实上游模型名
- 例如 `doubao-seed-2-0-pro-260215`

##### 5. `capabilities_json`

- 描述模型支持能力
- 建议支持：
  - `text`
  - `vision`
  - `json`
  - `stream`

##### 6. `default_system_prompt`

- 可空
- 作为统一调用默认系统提示词

##### 7. `default_options_json`

- 模型默认调用参数
- 替代旧 `profile` 上的：
  - `temperature`
  - `top_p`
  - `max_tokens`
  - `timeout_sec`
  - `response_format`

建议统一放在 JSON 中，减少后续表结构膨胀。

#### 删除 `LlmModelProfile`

以下内容整体删除：

- 实体 `LlmModelProfile`
- repository
- controller 接口
- service 逻辑
- 前端 `ModelProfileConfig`
- `purpose`、`fallback_profile_code`
- 所有 `profile` 相关测试和初始化数据

### 三、接口设计

#### provider 资源

保留并简化以下接口：

- `GET /api/model-config/providers`
- `POST /api/model-config/providers`
- `PUT /api/model-config/providers/{providerCode}`
- `DELETE /api/model-config/providers/{providerCode}`
- `POST /api/model-config/providers/validate-draft`

其中：

- `validate-draft` 继续用于左栏测试连通性
- `DELETE provider` 必须检查是否仍有模型记录引用

#### model-record 资源

新增以下接口：

- `GET /api/model-config/models`
- `POST /api/model-config/models`
- `GET /api/model-config/models/{modelCode}`
- `PUT /api/model-config/models/{modelCode}`
- `DELETE /api/model-config/models/{modelCode}`
- `POST /api/model-config/models/{modelCode}/validate`
- `POST /api/model-config/models/{modelCode}/test-chat` 可保留为调试接口

`GET /api/model-config/models` 需要支持：

- `page`
- `pageSize`
- `keyword`
- `providerCode`
- `enabled`

搜索匹配字段：

- `model_code`
- `model_name`
- `upstream_model_code`

### 四、统一模型调用服务

Java 后端新增统一调用层，例如 `UnifiedModelService`。

原则：

- 全系统只允许通过这层调用模型
- 调用方不再关心 provider 协议细节
- 只传最少输入，拿最稳定输出

#### 统一输入

建议统一请求对象包含：

- `modelCode`
- `messages`
- `systemPrompt`
- `options`
- `responseFormat`
- `stream`

其中：

- `options` 允许覆盖 `default_options_json`
- `responseFormat` 用于结构化输出场景
- `stream` 用于流式场景

#### 统一输出

建议统一返回对象包含：

- `text`
- `structuredData`
- `finishReason`
- `usage`
- `rawProviderResponse`

说明：

- `text` 是默认主输出
- `structuredData` 仅结构化模式下返回
- `usage` 统一封装 token 统计
- `rawProviderResponse` 仅用于调试和审计

#### 统一调用服务职责

这层内部统一负责：

1. 根据 `modelCode` 查找 `LlmModelRecord`
2. 查找关联 `provider`
3. 组装不同厂商的请求协议
4. 鉴权、超时、重试
5. 流式与非流式处理
6. 结构化输出提取
7. 文本输出提取
8. 错误码标准化
9. 审计日志与耗时记录

#### 明确不支持的模式

本次重构后明确不支持：

- 按 `scene_code` 选模
- 按 `purpose` 选模
- 自动 fallback 到 `fallback_profile_code`

调用方必须显式提供 `modelCode`。

### 五、全系统引用方式重构

旧引用方式中的以下字段必须退出：

- `intent_profile_ref`
- `model_profile_ref`
- `*_profile_ref`
- `intent_profile_code`

新系统统一改为引用 `model_code`。

#### 工作流

工作流定义中的模型绑定字段，统一改为：

- `routing_model_code`
- `default_model_code`
- 节点级 `model_code`

不再保存任何 `profile_ref`。

#### 执行链路

`ExecutionService`、`WorkflowService`、`PythonClient` 都必须改为：

- 传递 `model_code`
- 调用 `UnifiedModelService`

不允许继续拼装 `profile` 语义中间层。

#### 能力中心

能力中心内所有直接或间接调用模型的地方，统一改为：

- 配置模型记录编码
- 执行时通过统一服务调用

### 六、删除策略与约束

#### 删除 provider

删除 provider 前必须检查是否存在模型记录引用。

若仍有引用：

- 拒绝删除
- 返回引用数量，必要时返回模型记录编码列表

#### 删除 model-record

删除模型记录前必须检查：

- 工作流定义是否引用该 `model_code`
- 能力配置是否引用该 `model_code`
- 其他运行配置是否引用该 `model_code`

默认策略建议为：

- 支持禁用
- 真删除只允许未被引用且已禁用的模型记录

### 七、错误处理

统一模型调用服务需要提供标准化错误码，至少包含：

- `MODEL_NOT_FOUND`
- `MODEL_DISABLED`
- `PROVIDER_NOT_FOUND`
- `PROVIDER_DISABLED`
- `PROVIDER_AUTH_INVALID`
- `PROVIDER_TIMEOUT`
- `PROVIDER_RATE_LIMITED`
- `PROVIDER_BAD_RESPONSE`

要求：

- 上游 provider 的原始错误可以记录到日志
- 对调用方返回稳定可判断的内部错误码

### 八、旧数据处理策略

本次为硬切方案，旧数据不保留。

执行要求：

1. 不做 `LlmModelProfile -> LlmModelRecord` 的保留迁移。
2. 旧 `profile` 表数据可直接清理。
3. 初始化数据改为直接创建新的 provider 和 model record。
4. 所有旧工作流、旧默认配置中的 `profile_ref` 不做兼容读取。
5. 如果系统内已有旧工作流依赖这些字段，需要同步更新初始化样例和测试数据。

原因：

- 你已明确旧数据不需要保留
- 继续保留旧数据只会拖慢结构清理

### 九、测试策略

#### 前端测试

前端至少覆盖以下内容：

1. 页面删除业务模型配置后正常渲染。
2. “默认 OpenAI 提供方”相关控件和文案完全移除。
3. 左栏 provider 编辑正常保存。
4. 右栏模型记录列表支持分页、搜索、编辑、删除。
5. 页面在左右双栏布局下无底部留白。

建议增加一条 E2E：

- 新建 provider
- 新建模型记录
- 编辑模型记录
- 禁用或删除模型记录

#### Java 后端测试

新增或改造以下测试：

1. provider 删除引用校验
2. model record 分页查询
3. model record 删除保护
4. `UnifiedModelService`：
   - 根据 `modelCode` 正确解析模型记录和 provider
   - 不同 provider 协议组装正确
   - 文本输出提取正确
   - 错误码映射正确

#### 回归范围

必须重点回归以下模块：

- `ModelConfigPanel`
- `ExecutionService`
- `WorkflowService`
- `PythonClient`
- 能力中心相关模型调用入口
- 初始化数据与样例定义

### 十、实施顺序建议

虽然本次是硬切方案，但实施仍建议拆成两个连续步骤：

#### 第一步：建立新结构并替换引用

- 新增 `LlmModelRecord`
- 新增 `UnifiedModelService`
- 改造前端页面
- 所有模型调用入口改为 `model_code`

#### 第二步：删除旧结构

- 删除 `LlmModelProfile`
- 删除 profile 相关接口、DTO、测试、初始化数据
- 删除前端 profile 类型和业务模型配置 UI

这样做的原因不是保留兼容，而是为了让提交边界更清晰，更容易定位回归。

## 验收标准

满足以下条件，才能认为本次重构完成：

1. 页面左侧只维护 provider，右侧显示分页模型记录列表。
2. 页面底部和右侧不再出现明显留白。
3. “默认 OpenAI 提供方”相关文案、控件和后端功能全部删除。
4. 业务模型配置整块及相关前后端逻辑全部删除。
5. 系统新增独立 `LlmModelRecord`，并删除 `LlmModelProfile`。
6. 全系统模型调用统一按 `model_code` 收口。
7. Java 后端统一模型调用服务对调用方暴露简单稳定的输入输出。
8. provider 删除与模型记录删除存在引用保护。
9. 全链路 UTF-8 文本保持完整，无乱码。

## 风险与控制

### 风险 1：引用替换不完整

控制：

- 全仓搜索 `profile_ref`、`intent_profile_code`、`profile_code`
- 替换后增加回归测试

### 风险 2：页面重构后仍有布局留白

控制：

- 明确采用 `flex + min-h-0 + internal scroll`
- 前端联调时重点检查不同内容高度场景

### 风险 3：删除旧 profile 代码后初始化或测试断裂

控制：

- 同步更新初始化数据和所有相关测试样例
- 不允许保留半旧半新的样例定义

### 风险 4：统一调用服务抽象过重

控制：

- 统一服务只封装必要输入输出
- 不在第一版引入场景路由、自动 fallback、复杂策略编排

## 结论

本次模型配置重构的核心，不是单纯把页面改成双栏，而是借页面重构机会把系统中的“provider / profile / purpose”旧模型彻底替换为：

- `provider`
- `model record`
- `UnifiedModelService`

这样才能同时满足：

- 页面右侧显示真实模型记录列表
- 全系统显式按模型记录调用
- 调用接口简单稳定
- 后续扩展新的模型能力不会继续受旧 `purpose/profile` 设计牵制

本设计确认后，下一步应进入独立 implementation plan，按前端、Java 后端、Python 链路和回归验证拆分实施。
