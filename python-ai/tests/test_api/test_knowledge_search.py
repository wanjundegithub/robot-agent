import pytest

from src.api import main


@pytest.mark.asyncio
async def test_knowledge_search_api_returns_hits_and_citations(monkeypatch):
    captured = {}

    class StubKnowledgeStore:
        def search_many(self, **kwargs):
            captured.update(kwargs)
            return [
                {
                    "chunk_id": "chunk_1",
                    "doc_id": "doc_1",
                    "kb_code": "kb_product",
                    "title": "产品手册",
                    "content": "保修期为一年",
                    "score": 0.92,
                }
            ]

    monkeypatch.setattr(main, "get_knowledge_store", lambda: StubKnowledgeStore())
    request = main.KnowledgeSearchRequest(
        query="保修期多久",
        kb_codes=["kb_product"],
        top_k=3,
        score_threshold=0.65,
        embedding_model_code="embedding-qwen3-8b",
        provider_configs=[{"provider_code": "modelscope-embedding"}],
        model_records=[{"model_code": "embedding-qwen3-8b"}],
    )

    payload = await main.search_knowledge(request)

    assert payload["query"] == "保修期多久"
    assert payload["bestScore"] == 0.92
    assert payload["documents"][0]["chunk_id"] == "chunk_1"
    assert payload["citations"] == [{"chunkId": "chunk_1", "docId": "doc_1", "score": 0.92}]
    assert captured["kb_codes"] == ["kb_product"]
    assert captured["embedding_model_code"] == "embedding-qwen3-8b"
