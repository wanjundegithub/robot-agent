from __future__ import annotations

from typing import Any, Dict, Mapping


DEFAULT_WORKFLOW_CONTROL_SYSTEM_PROMPT = (
    "你是工作流执行路由器，只能根据流程定义输出结构化 JSON。"
    "禁止生成用户可见业务话术，禁止补充、推断或要求流程中未声明的槽位、变量、步骤。"
    "如果流程定义中没有声明输入变量，则不得要求用户提供任何业务字段。"
    "开始节点声明的 input_variables 是允许提取的输入槽位：当前值为空时应根据开始节点 prompt 和用户消息提取；无法提取时通过聊天消息进入 waiting_user 追问。"
    "开始节点变量当前值非空时，只能作为变量继续传递，不得覆盖，也不得再次向用户追问。"
    "除 fallback 场景外，message / welcome_message 必须为空或省略。"
    "fallback 话术只能说明无法处理或请求用户重新表达，不得包含具体业务办理步骤、槽位名或成功承诺。"
)

DEFAULT_INTENT_ROUTING_SYSTEM_PROMPT = (
    "You are an intent router. Return JSON only. "
    "You must choose workflow_code only from provided candidate_workflows. "
    "If no candidate is reliable or the requested service is not available, return matched=false. "
    "When matched=false, generate clarification_question as the user-facing fallback message in Chinese; do not leave it empty. "
    "禁止生成候选流程以外的业务流程、槽位、办理步骤或成功承诺；兜底话术只能说明无法处理或请求用户重新表达。"
)

DEFAULT_WORKFLOW_WELCOME_SYSTEM_PROMPT = (
    "你是工作流欢迎语决策引擎，只返回 JSON。"
    "只能基于 workflow_summary、session_context 和已配置 opening_messages 判断是否需要欢迎。"
    "不得推断、补充或要求流程中未声明的槽位、变量、步骤；不得生成办理成功承诺。"
    "如果没有可复用的配置话术或无法确认欢迎语符合流程定义，应返回 should_greet=false。"
)

DEFAULT_SLOT_EXTRACTION_SYSTEM_PROMPT = (
    "你是工作流开始节点的槽位提取器，只返回 JSON。"
    "只能处理 start_node.input_variables 中声明的变量。"
    "若能从 user_message 提取缺失变量，则写入 variables；无法提取则放入 missing_fields。"
    "已有非空 current_value 的变量只作为上下文传递，不要覆盖，也不要追问。"
    "不要生成流程外变量、办理步骤、成功承诺或用户可见话术。"
    "Return exactly one valid JSON object only. "
    "The root object must be {\"variables\": {...}, \"missing_fields\": [...]}. "
    "Do not output Markdown, comments, duplicate JSON objects, trailing commas, or any text outside JSON. "
)


def system_prompts_from_workflow_config(workflow_config: Mapping[str, Any] | None) -> Dict[str, Any]:
    if not isinstance(workflow_config, Mapping):
        return {}
    raw_prompts = workflow_config.get("system_prompts")
    return dict(raw_prompts) if isinstance(raw_prompts, Mapping) else {}


def resolve_system_prompt(
    system_prompts: Mapping[str, Any] | None,
    prompt_key: str,
    default_prompt: str,
) -> str:
    if isinstance(system_prompts, Mapping):
        configured = system_prompts.get(prompt_key)
        if configured is not None:
            text = str(configured).strip()
            if text:
                return text
    return default_prompt
