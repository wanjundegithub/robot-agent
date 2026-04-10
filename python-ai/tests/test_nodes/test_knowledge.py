import pytest

from src.core.context import ExecutionContext
from src.nodes.knowledge import KnowledgeNode


@pytest.mark.asyncio
async def test_knowledge_node_retrieves_documents_and_updates_context():
    context = ExecutionContext(
        execution_id="exec_knowledge",
        session_id="sess_knowledge",
        workflow_code="general_query",
        workflow_version="1.0.0"
    )
    context.add_execution_variable("user_message", "我想了解航班退票和改签政策")

    node = KnowledgeNode("retrieve_policy", {
        "config": {
            "knowledge_base_code": "flight_policy_kb",
            "kb_version": "1.0.0",
            "retrieval_mode": "hybrid",
            "top_k": 2
        }
    })

    result = await node.execute(context)

    assert result["output"]["document_count"] >= 1
    assert context.get_variable("retrieved_docs")
    assert "改签" in context.get_variable("policy_content")
