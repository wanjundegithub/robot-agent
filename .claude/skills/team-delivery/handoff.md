# Phase 3 Handoff Checklist

## 角色分工

- `tech-leader`: 产出 Phase 3 拆解方案、接口冻结清单、风险清单、最终评审意见
- `backend-java`: 产出混合路由入口、切换/恢复 HTTP 与 WebSocket 合同、Session / Execution 聚合与审计实现
- `python-dev`: 产出完整状态机、显式中断恢复、优先级调度、并发控制、运行时安全挂点和 SSE 事件
- `frontend`: 产出切换确认、恢复提示、并发 execution 展示和安全反馈界面
- `security`: 产出 Prompt 防护、输出校验、脱敏规则、攻击样例与残余风险清单
- `qa`: 产出单测、集成测试、闭环验收用例、安全回归与观测验证

## 先冻结的合同

- `entry_rule` 结构、候选工作流筛选规则、模型分类输出、固定置信度阈值和路由决策返回体
- 会话状态机状态集合、状态转换规则，以及 `WAITING_USER` / `WAITING_TOOL` / `SUSPENDED` 的恢复入口
- `suspended_stack` 或等价结构的快照字段、恢复顺序和清理规则
- 调度优先级来源、并发限制、锁语义、排队策略和冲突处理规则
- Java 到 Python 的执行请求体、SSE 事件名、切换/恢复语义和追踪字段
- Java 到前端的 WebSocket 事件模型，以及切换确认 / 恢复提示需要的最小字段集
- Prompt 清洗规则、结构化输出 schema 来源、校验失败口径、敏感字段字典和脱敏覆盖范围
- 审计、日志、Redis、Telemetry 的关键字段和命名

## 并行策略

1. `tech-leader` 冻结目录结构、合同和验收边界
2. `backend-java` 与 `python-dev` 对齐路由、状态机、切换/恢复和事件合同后并行开发
3. `security` 并行冻结安全策略，并在 Java / Python / Frontend 上给出接入要求
4. `frontend` 根据合同并行开发切换、恢复和并发展示界面
5. `qa` 从合同和 Phase 3 验收标准反推测试
6. `tech-leader` 做最终集成审查与范围回收

## 角色输出格式

- 变更摘要
- 影响的接口、状态、表或事件
- 影响的 Redis Key、指标、追踪字段或安全规则
- 未完成项
- 风险或阻塞项
- 对验收标准的覆盖说明

## 定义完成

- 流程执行中可中断切换
- 切换后可恢复原流程
- 多个流程可并发执行
- Prompt 注入被防护
- 结构化输出校验可验证
- 敏感信息被脱敏
- 无明显 Phase 4 / Phase 5 范围漂移，且不回归 Phase 1 / Phase 2
