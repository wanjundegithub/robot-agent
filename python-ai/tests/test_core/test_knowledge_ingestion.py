import pytest

from src.api.models import KnowledgeIngestRequest
from src.core.knowledge_ingestion import ingest_knowledge_document


@pytest.mark.asyncio
async def test_ingest_raw_text_chunks_embeds_and_upserts(monkeypatch):
    captured_rows = []

    class StubKnowledgeStore:
        def upsert_chunks(self, rows):
            captured_rows.extend(rows)
            return len(rows)

    async def fake_embed_texts_with_model(**kwargs):
        assert kwargs["model_code"] == "model-431c4581ab84"
        assert kwargs["expected_dimension"] == 1024
        return [[0.1] * 1024 for _text in kwargs["texts"]]

    monkeypatch.setattr("src.core.knowledge_ingestion.get_knowledge_store", lambda: StubKnowledgeStore())
    monkeypatch.setattr("src.core.knowledge_ingestion.embed_texts_with_model", fake_embed_texts_with_model)

    request = KnowledgeIngestRequest(
        task_id="task_001",
        doc_id="doc_001",
        kb_code="kb_product",
        index_version=1,
        title="Product Manual",
        source_type="TEXT",
        filename="manual.txt",
        raw_content="Warranty lasts one year.\nReturns follow the after-sales policy.",
        embedding_model_code="model-431c4581ab84",
        provider_configs=[{"provider_code": "model-431c4581ab84-provider"}],
        model_records=[{"model_code": "model-431c4581ab84"}],
    )

    response = await ingest_knowledge_document(
        request,
        provider_configs={"model-431c4581ab84-provider": {"provider_code": "model-431c4581ab84-provider"}},
        model_records={"model-431c4581ab84": {"model_code": "model-431c4581ab84"}},
    )

    assert response.status == "SUCCEEDED"
    assert response.chunk_count == len(captured_rows)
    assert captured_rows[0]["chunk_id"] == "doc_001_1_0"
    assert captured_rows[0]["kb_code"] == "kb_product"
    assert captured_rows[0]["metadata"]["task_id"] == "task_001"
