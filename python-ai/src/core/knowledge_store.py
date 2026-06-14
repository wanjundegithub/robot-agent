from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any, Dict, List, Protocol

import psycopg
from pgvector.psycopg import register_vector
from psycopg.types.json import Jsonb

from .chunking import normalize_search_terms
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

    def search_many(
        self,
        kb_codes: List[str],
        query: str,
        retrieval_mode: str = "hybrid",
        top_k: int = 5,
        score_threshold: float = 0.0,
        embedding: List[float] | None = None,
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

    def search_many(
        self,
        kb_codes: List[str],
        query: str,
        retrieval_mode: str = "hybrid",
        top_k: int = 5,
        score_threshold: float = 0.0,
        embedding: List[float] | None = None,
    ) -> List[Dict[str, Any]]:
        results: List[Dict[str, Any]] = []
        for kb_code in kb_codes:
            results.extend(self.search(kb_code, None, query, retrieval_mode, top_k, score_threshold))
        return sorted(results, key=lambda item: float(item.get("score", 0.0)), reverse=True)[:top_k]

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
                register_vector(connection)
                with connection.cursor() as cursor:
                    cursor.execute("CREATE EXTENSION IF NOT EXISTS vector")
                    cursor.execute(
                        f"""
                        CREATE TABLE IF NOT EXISTS {self._table_name} (
                            chunk_id TEXT PRIMARY KEY,
                            kb_code TEXT NOT NULL,
                            doc_id TEXT NOT NULL,
                            index_version INT NOT NULL,
                            chunk_index INT NOT NULL,
                            title TEXT,
                            content TEXT NOT NULL,
                            search_text TEXT,
                            keywords TEXT[],
                            search_terms TEXT[],
                            content_hash TEXT,
                            embedding VECTOR({settings.vector_dimension}) NOT NULL,
                            metadata JSONB NOT NULL DEFAULT '{{}}'::jsonb,
                            status TEXT NOT NULL DEFAULT 'ACTIVE',
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                        """
                    )
                    cursor.execute(
                        f"CREATE INDEX IF NOT EXISTS idx_{self._table_name}_kb_status_version "
                        f"ON {self._table_name} (kb_code, status, index_version)"
                    )
                    cursor.execute(
                        f"CREATE INDEX IF NOT EXISTS idx_{self._table_name}_doc_version "
                        f"ON {self._table_name} (doc_id, index_version)"
                    )
                    cursor.execute(
                        f"CREATE INDEX IF NOT EXISTS idx_{self._table_name}_metadata "
                        f"ON {self._table_name} USING GIN (metadata)"
                    )
                    cursor.execute(
                        f"CREATE INDEX IF NOT EXISTS idx_{self._table_name}_keywords "
                        f"ON {self._table_name} USING GIN (keywords)"
                    )
                    cursor.execute(
                        f"CREATE INDEX IF NOT EXISTS idx_{self._table_name}_search_terms "
                        f"ON {self._table_name} USING GIN (search_terms)"
                    )
                    cursor.execute(
                        f"CREATE INDEX IF NOT EXISTS idx_{self._table_name}_embedding "
                        f"ON {self._table_name} USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100)"
                    )
                connection.commit()
            return True
        except Exception:
            return False

    def upsert_chunks(self, chunks: List[Dict[str, Any]]) -> int:
        if not chunks:
            return 0
        with self._connect() as connection:
            with connection.cursor() as cursor:
                for chunk in chunks:
                    cursor.execute(
                        f"""
                        INSERT INTO {self._table_name} (
                            chunk_id, kb_code, doc_id, index_version, chunk_index, title,
                            content, search_text, keywords, search_terms, content_hash,
                            embedding, metadata, status, updated_at
                        )
                        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'ACTIVE', CURRENT_TIMESTAMP)
                        ON CONFLICT (chunk_id) DO UPDATE SET
                            title = EXCLUDED.title,
                            content = EXCLUDED.content,
                            search_text = EXCLUDED.search_text,
                            keywords = EXCLUDED.keywords,
                            search_terms = EXCLUDED.search_terms,
                            content_hash = EXCLUDED.content_hash,
                            embedding = EXCLUDED.embedding,
                            metadata = EXCLUDED.metadata,
                            status = 'ACTIVE',
                            updated_at = CURRENT_TIMESTAMP
                        """,
                        (
                            chunk["chunk_id"],
                            chunk["kb_code"],
                            chunk["doc_id"],
                            int(chunk["index_version"]),
                            int(chunk["chunk_index"]),
                            chunk.get("title"),
                            chunk["content"],
                            chunk.get("search_text"),
                            chunk.get("keywords", []),
                            chunk.get("search_terms", []),
                            chunk.get("content_hash"),
                            chunk["embedding"],
                            Jsonb(chunk.get("metadata", {})),
                        ),
                    )
            connection.commit()
        return len(chunks)

    def search(
        self,
        kb_code: str,
        kb_version: str | None,
        query: str,
        retrieval_mode: str = "hybrid",
        top_k: int = 5,
        score_threshold: float = 0.0,
    ) -> List[Dict[str, Any]]:
        return self.search_many(
            kb_codes=[kb_code],
            query=query,
            retrieval_mode=retrieval_mode,
            top_k=top_k,
            score_threshold=score_threshold,
        )

    def search_many(
        self,
        kb_codes: List[str],
        query: str,
        retrieval_mode: str = "hybrid",
        top_k: int = 5,
        score_threshold: float = 0.0,
        embedding: List[float] | None = None,
    ) -> List[Dict[str, Any]]:
        if not kb_codes:
            return []
        vector_rows: List[Dict[str, Any]] = []
        keyword_rows: List[Dict[str, Any]] = []
        query_terms = normalize_search_terms(
            query,
            settings.knowledge_retrieval_query_ngram_min,
            settings.knowledge_retrieval_query_ngram_max,
        )

        with self._connect() as connection:
            with connection.cursor() as cursor:
                if retrieval_mode in {"vector", "hybrid"}:
                    query_embedding = embedding or self._embed_text(query)
                    cursor.execute(
                        f"""
                        SELECT chunk_id, doc_id, kb_code, title, content, metadata,
                               CAST(1 - (embedding <=> %s) AS DOUBLE PRECISION) AS vector_score
                        FROM {self._table_name}
                        WHERE kb_code = ANY(%s) AND status = 'ACTIVE'
                        ORDER BY embedding <=> %s
                        LIMIT %s
                        """,
                        (query_embedding, kb_codes, query_embedding, settings.knowledge_retrieval_vector_top_k),
                    )
                    vector_rows = [_row_to_hit(row, "vector_score") for row in cursor.fetchall()]

                if retrieval_mode in {"keyword", "hybrid"}:
                    keyword_like = f"%{query}%"
                    cursor.execute(
                        f"""
                        SELECT chunk_id, doc_id, kb_code, title, content, metadata,
                               (
                                 CASE WHEN search_terms && %s THEN 0.5 ELSE 0 END +
                                 CASE WHEN keywords && %s THEN 0.3 ELSE 0 END +
                                 CASE WHEN search_text ILIKE %s THEN 0.2 ELSE 0 END
                               ) AS keyword_score
                        FROM {self._table_name}
                        WHERE kb_code = ANY(%s)
                          AND status = 'ACTIVE'
                          AND (search_terms && %s OR keywords && %s OR search_text ILIKE %s)
                        ORDER BY keyword_score DESC, updated_at DESC
                        LIMIT %s
                        """,
                        (
                            query_terms,
                            query_terms,
                            keyword_like,
                            kb_codes,
                            query_terms,
                            query_terms,
                            keyword_like,
                            settings.knowledge_retrieval_keyword_top_k,
                        ),
                    )
                    keyword_rows = [_row_to_hit(row, "keyword_score") for row in cursor.fetchall()]

        merged: Dict[str, Dict[str, Any]] = {}
        for item in vector_rows:
            merged[item["chunk_id"]] = {**item, "vector_score": item.get("vector_score", 0.0), "keyword_score": 0.0}
        for item in keyword_rows:
            current = merged.setdefault(item["chunk_id"], {**item, "vector_score": 0.0, "keyword_score": 0.0})
            current["keyword_score"] = item.get("keyword_score", 0.0)

        for item in merged.values():
            item["score"] = round(
                float(item.get("vector_score", 0.0)) * settings.knowledge_retrieval_vector_weight
                + float(item.get("keyword_score", 0.0)) * settings.knowledge_retrieval_keyword_weight
                + settings.knowledge_retrieval_metadata_boost,
                6,
            )
        return sorted(
            [item for item in merged.values() if float(item["score"]) >= score_threshold],
            key=lambda value: float(value["score"]),
            reverse=True,
        )[:top_k]

    def _connect(self) -> psycopg.Connection:
        connection = psycopg.connect(self._dsn, connect_timeout=1)
        register_vector(connection)
        return connection

    def _embed_text(self, text: str) -> List[float]:
        digest = hashlib.sha256((text or "").encode("utf-8")).digest()
        base = [round(int.from_bytes(digest[index:index + 4], "big") / 4294967295, 6) for index in range(0, 32, 4)]
        repeats = (settings.vector_dimension + len(base) - 1) // len(base)
        return (base * repeats)[:settings.vector_dimension]


def _row_to_hit(row: Any, score_key: str) -> Dict[str, Any]:
    return {
        "chunk_id": row[0],
        "doc_id": row[1],
        "kb_code": row[2],
        "title": row[3],
        "content": row[4],
        "metadata": row[5],
        score_key: float(row[6] or 0.0),
    }


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
