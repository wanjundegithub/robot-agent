from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Iterable, List

from .knowledge_store import get_knowledge_store


DEFAULT_RAG_DATASET = [
    {
        "query": "航班退票政策是什么",
        "expected_terms": ["退票", "手续费"],
    },
    {
        "query": "航班改签规则是什么",
        "expected_terms": ["改签", "航班"],
    },
]


@dataclass
class RagEvaluationItemResult:
    query: str
    hit: bool
    relevance: float
    matched_terms: List[str]
    document_count: int


class RagEvaluator:
    def evaluate_dataset(self, dataset: Iterable[Dict[str, object]] | None = None) -> Dict[str, object]:
        items = list(dataset or DEFAULT_RAG_DATASET)
        results = [self.evaluate_query(item["query"], item.get("expected_terms", [])) for item in items]
        total = len(results)
        hit_rate = sum(1 for result in results if result.hit) / total if total else 0.0
        avg_relevance = sum(result.relevance for result in results) / total if total else 0.0
        return {
            "dataset_size": total,
            "hit_rate": round(hit_rate, 4),
            "avg_relevance": round(avg_relevance, 4),
            "results": [
                {
                    "query": result.query,
                    "hit": result.hit,
                    "relevance": result.relevance,
                    "matched_terms": result.matched_terms,
                    "document_count": result.document_count,
                }
                for result in results
            ],
        }

    def evaluate_query(self, query: str, expected_terms: Iterable[str]) -> RagEvaluationItemResult:
        expected_terms_list = list(expected_terms)
        documents = get_knowledge_store().search(
            kb_code="flight_policy_kb",
            kb_version="1.0.0",
            query=query,
            retrieval_mode="hybrid",
            top_k=3,
            score_threshold=0.0,
        )
        joined = " ".join(document["content"] for document in documents)
        matched_terms = [term for term in expected_terms_list if term in joined]
        relevance = len(matched_terms) / len(expected_terms_list) if expected_terms_list else 0.0
        return RagEvaluationItemResult(
            query=query,
            hit=bool(matched_terms),
            relevance=round(relevance, 4),
            matched_terms=matched_terms,
            document_count=len(documents),
        )


rag_evaluator = RagEvaluator()
