from typing import Any, Dict

from src.core.knowledge_store import get_knowledge_store
from src.core.model_runtime import execute_profile_completion
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
        self.query_rewrite = config.get("query_rewrite", {})
        self.answer_generation = config.get("answer_generation", {})
        self.output_mapping = data.get("output_mapping", config.get("output_mapping", {}))

    async def execute(self, context) -> Dict[str, Any]:
        query = context.get_variable("user_message", "")
        retrieval_query = query
        if self.query_rewrite.get("enabled"):
            profile_code = self.query_rewrite.get("model_profile_ref")
            if not profile_code:
                raise ValueError(f"Knowledge query rewrite missing model_profile_ref for node {self.node_id}")
            retrieval_query = await execute_profile_completion(
                profile_code=str(profile_code),
                provider_configs=context.provider_configs,
                model_profiles=context.model_profiles,
                system_prompt="你是知识检索查询改写器，请输出更适合知识库检索的查询文本。",
                user_prompt=query,
            )

        plan = vector_access_optimizer.plan(self.knowledge_base_code, self.kb_version, retrieval_query)
        cached_documents = vector_access_optimizer.get_cached(plan.cache_key)
        cache_hit = cached_documents is not None
        if cache_hit:
            documents = cached_documents
        else:
            documents = get_knowledge_store().search(
                kb_code=self.knowledge_base_code,
                kb_version=self.kb_version,
                query=retrieval_query,
                retrieval_mode=self.retrieval_mode,
                top_k=self.top_k,
                score_threshold=self.score_threshold,
            )
            vector_access_optimizer.put_cached(plan.cache_key, documents)
        answer = None
        citations = [{"doc_id": item.get("doc_id")} for item in documents[:3]]
        if self.answer_generation.get("enabled"):
            profile_code = self.answer_generation.get("model_profile_ref")
            if not profile_code:
                raise ValueError(f"Knowledge answer generation missing model_profile_ref for node {self.node_id}")
            answer = await execute_profile_completion(
                profile_code=str(profile_code),
                provider_configs=context.provider_configs,
                model_profiles=context.model_profiles,
                system_prompt="你是知识库问答助手，请基于给定文档回答用户问题。",
                user_prompt=str({
                    "question": query,
                    "retrieval_query": retrieval_query,
                    "documents": documents,
                }),
            )
        output = {
            "retrieval_query": retrieval_query,
            "documents": documents,
            "document_count": len(documents),
            "answer": answer,
            "citations": citations,
        }
        context.add_execution_variable("retrieved_docs", documents)
        context.add_execution_variable("retrieval_query", retrieval_query)
        if documents:
            context.add_execution_variable("policy_content", documents[0]["content"])
        if answer:
            context.add_execution_variable("knowledge_answer", answer)
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
                "query_rewrite_enabled": bool(self.query_rewrite.get("enabled")),
                "answer_generation_enabled": bool(self.answer_generation.get("enabled")),
            },
            "protection_events": [{
                "event_type": "optimization.vector_access",
                "data": {
                    "kb_code": self.knowledge_base_code,
                    "kb_version": self.kb_version,
                    "retrieval_query": retrieval_query,
                    "vector_shard": plan.shard_id,
                    "vector_cache_hit": cache_hit,
                },
            }],
        })
