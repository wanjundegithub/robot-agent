from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict

from .context import ExecutionContext


@dataclass
class PlanningContext:
    context: ExecutionContext
    workflow: Dict[str, Any]
    current_node_id: str
    current_node: Dict[str, Any]
    transitions: Dict[str, Any] = field(default_factory=dict)


class ContextAssembler:
    def build(self, context: ExecutionContext, workflow: Dict[str, Any], current_node_id: str) -> PlanningContext:
        node_def = workflow.get("nodes", {}).get(current_node_id)
        if node_def is None:
            raise ValueError(f"Node not found in workflow: {current_node_id}")
        return PlanningContext(
            context=context,
            workflow=workflow,
            current_node_id=current_node_id,
            current_node=node_def,
            transitions=dict(workflow.get("transitions", {})),
        )
