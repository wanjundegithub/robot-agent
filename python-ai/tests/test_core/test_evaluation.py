from src.core import evaluation


def test_rag_evaluator_returns_aggregate_metrics(monkeypatch):
    class StubKnowledgeStore:
        def search(self, **_kwargs):
            return [{"content": "产品保修期为一年"}]

    monkeypatch.setattr(evaluation, "get_knowledge_store", lambda: StubKnowledgeStore())

    result = evaluation.rag_evaluator.evaluate_dataset([
        {
            "query": "产品保修期是什么",
            "expected_terms": ["保修"],
        }
    ])

    assert result["dataset_size"] >= 1
    assert 0.0 <= result["hit_rate"] <= 1.0
    assert "results" in result
