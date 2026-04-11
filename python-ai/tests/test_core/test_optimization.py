from src.core.optimization import dynamic_threshold_manager, subflow_recommendation_service


def test_dynamic_threshold_manager_adjusts_for_short_queries():
    decision = dynamic_threshold_manager.resolve(
        workflow_code="flight_booking",
        intent_code="book_flight",
        confidence=0.7,
        message="机票",
    )

    assert decision.threshold_source == "dynamic:short_query"
    assert decision.threshold >= 0.7


def test_subflow_recommendation_service_returns_ranked_candidates():
    recommendations = subflow_recommendation_service.recommend(
        workflow_code="flight_booking",
        message="我想确认座位库存和退票政策",
    )

    assert recommendations
    assert recommendations[0]["score"] >= recommendations[-1]["score"]
