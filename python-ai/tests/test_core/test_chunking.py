from src.core.chunking import build_chunks, normalize_search_terms


def test_build_chunks_splits_text_and_keeps_metadata():
    chunks = build_chunks("第一段介绍保修政策。\n\n第二段介绍退换货流程。", title="产品手册", max_chars=12)

    assert chunks[0]["chunk_index"] == 0
    assert chunks[0]["title"] == "产品手册"
    assert "保修" in chunks[0]["content"]
    assert chunks[0]["search_terms"]


def test_normalize_search_terms_uses_ngram_for_chinese():
    terms = normalize_search_terms("保修政策", ngram_min=2, ngram_max=3)

    assert "保修" in terms
    assert "政策" in terms
    assert "保修政" in terms
