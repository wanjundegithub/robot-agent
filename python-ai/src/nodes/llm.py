import re
from datetime import date, timedelta
from typing import Any, Dict, List

from .base import BaseNode
from src.core.security import PromptSanitizer, StructuredOutputValidator


class LLMNode(BaseNode):
    """LLM-like node with prompt sanitization and structured validation."""

    def __init__(self, node_id: str, data: Dict[str, Any]):
        super().__init__(node_id, "llm")
        config = data.get("config", {})
        self.prompt = config.get("prompt", data.get("prompt", ""))
        self.structured_output = config.get("structured_output", {})

    async def execute(self, context) -> Dict[str, Any]:
        original_message = context.get_variable("user_message", "")
        message = PromptSanitizer.sanitize(original_message)
        security_events: List[Dict[str, Any]] = []
        if message != original_message:
            security_events.append({
                "event_type": "security.prompt_sanitized",
                "data": {
                    "sanitized": True,
                    "original_length": len(original_message),
                    "sanitized_length": len(message),
                },
            })

        if self.prompt == "knowledge_answer":
            answer = self._answer_with_knowledge(context)
            context.add_execution_variable("answer", answer)
            return self.prepare_output({
                "status": "completed",
                "output": {"answer": answer},
                "message_deltas": [answer],
                "security_events": security_events,
            })

        extracted = self._extract_slots(message, hotel_mode=self.prompt == "hotel_slot_extraction")
        self._validate_output(extracted)
        if extracted:
            context.add_execution_variables(extracted)

        deltas = ["Slots extracted." if extracted else "No slots extracted, need more info."]

        return self.prepare_output({
            "status": "completed",
            "output": extracted,
            "message_deltas": deltas,
            "security_events": security_events,
        })

    def _extract_slots(self, message: str, hotel_mode: bool = False) -> Dict[str, Any]:
        if not message:
            return {}

        slots: Dict[str, Any] = {}
        zh_match = re.search(r"从(?P<departure>[^到，,。\s]+)到(?P<arrival>[^，,。\s]+)", message)
        if zh_match:
            slots["departure_city"] = zh_match.group("departure")
            slots["arrival_city"] = zh_match.group("arrival")

        route_match = re.search(
            r"from\s+(?P<departure>[A-Za-z\u4e00-\u9fa5]+)\s+to\s+(?P<arrival>[A-Za-z\u4e00-\u9fa5]+)",
            message,
            re.IGNORECASE,
        )
        if route_match:
            slots["departure_city"] = route_match.group("departure")
            slots["arrival_city"] = route_match.group("arrival")

        if hotel_mode and "arrival_city" not in slots:
            city_match = re.search(r"(北京|上海|广州|深圳|杭州|成都)", message)
            if city_match:
                slots["arrival_city"] = city_match.group(1)

        date_match = re.search(r"(\d{4}-\d{2}-\d{2})", message)
        if date_match:
            slots["departure_date"] = date_match.group(1)
        elif "tomorrow" in message.lower() or "\u660e\u5929" in message:
            slots["departure_date"] = (date.today() + timedelta(days=1)).isoformat()
        elif "today" in message.lower() or "\u4eca\u5929" in message:
            slots["departure_date"] = date.today().isoformat()

        passengers_match = re.search(r"(\d+)\s*passengers?", message, re.IGNORECASE)
        if passengers_match:
            slots["passengers"] = int(passengers_match.group(1))
        else:
            passengers_match = re.search(r"(\d+)\s*(?:人|位)", message)
            if passengers_match:
                slots["passengers"] = int(passengers_match.group(1))

        nights_match = re.search(r"(\d+)\s*(?:晚|night)", message, re.IGNORECASE)
        if nights_match:
            slots["nights"] = int(nights_match.group(1))

        return slots

    def _answer_with_knowledge(self, context) -> str:
        documents: List[Dict[str, Any]] = context.get_variable("retrieved_docs", []) or []
        if not documents:
            return "暂时没有检索到相关知识，请稍后再试。"
        summary = "；".join(document["content"] for document in documents[:2])
        return f"根据知识库检索结果：{summary}"

    def _validate_output(self, extracted: Dict[str, Any]) -> None:
        if not self.structured_output.get("enabled"):
            return
        schema = self.structured_output.get("schema") or {}
        StructuredOutputValidator.validate(extracted, schema)
