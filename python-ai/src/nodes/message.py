from typing import Any, Dict

from .base import BaseNode


class MessageNode(BaseNode):
    """固定话术消息节点"""

    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "message")
        config = data.get("config", {})
        self.message_text = str(config.get("message_text", data.get("message_text", "")))

    async def execute(self, context) -> Dict[str, Any]:
        text = self.message_text
        context.add_execution_variable("answer", text)
        return self.prepare_output({
            "status": "completed",
            "output": {
                "answer": text,
            },
            "message_deltas": [text] if text else [],
        })
