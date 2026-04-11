from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List


@dataclass
class ThresholdDecision:
    workflow_code: str
    intent_code: str
    threshold: float
    threshold_source: str
    accepted: bool


class DynamicThresholdManager:
    BASE_THRESHOLDS = {
        "flight_booking": 0.72,
        "hotel_booking": 0.68,
        "general_query": 0.52,
    }

    def resolve(self, workflow_code: str, intent_code: str, confidence: float, message: str = "") -> ThresholdDecision:
        base = self.BASE_THRESHOLDS.get(workflow_code, 0.60)
        normalized_message = (message or "").lower()
        source = "dynamic:default"

        if len(normalized_message) <= 8:
            base += 0.05
            source = "dynamic:short_query"
        elif any(keyword in normalized_message for keyword in ("政策", "规则", "refund", "policy")):
            base -= 0.04
            source = "dynamic:knowledge_query"
        elif any(keyword in normalized_message for keyword in ("航班", "机票", "flight", "ticket")):
            base -= 0.02
            source = "dynamic:travel_query"

        threshold = max(0.45, min(0.85, round(base, 2)))
        return ThresholdDecision(
            workflow_code=workflow_code,
            intent_code=intent_code,
            threshold=threshold,
            threshold_source=source,
            accepted=confidence >= threshold,
        )


class SubflowRecommendationService:
    SUBFLOW_CATALOG = {
        "flight_booking": [
            {"subflow_code": "seat_check", "subflow_version": "1.0.0", "keywords": ["座位", "seat", "库存"]},
            {"subflow_code": "general_query", "subflow_version": "1.0.0", "keywords": ["政策", "规则", "退票", "改签"]},
        ],
        "hotel_booking": [
            {"subflow_code": "general_query", "subflow_version": "1.0.0", "keywords": ["政策", "发票", "取消"]},
        ],
    }

    def recommend(self, workflow_code: str, message: str) -> List[Dict[str, object]]:
        normalized_message = (message or "").lower()
        candidates = self.SUBFLOW_CATALOG.get(workflow_code, [])
        recommendations: List[Dict[str, object]] = []
        for candidate in candidates:
            matches = sum(1 for keyword in candidate["keywords"] if keyword.lower() in normalized_message)
            if matches == 0:
                continue
            score = min(0.99, 0.55 + matches * 0.15)
            recommendations.append({
                "subflow_code": candidate["subflow_code"],
                "subflow_version": candidate["subflow_version"],
                "score": round(score, 2),
                "reason": f"matched_{matches}_keywords",
            })
        return sorted(recommendations, key=lambda item: float(item["score"]), reverse=True)


dynamic_threshold_manager = DynamicThresholdManager()
subflow_recommendation_service = SubflowRecommendationService()
