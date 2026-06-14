from src.core.knowledge_store import PgVectorKnowledgeStore


class FakeCursor:
    def __init__(self, row_sets=None):
        self.executed = []
        self.row_sets = list(row_sets or [])

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def execute(self, sql, params=None):
        self.executed.append((sql, params))

    def fetchall(self):
        return self.row_sets.pop(0) if self.row_sets else []


class FakeConnection:
    def __init__(self, cursor):
        self.cursor_instance = cursor
        self.committed = False

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def cursor(self):
        return self.cursor_instance

    def commit(self):
        self.committed = True


def test_initialize_creates_pgvector_hybrid_schema(monkeypatch):
    cursor = FakeCursor()
    connection = FakeConnection(cursor)
    monkeypatch.setattr("src.core.knowledge_store.psycopg.connect", lambda *_args, **_kwargs: connection)
    monkeypatch.setattr("src.core.knowledge_store.register_vector", lambda _connection: None)

    store = PgVectorKnowledgeStore("postgresql://test", "knowledge_chunks")

    assert store.initialize() is True
    sql = "\n".join(statement for statement, _params in cursor.executed)
    assert "CREATE EXTENSION IF NOT EXISTS vector" in sql
    assert "chunk_id TEXT PRIMARY KEY" in sql
    assert "embedding VECTOR(4096) NOT NULL" in sql
    assert "CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_search_terms" in sql


def test_search_many_merges_vector_and_keyword_scores(monkeypatch):
    cursor = FakeCursor(row_sets=[
        [("chunk_1", "doc_1", "kb_product", "产品手册", "保修期为一年", {"source": "manual"}, 0.8)],
        [("chunk_1", "doc_1", "kb_product", "产品手册", "保修期为一年", {"source": "manual"}, 0.5)],
    ])
    store = PgVectorKnowledgeStore("postgresql://test", "knowledge_chunks")
    monkeypatch.setattr(store, "_connect", lambda: FakeConnection(cursor))
    monkeypatch.setattr(store, "_embed_text", lambda _text: [0.1] * 4096)

    results = store.search_many(["kb_product"], "保修政策", top_k=3, score_threshold=0.0)

    assert results[0]["chunk_id"] == "chunk_1"
    assert results[0]["vector_score"] == 0.8
    assert results[0]["keyword_score"] == 0.5
    assert results[0]["score"] == 0.76
