
1.输入 SCHEMA (Draft-07 标准)
这个输入 Schema 展示了如何使用 Draft-07 的特性来进行字符串长度限制、正则表达式校验、内置格式（如 Email）校验以及枚举值定义。
{
"$schema": "http://json-schema.org/draft-07/schema#",
"$id": "https://your-domain.com/schemas/create-user-input.json",
"type": "object",
"title": "创建用户_输入参数",
"description": "用于校验创建新用户接口的请求体数据格式",
"properties": {
"username": {
"type": "string",
"description": "用户登录名，仅允许大小写字母和数字",
"minLength": 4,
"maxLength": 20,
"pattern": "^[a-zA-Z0-9]+$"
},
"email": {
"type": "string",
"description": "用户联系邮箱",
"format": "email"
},
"age": {
"type": "integer",
"description": "用户年龄，必须满足法定年龄 18 岁",
"minimum": 18,
"maximum": 120
},
"role": {
"type": "string",
"description": "系统角色分配",
"enum": ["admin", "editor", "viewer"],
"default": "viewer"
}
},
"required": [
"username",
"email"
],
"additionalProperties": false
}
Draft-07 关键点解析：

$schema: 明确指定了使用 Draft-07 的核心校验元模式（Meta-schema）。

$id: (可选) 为当前 Schema 提供一个全局唯一的 URI 标识。

format: 使用了 "email"，网关或校验器会自动按照 RFC 5322 标准校验邮箱格式。

pattern: 支持正则表达式进行严格格式卡控。

additionalProperties: false: 表示除了 properties 里定义的四个字段外，不允许前端传入任何其他多余的字段（严格模式）。

2.输出 SCHEMA (Draft-07 标准)
输出 Schema 通常用于记录和约束 API 响应给客户端的数据结构。这里展示了嵌套对象以及 uuid 和 date-time 格式的应用。

JSON
{
"$schema": "http://json-schema.org/draft-07/schema#",
"$id": "https://your-domain.com/schemas/create-user-output.json",
"type": "object",
"title": "创建用户_返回结果",
"description": "定义 API 处理完成后的标准 JSON 响应结构",
"properties": {
"code": {
"type": "integer",
"description": "业务状态码，20000 代表业务成功"
},
"message": {
"type": "string",
"description": "前端可以直接展示的提示信息"
},
"data": {
"type": "object",
"description": "成功时返回的实体数据",
"properties": {
"userId": {
"type": "string",
"description": "系统分配的全局唯一 ID",
"format": "uuid"
},
"createdAt": {
"type": "string",
"description": "账号创建的 UTC 时间",
"format": "date-time"
}
},
"required": [
"userId",
"createdAt"
]
}
},
"required": [
"code",
"message"
]
}
Draft-07 关键点解析：

format: "uuid": 规定返回的 userId 必须是标准的 UUID 字符串格式。

format: "date-time": 规定返回的时间必须是符合 RFC 3339 标准的时间字符串（例如 2026-05-30T22:31:09Z）。