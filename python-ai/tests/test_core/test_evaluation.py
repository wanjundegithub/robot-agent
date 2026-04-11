from src.core.evaluation import rag_evaluator


def test_rag_evaluator_returns_aggregate_metrics():
    result = rag_evaluator.evaluate_dataset()

    assert result["dataset_size"] >= 1
    assert 0.0 <= result["hit_rate"] <= 1.0
    assert "results" in result
