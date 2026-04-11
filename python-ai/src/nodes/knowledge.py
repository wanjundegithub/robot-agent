from typing import Any, Dict

from src.core.knowledge_store import get_knowledge_store
from src.core.protection import vector_access_optimizer
from .base import BaseNode


class KnowledgeNode(BaseNode):
    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "knowledge")
        config = data.get("config", {})
        self.knowledge_base_code = config.get("knowledge_base_code")
        self.kb_version = config.get("kb_version")
        self.retrieval_mode = config.get("retrieval_mode", "hybrid")
        self.top_k = int(config.get("top_k", 5))
        self.score_threshold = float(config.get("score_threshold", 0.0))
        self.output_mapping = data.get("output_mapping", config.get("output_mapping", {}))

    async def execute(self, context) -> Dict[str, Any]:
        query = context.get_variable("user_message", "")
        plan = vector_access_optimizer.plan(self.knowledge_base_code, self.kb_version, query)
        cached_documents = vector_access_optimizer.get_cached(plan.cache_key)
        cache_hit = cached_documents is not None
        if cache_hit:
            documents = cached_documents
        else:
            documents = get_knowledge_store().search(
                kb_code=self.knowledge_base_code,
                kb_version=self.kb_version,
                query=query,
                retrieval_mode=self.retrieval_mode,
                top_k=self.top_k,
                score_threshold=self.score_threshold,
            )
            vector_access_optimizer.put_cached(plan.cache_key, documents)
        output = {
            "documents": documents,
            "document_count": len(documents),
        }
        context.add_execution_variable("retrieved_docs", documents)
        if documents:
            context.add_execution_variable("policy_content", documents[0]["content"])
        if self.output_mapping:
            self.apply_output_mapping(self.output_mapping, output, context)

        return self.prepare_output({
            "status": "completed",
            "output": output,
            "metrics": {
                "retrieval_mode": self.retrieval_mode,
                "document_count": len(documents),
                "vector_shard": plan.shard_id,
                "vector_cache_hit": cache_hit,
            },
            "protection_events": [{
                "event_type": "optimization.vector_access",
                "data": {
                    "kb_code": self.knowledge_base_code,
                    "kb_version": self.kb_version,
                    "vector_shard": plan.shard_id,
                    "vector_cache_hit": cache_hit,
                },
            }],
        })
