import pytest

from src.core.context import ExecutionContext
from src.core.protection import vector_access_optimizer
from src.nodes.knowledge import KnowledgeNode


@pytest.mark.asyncio
async def test_knowledge_node_retrieves_documents_and_updates_context(monkeypatch):
    vector_access_optimizer.reset()
    context = ExecutionContext(
        execution_id="exec_knowledge",
        session_id="sess_knowledge",
        workflow_code="general_query",
        workflow_version="1.0.0",
        provider_configs={
            "openai-compatible-prod": {
                "provider_code": "openai-compatible-prod",
                "provider_type": "openai_compatible",
                "base_url": "https://llm.example.com/v1",
            }
        },
        model_records={
            "knowledge-answer-v1": {
                "model_code": "knowledge-answer-v1",
                "provider_code": "openai-compatible-prod",
                "upstream_model_code": "qwen-plus",
            }
        },
    )
    context.add_execution_variable("user_message", "我想了解航班退票和改签政策")

    node = KnowledgeNode("retrieve_policy", {
        "config": {
            "knowledge_base_code": "flight_policy_kb",
            "kb_version": "1.0.0",
            "retrieval_mode": "hybrid",
            "top_k": 2,
            "answer_generation": {"enabled": True, "model_code": "knowledge-answer-v1"},
        }
    })

    class StubKnowledgeStore:
        def search(self, **_kwargs):
            return [
                {
                    "doc_id": "doc_1",
                    "content": "航班改签需要遵循改签规则",
                    "score": 0.92,
                }
            ]

    async def fake_completion(**_kwargs):
        return "根据知识库，支持改签。"

    monkeypatch.setattr("src.nodes.knowledge.get_knowledge_store", lambda: StubKnowledgeStore())
    monkeypatch.setattr("src.nodes.knowledge.execute_model_completion", fake_completion)

    result = await node.execute(context)

    assert result["output"]["document_count"] >= 1
    assert context.get_variable("retrieved_docs")
    assert "改签" in context.get_variable("policy_content")
    assert result["output"]["answer"] == "根据知识库，支持改签。"
    assert result["metrics"]["vector_shard"] in {0, 1, 2, 3}
    assert result["metrics"]["vector_cache_hit"] is False


@pytest.mark.asyncio
async def test_knowledge_node_reuses_vector_cache_on_second_search(monkeypatch):
    vector_access_optimizer.reset()
    context = ExecutionContext(
        execution_id="exec_knowledge_cached",
        session_id="sess_knowledge_cached",
        workflow_code="general_query",
        workflow_version="1.0.0"
    )
    context.add_execution_variable("user_message", "我想了解航班退票和改签政策")

    node = KnowledgeNode("retrieve_policy", {
        "config": {
            "knowledge_base_code": "flight_policy_kb",
            "kb_version": "1.0.0",
            "retrieval_mode": "hybrid",
            "top_k": 2
        }
    })

    class StubKnowledgeStore:
        def search(self, **_kwargs):
            return [{"doc_id": "doc_1", "content": "缓存知识", "score": 0.91}]

    monkeypatch.setattr("src.nodes.knowledge.get_knowledge_store", lambda: StubKnowledgeStore())

    await node.execute(context)
    second = await node.execute(context)

    assert second["metrics"]["vector_cache_hit"] is True
