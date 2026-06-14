from __future__ import annotations

from typing import Any, Dict, List

import httpx

from src.api.models import KnowledgeIngestRequest, KnowledgeIngestResponse
from src.core.chunking import build_chunks
from src.core.document_processing import extract_text
from src.core.embedding_runtime import embed_texts_with_model
from src.core.knowledge_store import get_knowledge_store
from src.core.settings import settings


async def ingest_knowledge_document(
    request: KnowledgeIngestRequest,
    provider_configs: Dict[str, Dict[str, Any]],
    model_records: Dict[str, Dict[str, Any]],
) -> KnowledgeIngestResponse:
    try:
        content_bytes = await _download_bytes(request.raw_object_url) if request.raw_object_url else None
        text = extract_text(
            filename=request.filename,
            content=content_bytes,
            raw_text=request.raw_content,
            legacy_doc_text=request.legacy_doc_text,
        )
        chunks = build_chunks(text, title=request.title or request.filename)
        if not chunks:
            return KnowledgeIngestResponse(
                task_id=request.task_id,
                doc_id=request.doc_id,
                kb_code=request.kb_code,
                status="FAILED",
                error_message="No extractable text found",
            )

        embeddings: List[List[float]] = []
        for start in range(0, len(chunks), settings.knowledge_embedding_batch_size):
            batch = chunks[start:start + settings.knowledge_embedding_batch_size]
            embeddings.extend(
                await embed_texts_with_model(
                    texts=[chunk["content"] for chunk in batch],
                    model_code=request.embedding_model_code,
                    provider_configs=provider_configs,
                    model_records=model_records,
                    expected_dimension=settings.vector_dimension,
                )
            )

        rows = []
        for chunk, embedding in zip(chunks, embeddings):
            chunk_id = f"{request.doc_id}_{request.index_version}_{chunk['chunk_index']}"
            rows.append(
                {
                    **chunk,
                    "chunk_id": chunk_id,
                    "kb_code": request.kb_code,
                    "doc_id": request.doc_id,
                    "index_version": request.index_version,
                    "embedding": embedding,
                    "metadata": {
                        "filename": request.filename,
                        "task_id": request.task_id,
                        "source_type": request.source_type,
                    },
                }
            )

        get_knowledge_store().upsert_chunks(rows)
        keywords = sorted({keyword for row in rows for keyword in row.get("keywords", [])})[:20]
        return KnowledgeIngestResponse(
            task_id=request.task_id,
            doc_id=request.doc_id,
            kb_code=request.kb_code,
            status="SUCCEEDED",
            chunk_count=len(rows),
            generated_title=request.title or request.filename,
            generated_summary=rows[0]["content"][:200],
            generated_keywords=keywords,
        )
    except Exception as exc:
        return KnowledgeIngestResponse(
            task_id=request.task_id,
            doc_id=request.doc_id,
            kb_code=request.kb_code,
            status="FAILED",
            error_message=str(exc),
        )


async def _download_bytes(url: str) -> bytes:
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.get(url)
        response.raise_for_status()
        return response.content
