from __future__ import annotations

import re
from hashlib import sha256
from typing import Any, Dict, List

_PUNCTUATION_RE = re.compile(r"""[\s,，。！？；：、()\[\]{}<>《》“”"'`~@#$%^&*_+=|\\/.-]+""")
_CJK_RE = re.compile(r"[\u4e00-\u9fff]")


def build_chunks(text: str, title: str = "", max_chars: int = 800, overlap_chars: int = 120) -> List[Dict[str, Any]]:
    normalized = normalize_text(text)
    if not normalized:
        return []

    chunks: List[Dict[str, Any]] = []
    start = 0
    chunk_index = 0
    step = max(1, max_chars - max(0, overlap_chars))
    while start < len(normalized):
        end = min(len(normalized), start + max_chars)
        content = normalized[start:end].strip()
        if content:
            search_text_source = f"{title} {content}"
            chunks.append(
                {
                    "chunk_id": "",
                    "chunk_index": chunk_index,
                    "title": title,
                    "content": content,
                    "search_text": normalize_for_search(search_text_source),
                    "keywords": extract_keywords(search_text_source),
                    "search_terms": normalize_search_terms(search_text_source, 2, 4),
                    "content_hash": sha256(content.encode("utf-8")).hexdigest(),
                }
            )
            chunk_index += 1
        start += step
    return chunks


def normalize_text(text: str) -> str:
    lines = [line.strip() for line in (text or "").replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    return "\n".join(line for line in lines if line)


def normalize_for_search(text: str) -> str:
    return _PUNCTUATION_RE.sub(" ", (text or "").lower()).strip()


def normalize_search_terms(text: str, ngram_min: int = 2, ngram_max: int = 4) -> List[str]:
    normalized = normalize_for_search(text)
    terms: set[str] = {token for token in normalized.split(" ") if token}
    cjk_text = "".join(char for char in normalized if _CJK_RE.match(char))
    max_size = max(ngram_min, ngram_max)
    for size in range(max(1, ngram_min), max_size + 1):
        for index in range(0, max(0, len(cjk_text) - size + 1)):
            terms.add(cjk_text[index:index + size])
    return sorted(terms)


def extract_keywords(text: str, limit: int = 20) -> List[str]:
    terms = normalize_search_terms(text, 2, 4)
    scored = sorted(terms, key=lambda value: (-len(value), value))
    return scored[:limit]
