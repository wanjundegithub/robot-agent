from src.core.costing import budget_alert_evaluator, cost_tracker, estimate_tokens


def test_estimate_tokens_returns_stable_minimum_value():
    assert estimate_tokens("") == 0
    assert estimate_tokens("abc") == 1


def test_cost_tracker_calculates_model_cost():
    cost = cost_tracker.calculate_cost("gpt-4.1-mini", 1000, 500)

    assert cost > 0
    assert round(cost, 6) == cost


def test_budget_alert_evaluator_emits_workflow_alert():
    alerts = budget_alert_evaluator.evaluate("flight_booking", "demo-user", 85.0)

    assert any(alert.scope == "workflow" for alert in alerts)
