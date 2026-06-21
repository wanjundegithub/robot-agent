from __future__ import annotations

import hashlib
from typing import Any, Dict, List, Protocol

import psycopg
from pgvector.psycopg import register_vector
from psycopg.types.json import Jsonb

from .chunking import normalize_search_terms
from .settings import settings


class KnowledgeStore(Protocol):
    def upsert_chunks(self, chunks: List[Dict[str, Any]]) -> int:
        ...

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
        **_kwargs: Any,
    ) -> List[Dict[str, Any]]:
        ...


class PgVectorKnowledgeStore:
    REQUIRED_COLUMNS = {
        "chunk_id",
        "kb_code",
        "doc_id",
        "index_version",
        "chunk_index",
        "title",
        "content",
        "search_text",
        "keywords",
        "search_terms",
        "content_hash",
        "embedding",
        "metadata",
        "status",
        "created_at",
        "updated_at",
    }

    def __init__(self, dsn: str, table_name: str) -> None:
        self._dsn = dsn
        self._table_name = table_name

    def initialize(self) -> bool:
        with psycopg.connect(self._dsn, connect_timeout=1) as connection:
            register_vector(connection)
            with connection.cursor() as cursor:
                cursor.execute("CREATE EXTENSION IF NOT EXISTS vector")
                self._create_schema(cursor)
                if not self._schema_matches(cursor):
                    cursor.execute(f"DROP TABLE IF EXISTS {self._table_name}")
                    self._create_schema(cursor)
                    self._validate_schema(cursor)
            connection.commit()
        return True

    def _create_schema(self, cursor: Any) -> None:
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

    def _schema_matches(self, cursor: Any) -> bool:
        try:
            self._validate_schema(cursor)
            return True
        except RuntimeError as exc:
            if "embedding dimension" not in str(exc):
                raise
            return False

    def _validate_schema(self, cursor: Any) -> None:
        cursor.execute(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = %s
            """,
            (self._table_name,),
        )
        columns = {str(row[0]) for row in cursor.fetchall()}
        missing_columns = sorted(self.REQUIRED_COLUMNS - columns)
        if missing_columns:
            raise RuntimeError(
                f"Knowledge vector table {self._table_name} is incompatible; missing columns: "
                + ", ".join(missing_columns)
            )

        cursor.execute(
            "SELECT atttypmod FROM pg_attribute WHERE attrelid = %s::regclass AND attname = 'embedding'",
            (self._table_name,),
        )
        row = cursor.fetchone()
        dimension = int(row[0]) if row else 0
        if dimension != settings.vector_dimension:
            raise RuntimeError(
                f"Knowledge vector table {self._table_name} has embedding dimension {dimension}; "
                f"expected {settings.vector_dimension}"
            )

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
        **_kwargs: Any,
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
                               CAST(1 - (embedding <=> %s::vector) AS DOUBLE PRECISION) AS vector_score
                        FROM {self._table_name}
                        WHERE kb_code = ANY(%s) AND status = 'ACTIVE'
                        ORDER BY embedding <=> %s::vector
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
            vector_score = float(item.get("vector_score", 0.0))
            keyword_score = float(item.get("keyword_score", 0.0))
            weighted_score = (
                vector_score * settings.knowledge_retrieval_vector_weight
                + keyword_score * settings.knowledge_retrieval_keyword_weight
                + settings.knowledge_retrieval_metadata_boost
            )
            if retrieval_mode == "vector":
                score = vector_score
            elif retrieval_mode == "keyword":
                score = keyword_score
            else:
                score = max(weighted_score, vector_score, keyword_score)
            item["score"] = round(score, 6)
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


_knowledge_store: KnowledgeStore | None = None
_knowledge_backend = "uninitialized"


def initialize_knowledge_store() -> KnowledgeStore:
    global _knowledge_store, _knowledge_backend
    if not settings.vector_enabled:
        raise RuntimeError("ROBOT_VECTOR_ENABLED must be true; in-memory knowledge store is not supported")
    pgvector_store = PgVectorKnowledgeStore(settings.vector_dsn, settings.vector_table)
    pgvector_store.initialize()
    _knowledge_store = pgvector_store
    _knowledge_backend = "pgvector"
    return _knowledge_store


def get_knowledge_store() -> KnowledgeStore:
    if _knowledge_store is None:
        raise RuntimeError("Knowledge store is not initialized")
    return _knowledge_store


def get_knowledge_backend() -> str:
    return _knowledge_backend
