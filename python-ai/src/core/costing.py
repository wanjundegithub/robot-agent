from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List


MODEL_PRICING: Dict[str, Dict[str, float]] = {
    "gpt-4.1": {"input": 0.03, "output": 0.06},
    "gpt-4.1-mini": {"input": 0.00015, "output": 0.0006},
    "claude-3-opus": {"input": 0.015, "output": 0.075},
    "claude-3-haiku": {"input": 0.00025, "output": 0.00125},
}

BUDGET_ALERTS = {
    "workflow": {
        "flight_booking": {"daily_limit": 100.0, "alert_at": 80.0},
        "hotel_booking": {"daily_limit": 80.0, "alert_at": 60.0},
        "general_query": {"daily_limit": 40.0, "alert_at": 20.0},
    },
    "user": {"daily_limit": 10.0, "alert_at": 8.0},
    "global": {"daily_limit": 1000.0, "alert_at": 800.0},
}


def estimate_tokens(text: str) -> int:
    if not text:
        return 0
    return max(1, len(text) // 4)


class CostTracker:
    def calculate_cost(self, model: str, input_tokens: int, output_tokens: int) -> float:
        pricing = MODEL_PRICING.get(model)
        if not pricing:
            return 0.0
        input_cost = (input_tokens / 1000) * pricing["input"]
        output_cost = (output_tokens / 1000) * pricing["output"]
        return round(input_cost + output_cost, 6)

    def build_cost_payload(
        self,
        *,
        model: str,
        workflow_code: str,
        workflow_version: str,
        execution_id: str,
        session_id: str,
        user_id: str,
        input_tokens: int,
        output_tokens: int,
    ) -> Dict[str, object]:
        cost = self.calculate_cost(model, input_tokens, output_tokens)
        return {
            "model": model,
            "workflow_code": workflow_code,
            "workflow_version": workflow_version,
            "execution_id": execution_id,
            "session_id": session_id,
            "user_id": user_id,
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "cost": cost,
        }


@dataclass
class BudgetAlert:
    scope: str
    scope_id: str
    total_cost: float
    threshold: float
    message: str


class BudgetAlertEvaluator:
    def evaluate(self, workflow_code: str, user_id: str, total_cost: float) -> List[BudgetAlert]:
        alerts: List[BudgetAlert] = []
        workflow_limit = BUDGET_ALERTS["workflow"].get(workflow_code)
        if workflow_limit and total_cost >= workflow_limit["alert_at"]:
            alerts.append(
                BudgetAlert(
                    scope="workflow",
                    scope_id=workflow_code,
                    total_cost=total_cost,
                    threshold=float(workflow_limit["alert_at"]),
                    message=f"Workflow {workflow_code} cost reached alert threshold",
                )
            )

        user_limit = BUDGET_ALERTS["user"]
        if total_cost >= user_limit["alert_at"]:
            alerts.append(
                BudgetAlert(
                    scope="user",
                    scope_id=user_id,
                    total_cost=total_cost,
                    threshold=float(user_limit["alert_at"]),
                    message=f"User {user_id} cost reached alert threshold",
                )
            )

        global_limit = BUDGET_ALERTS["global"]
        if total_cost >= global_limit["alert_at"]:
            alerts.append(
                BudgetAlert(
                    scope="global",
                    scope_id="all",
                    total_cost=total_cost,
                    threshold=float(global_limit["alert_at"]),
                    message="Global cost reached alert threshold",
                )
            )
        return alerts


cost_tracker = CostTracker()
budget_alert_evaluator = BudgetAlertEvaluator()
