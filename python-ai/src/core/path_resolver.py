from __future__ import annotations

from dataclasses import dataclass
from typing import Optional


@dataclass
class PathDecision:
    selected_node: Optional[str]
    reason: str


class PathResolver:
    def resolve(self, plan, workflow, context) -> PathDecision:
        selected_node = plan.candidate_nodes[0] if plan.candidate_nodes else None
        if selected_node is None:
            return PathDecision(selected_node=None, reason="no_candidate_nodes")
        return PathDecision(selected_node=selected_node, reason="current_node_selected")
