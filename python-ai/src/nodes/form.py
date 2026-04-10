from typing import Dict, Any
from .base import BaseNode


class FormNode(BaseNode):
    """表单节点"""

    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "form")
        config = data.get("config", {})
        self.fields = config.get("fields", data.get("fields", []))
        self.title = config.get("title", data.get("title", "Form"))
        self.description = config.get("description", data.get("description", ""))

    async def execute(self, context) -> Dict[str, Any]:
        return self.prepare_output({
            "status": "suspended",
            "form_definition": {
                "title": self.title,
                "description": self.description,
                "fields": self.fields
            }
        })
