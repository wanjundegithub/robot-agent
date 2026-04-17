from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any, Dict, List, Protocol

import psycopg
from pgvector.psycopg import register_vector

from .settings import settings


@dataclass
class KnowledgeDocument:
    doc_id: str
    kb_code: str
    kb_version: str
    title: str
    content: str
    keywords: List[str]


DEFAULT_KNOWLEDGE_BASES: Dict[str, Dict[str, List[KnowledgeDocument]]] = {}


class KnowledgeStore(Protocol):
    def search(
        self,
        kb_code: str,
        kb_version: str | None,
        query: str,
        retrieval_mode: str = "hybrid",
        top_k: int = 5,
        score_threshold: float = 0.0,
    ) -> List[Dict[str, Any]]:
        ...


class InMemoryKnowledgeStore:
    def __init__(self, documents: Dict[str, Dict[str, List[KnowledgeDocument]]] | None = None) -> None:
        self._documents = documents or DEFAULT_KNOWLEDGE_BASES

    def search(
        self,
        kb_code: str,
        kb_version: str | None,
        query: str,
        retrieval_mode: str = "hybrid",
        top_k: int = 5,
        score_threshold: float = 0.0,
    ) -> List[Dict[str, Any]]:
        versions = self._documents.get(kb_code, {})
        if not versions:
            return []

        effective_version = kb_version or sorted(versions.keys())[-1]
        documents = versions.get(effective_version, [])
        tokens = self._tokenize(query)
        scored_documents: List[tuple[float, KnowledgeDocument]] = []

        for document in documents:
            keyword_hits = sum(1 for token in tokens if token in self._tokenize(document.content) or token in document.keywords)
            semantic_hits = sum(1 for token in tokens if token in self._tokenize(document.title))
            if retrieval_mode == "keyword":
                score = float(keyword_hits)
            elif retrieval_mode == "vector":
                score = float(semantic_hits + keyword_hits * 0.5)
            else:
                score = float(keyword_hits + semantic_hits)
            if score >= score_threshold:
                scored_documents.append((score, document))

        scored_documents.sort(key=lambda item: item[0], reverse=True)
        return [
            {
                "doc_id": document.doc_id,
                "kb_code": document.kb_code,
                "kb_version": document.kb_version,
                "title": document.title,
                "content": document.content,
                "score": score,
            }
            for score, document in scored_documents[:top_k]
        ]

    def _tokenize(self, text: str) -> List[str]:
        normalized = (text or "").lower()
        return [token for token in normalized.replace("，", " ").replace(",", " ").split() if token]


class PgVectorKnowledgeStore:
    def __init__(self, dsn: str, table_name: str) -> None:
        self._dsn = dsn
        self._table_name = table_name

    def initialize(self) -> bool:
        try:
            with psycopg.connect(self._dsn, connect_timeout=1) as connection:
                with connection.cursor() as cursor:
                    cursor.execute("CREATE EXTENSION IF NOT EXISTS vector")
                    cursor.execute(
                        f"""
                        CREATE TABLE IF NOT EXISTS {self._table_name} (
                            doc_id TEXT NOT NULL,
                            chunk_id TEXT PRIMARY KEY,
                            kb_code TEXT NOT NULL,
                            kb_version TEXT NOT NULL,
                            title TEXT NOT NULL,
                            content TEXT NOT NULL,
                            embedding VECTOR(8) NOT NULL,
                            metadata JSONB NOT NULL DEFAULT '{{}}'::jsonb,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                        """
                    )
                connection.commit()
            return True
        except Exception:
            return False

    def search(
        self,
        kb_code: str,
        kb_version: str | None,
        query: str,
        retrieval_mode: str = "hybrid",
        top_k: int = 5,
        score_threshold: float = 0.0,
    ) -> List[Dict[str, Any]]:
        effective_version = kb_version or "1.0.0"
        query_embedding = self._embed_text(query)
        with self._connect() as connection:
            with connection.cursor() as cursor:
                if retrieval_mode == "keyword":
                    cursor.execute(
                        f"""
                        SELECT doc_id, kb_code, kb_version, title, content, 1.0 AS score
                        FROM {self._table_name}
                        WHERE kb_code = %s AND kb_version = %s
                        ORDER BY created_at DESC
                        LIMIT %s
                        """,
                        (kb_code, effective_version, top_k),
                    )
                else:
                    cursor.execute(
                        f"""
                        SELECT doc_id, kb_code, kb_version, title, content,
                               CAST(1 - (embedding <=> %s) AS DOUBLE PRECISION) AS score
                        FROM {self._table_name}
                        WHERE kb_code = %s AND kb_version = %s
                        ORDER BY embedding <=> %s
                        LIMIT %s
                        """,
                        (query_embedding, kb_code, effective_version, query_embedding, top_k),
                    )
                rows = cursor.fetchall()

        return [
            {
                "doc_id": row[0],
                "kb_code": row[1],
                "kb_version": row[2],
                "title": row[3],
                "content": row[4],
                "score": row[5],
            }
            for row in rows
            if row[5] is None or float(row[5]) >= score_threshold
        ]

    def _connect(self) -> psycopg.Connection:
        connection = psycopg.connect(self._dsn, connect_timeout=1)
        register_vector(connection)
        return connection

    def _embed_text(self, text: str) -> List[float]:
        digest = hashlib.sha256((text or "").encode("utf-8")).digest()
        return [round(int.from_bytes(digest[index:index + 4], "big") / 4294967295, 6) for index in range(0, 32, 4)]


_knowledge_store: KnowledgeStore = InMemoryKnowledgeStore()
_knowledge_backend = "memory"


def initialize_knowledge_store() -> KnowledgeStore:
    global _knowledge_store, _knowledge_backend
    if settings.vector_enabled:
        pgvector_store = PgVectorKnowledgeStore(settings.vector_dsn, settings.vector_table)
        if pgvector_store.initialize():
            _knowledge_store = pgvector_store
            _knowledge_backend = "pgvector"
            return _knowledge_store
    _knowledge_store = InMemoryKnowledgeStore()
    _knowledge_backend = "memory"
    return _knowledge_store


def get_knowledge_store() -> KnowledgeStore:
    return _knowledge_store


def get_knowledge_backend() -> str:
    return _knowledge_backend
