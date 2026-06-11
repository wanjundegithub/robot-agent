# 函数片段节点设计方案

## 目标

函数节点用于处理工作流变量。用户不需要编写完整 `def` 函数，只需要填写一段 Python 函数体代码片段。系统负责包装、校验、执行、捕获日志，并把修改后的变量写回工作流上下文。

## 技术选型

采用 Python，不采用 JS。

原因：

- 当前工作流执行器在 `python-ai`
- 变量上下文、节点调度、测试运行都在 Python 服务内
- Python 片段可以直接复用现有执行上下文
- JS 会引入额外运行时、沙箱、跨语言变量序列化和部署复杂度

## 节点配置

```json
{
  "language": "python",
  "function_name": "处理订单变量",
  "code": "print('开始处理')\nctx['local']['result'] = 'ok'",
  "timeout_ms": 3000
}
```

说明：

- `function_name`：函数片段名称，用于 UI 展示、日志、调试，不要求代码中存在同名函数
- `code`：Python 函数体片段，不要求包含 `def`
- `timeout_ms`：运行超时时间

不再支持：

- `operation_type`
- `assignments`
- `input_mapping`
- `output_mapping`
- 多语言选择
- JS 脚本

## 代码片段模型

用户输入：

```python
print("开始处理")
ctx["local"]["result"] = "ok"
```

系统内部包装为：

```python
def __robot_function__(ctx):
    print("开始处理")
    ctx["local"]["result"] = "ok"
```

因此用户可以写普通函数体代码，也可以写：

```python
if ctx["global"].get("user_name"):
    ctx["local"]["greeting"] = "你好，" + ctx["global"]["user_name"]
```

也允许显式提前结束：

```python
if not ctx["local"].get("order_id"):
    print("缺少订单号")
    return

ctx["local"]["checked"] = True
```

## 变量上下文

运行时系统传入固定变量：

```python
ctx = {
    "global": {},
    "local": {}
}
```

含义：

- `ctx["global"]`：全局变量，对应 session 级变量
- `ctx["local"]`：局部变量，对应 execution 级变量

函数片段可以直接读取和修改：

```python
name = ctx["global"].get("user_name")
ctx["local"]["message"] = name + " 已处理"
```

## 返回规则

函数片段不要求返回值。

允许：

```python
ctx["local"]["result"] = "ok"
```

允许：

```python
return
```

允许：

```python
return ctx
```

系统以执行后的 `ctx` 为准，不依赖返回值。

执行完成后：

- `ctx["global"]` 写回全局变量
- `ctx["local"]` 写回局部变量
- `print()` 内容进入调试日志

## 前端页面设计

函数节点属性面板只保留：

- 节点名称
- 函数名称
- Python 函数片段输入框
- 自动语法检查状态
- 测试按钮

点击测试按钮后打开弹窗：

- 如果函数读取了 `ctx["global"]` / `ctx["local"]` 中的变量，则显示对应测试变量输入框
- 如果函数没有读取变量，则直接显示可运行状态
- 弹窗内展示测试结果、标准输出和错误信息

测试变量输入示例：

```json
{
  "global": {
    "user_name": "张三"
  },
  "local": {
    "order_id": "A001"
  }
}
```

测试结果展示：

- 是否成功
- 修改后的全局变量
- 修改后的局部变量
- `print` 日志
- 错误信息
- 错误行号/列号
- 执行耗时

## 自动语法检查

前端在用户停止输入后自动检查，建议 debounce 500ms。

检查内容：

- Python 片段包装后语法是否通过
- 是否命中禁用语法
- 是否存在危险调用
- 是否存在危险属性访问
- 错误返回具体行号和列号

## 后端接口

Python 服务新增：

```text
POST /api/function-fragments/validate
POST /api/function-fragments/test-run
```

Java 后端新增代理接口：

```text
POST /api/workflows/function-fragments/validate
POST /api/workflows/function-fragments/test-run
```

前端只调用 Java 后端，不直连 Python 服务。

## 测试运行响应

```json
{
  "success": true,
  "variables": {
    "global": {
      "user_name": "张三"
    },
    "local": {
      "order_id": "A001",
      "result": "ok"
    }
  },
  "stdout": "开始处理\n",
  "error_message": null,
  "line": null,
  "column": null,
  "duration_ms": 12
}
```

## 安全规则

禁止：

- `import`
- `open`
- `eval`
- `exec`
- `__import__`
- `compile`
- `globals`
- `locals`
- `vars`
- `dir`
- `getattr`
- `setattr`
- `delattr`
- `input`
- 文件访问
- 网络访问
- 系统调用
- `__dunder__` 属性访问

运行限制：

- 子进程隔离执行
- 超时强制终止
- 限制 stdout 大小
- 限制变量 JSON 体积
- 禁止修改 `ctx["global"]` 和 `ctx["local"]` 为非对象

## 错误场景

以下情况执行失败：

- 语法错误
- 命中禁用语法
- 运行超时
- 脚本抛异常
- `ctx["global"]` 变成非对象
- `ctx["local"]` 变成非对象
- 输出日志过大
- 修改后变量体积过大

## 最终定位

函数节点不是“返回结果节点”，而是“变量处理片段节点”。

用户只写 Python 处理片段，系统负责包装执行。函数片段可以没有返回值，最终以 `ctx` 的修改结果作为节点输出。
