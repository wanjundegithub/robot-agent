import pytest

from src.api import main


@pytest.mark.asyncio
async def test_knowledge_search_api_returns_hits_and_citations(monkeypatch):
    captured = {}
    embedded = {}

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

    async def fake_embed_texts_with_model(**kwargs):
        embedded.update(kwargs)
        return [[0.2] * 4096]

    monkeypatch.setattr(main, "get_knowledge_store", lambda: StubKnowledgeStore())
    monkeypatch.setattr(main, "embed_texts_with_model", fake_embed_texts_with_model)
    request = main.KnowledgeSearchRequest(
        query="保修期多久",
        kb_codes=["kb_product"],
        top_k=3,
        score_threshold=0.65,
        embedding_model_code="model-431c4581ab84",
        provider_configs=[{"provider_code": "model-431c4581ab84-provider"}],
        model_records=[{"model_code": "model-431c4581ab84"}],
    )

    payload = await main.search_knowledge(request)

    assert payload["query"] == "保修期多久"
    assert payload["bestScore"] == 0.92
    assert payload["documents"][0]["chunk_id"] == "chunk_1"
    assert payload["citations"] == [{"chunkId": "chunk_1", "docId": "doc_1", "score": 0.92}]
    assert embedded["texts"] == ["保修期多久"]
    assert embedded["model_code"] == "model-431c4581ab84"
    assert captured["kb_codes"] == ["kb_product"]
    assert captured["embedding_model_code"] == "model-431c4581ab84"
    assert captured["embedding"] == [0.2] * 4096
