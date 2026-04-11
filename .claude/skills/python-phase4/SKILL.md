---
name: python-phase4
description: Implement the Phase 4 Python runtime: dynamic thresholds, subflow recommendation, evaluation execution, cost events, experiment tags, and replay events.
---

# Python Phase 4

先阅读:
- `../phase4-scope/reference.md`

## 你要交付的东西

- `python-ai/**` 下的 Phase 4 运行时能力
- 动态阈值调整
- 智能子流程推荐
- RAG 评测执行与结果输出
- 成本追踪事件和预算预警输入
- A/B 实验字段输出
- 执行回放所需事件和重放产物

## 实现约束

- 继续使用 Java -> Python 的 HTTP + SSE 协议，不改变浏览器接入路径。
- 动态阈值与推荐必须建立在 Phase 3 路由与子流程体系之上。
- 评测、成本、实验和回放事件必须可追踪、可对账、可脱敏。
- 不能把 Phase 4 的智能化能力扩成自动改工作流定义或无边界自学习系统。
- 安全规则必须继续和 `security-phase4` 对齐，不能为评测和回放开后门。

## 重点检查

- 阈值调整输入输出是否清晰且可回退
- 推荐结果是否具备版本、分数和理由
- RAG 评测结果是否可复跑和可比较
- 成本事件是否包含模型、tokens、cost 和 execution 关联字段
- 回放事件是否足够让 Java 聚合层和前端做重现与比对
