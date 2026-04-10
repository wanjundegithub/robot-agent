from __future__ import annotations

from typing import Any, Dict, Optional

from src.core.context import ExecutionContext
from src.core.workflow_registry import get_workflow


def _build_node(node_def: Dict[str, Any]):
    from src.nodes import ConditionNode, EndNode, FormNode, KnowledgeNode, LLMNode, StartNode, ToolNode

    node_type = node_def["type"]
    if node_type == "start":
        return StartNode(node_def["id"], node_def)
    if node_type == "end":
        return EndNode(node_def["id"], node_def)
    if node_type == "llm":
        return LLMNode(node_def["id"], node_def)
    if node_type == "condition":
        return ConditionNode(node_def["id"], node_def)
    if node_type == "form":
        return FormNode(node_def["id"], node_def)
    if node_type == "knowledge":
        return KnowledgeNode(node_def["id"], node_def)
    if node_type == "subflow":
        raise ValueError("Nested subflow is not supported in Phase 2 baseline")
    return ToolNode(node_def["id"], node_def)


async def run_subflow(
    parent_context: ExecutionContext,
    subflow_code: str,
    subflow_version: str,
    input_variables: Dict[str, Any],
) -> Dict[str, Any]:
    workflow = get_workflow(subflow_code, subflow_version)
    if workflow is None:
        raise ValueError(f"Subflow not found: {subflow_code}@{subflow_version}")

    context = ExecutionContext(
        execution_id=f"{parent_context.execution_id}:{subflow_code}",
        session_id=parent_context.session_id,
        workflow_code=subflow_code,
        workflow_version=subflow_version,
        trace_id=parent_context.trace_id,
    )
    context.add_execution_variables(input_variables)

    current_node_id: Optional[str] = workflow.get("entry")
    while current_node_id:
        node_def = workflow["nodes"][current_node_id]
        node = _build_node(node_def)
        result = await node.execute(context)
        if node_def["type"] == "form":
            raise ValueError("Subflow does not support form suspension in Phase 2 baseline")
        current_node_id = _next_node(workflow, node_def, result)

    return context.execution_variables


def _next_node(workflow: Dict[str, Any], node_def: Dict[str, Any], result: Dict[str, Any]) -> Optional[str]:
    transitions = workflow.get("transitions", {})
    node_id = node_def["id"]
    if node_def["type"] == "condition":
        branch = result.get("branch")
        return transitions.get(node_id, {}).get(branch)
    return transitions.get(node_id)
