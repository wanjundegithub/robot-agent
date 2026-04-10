from typing import Any, Dict, Optional


def get_workflow(workflow_code: str, workflow_version: str) -> Optional[Dict[str, Any]]:
    workflows = {
        ("flight_booking", "1.0.0"): flight_booking_v1(),
        ("flight_booking", "2.0.0"): flight_booking_v2(),
        ("hotel_booking", "1.0.0"): hotel_booking_v1(),
        ("general_query", "1.0.0"): general_query_v1(),
        ("seat_check", "1.0.0"): seat_check_v1(),
    }
    return workflows.get((workflow_code, workflow_version))


def flight_booking_v1() -> Dict[str, Any]:
    return {
        "workflow_code": "flight_booking",
        "workflow_version": "1.0.0",
        "entry": "start",
        "nodes": {
            "start": {"id": "start", "type": "start", "config": {"initial_variables": {}}},
            "extract_slots": {"id": "extract_slots", "type": "llm", "config": _slot_extraction_config()},
            "check_slots": {
                "id": "check_slots",
                "type": "condition",
                "config": {"required_fields": ["departure_city", "arrival_city", "departure_date"]},
            },
            "collect_info": {
                "id": "collect_info",
                "type": "form",
                "config": {
                    "title": "Complete trip info",
                    "description": "Please provide missing trip details.",
                    "fields": _flight_fields(),
                },
            },
            "end": {
                "id": "end",
                "type": "end",
                "config": {
                    "output_format": {
                        "departure_city": "execution.departure_city",
                        "arrival_city": "execution.arrival_city",
                        "departure_date": "execution.departure_date",
                        "passengers": "execution.passengers",
                    }
                },
            },
        },
        "transitions": {
            "start": "extract_slots",
            "extract_slots": "check_slots",
            "check_slots": {"complete": "end", "missing": "collect_info"},
            "collect_info": "end",
            "end": None,
        },
    }


def flight_booking_v2() -> Dict[str, Any]:
    return {
        "workflow_code": "flight_booking",
        "workflow_version": "2.0.0",
        "entry": "start",
        "nodes": {
            "start": {"id": "start", "type": "start", "config": {"initial_variables": {}}},
            "extract_slots": {"id": "extract_slots", "type": "llm", "config": _slot_extraction_config()},
            "check_slots": {
                "id": "check_slots",
                "type": "condition",
                "config": {"required_fields": ["departure_city", "arrival_city", "departure_date"]},
            },
            "collect_info": {
                "id": "collect_info",
                "type": "form",
                "config": {
                    "title": "Complete trip info",
                    "description": "Please provide missing trip details.",
                    "fields": _flight_fields(),
                },
            },
            "search_flights": {
                "id": "search_flights",
                "type": "tool",
                "config": {
                    "tool_code": "flight_search_api",
                    "retry_policy": "network_timeout",
                    "idempotent": True,
                    "simulate_failures": 1,
                },
            },
            "check_seat_availability": {
                "id": "check_seat_availability",
                "type": "subflow",
                "config": {
                    "subflow_code": "seat_check",
                    "subflow_version": "1.0.0",
                    "input_mapping": {
                        "flight_id": "$execution.flight_options.0.flight_id",
                        "departure_date": "$execution.departure_date",
                    },
                    "output_mapping": {
                        "$execution.seat_available": "$subflow.output.seat_available",
                        "$execution.seat_count": "$subflow.output.seat_count",
                    },
                },
            },
            "end": {
                "id": "end",
                "type": "end",
                "config": {
                    "output_format": {
                        "departure_city": "execution.departure_city",
                        "arrival_city": "execution.arrival_city",
                        "departure_date": "execution.departure_date",
                        "passengers": "execution.passengers",
                        "flight_options": "execution.flight_options",
                        "seat_available": "execution.seat_available",
                    }
                },
            },
        },
        "transitions": {
            "start": "extract_slots",
            "extract_slots": "check_slots",
            "check_slots": {"complete": "search_flights", "missing": "collect_info"},
            "collect_info": "search_flights",
            "search_flights": "check_seat_availability",
            "check_seat_availability": "end",
            "end": None,
        },
    }


def hotel_booking_v1() -> Dict[str, Any]:
    return {
        "workflow_code": "hotel_booking",
        "workflow_version": "1.0.0",
        "entry": "start",
        "nodes": {
            "start": {"id": "start", "type": "start", "config": {"initial_variables": {}}},
            "extract_slots": {"id": "extract_slots", "type": "llm", "config": _hotel_slot_extraction_config()},
            "collect_info": {
                "id": "collect_info",
                "type": "form",
                "config": {
                    "title": "Hotel request",
                    "description": "Please provide city and check-in date.",
                    "fields": [
                        {"name": "arrival_city", "type": "text", "required": True, "label": "Arrival city"},
                        {"name": "departure_date", "type": "date", "required": True, "label": "Check-in date"},
                        {"name": "nights", "type": "number", "required": False, "label": "Nights"},
                    ],
                },
            },
            "search_hotels": {
                "id": "search_hotels",
                "type": "tool",
                "config": {
                    "tool_code": "hotel_search_api",
                    "retry_policy": "network_timeout",
                    "idempotent": True,
                },
            },
            "end": {
                "id": "end",
                "type": "end",
                "config": {
                    "output_format": {
                        "arrival_city": "execution.arrival_city",
                        "departure_date": "execution.departure_date",
                        "hotel_options": "execution.hotel_options",
                    }
                },
            },
        },
        "transitions": {
            "start": "extract_slots",
            "extract_slots": "collect_info",
            "collect_info": "search_hotels",
            "search_hotels": "end",
            "end": None,
        },
    }


def general_query_v1() -> Dict[str, Any]:
    return {
        "workflow_code": "general_query",
        "workflow_version": "1.0.0",
        "entry": "start",
        "nodes": {
            "start": {"id": "start", "type": "start", "config": {"initial_variables": {}}},
            "retrieve_policy": {
                "id": "retrieve_policy",
                "type": "knowledge",
                "config": {
                    "knowledge_base_code": "flight_policy_kb",
                    "kb_version": "1.0.0",
                    "retrieval_mode": "hybrid",
                    "top_k": 3,
                },
            },
            "answer_query": {"id": "answer_query", "type": "llm", "config": {"prompt": "knowledge_answer"}},
            "end": {
                "id": "end",
                "type": "end",
                "config": {
                    "output_format": {
                        "answer": "execution.answer",
                        "retrieved_docs": "execution.retrieved_docs",
                    }
                },
            },
        },
        "transitions": {
            "start": "retrieve_policy",
            "retrieve_policy": "answer_query",
            "answer_query": "end",
            "end": None,
        },
    }


def seat_check_v1() -> Dict[str, Any]:
    return {
        "workflow_code": "seat_check",
        "workflow_version": "1.0.0",
        "entry": "start",
        "nodes": {
            "start": {"id": "start", "type": "start", "config": {"initial_variables": {}}},
            "check_inventory": {
                "id": "check_inventory",
                "type": "tool",
                "config": {
                    "tool_code": "seat_inventory_api",
                    "retry_policy": "network_timeout",
                    "idempotent": True,
                },
            },
            "end": {
                "id": "end",
                "type": "end",
                "config": {
                    "output_format": {
                        "seat_available": "execution.seat_available",
                        "seat_count": "execution.seat_count",
                    }
                },
            },
        },
        "transitions": {
            "start": "check_inventory",
            "check_inventory": "end",
            "end": None,
        },
    }


def _slot_extraction_config() -> Dict[str, Any]:
    return {
        "prompt": "slot_extraction",
        "structured_output": {
            "enabled": True,
            "schema": {
                "type": "object",
                "properties": {
                    "departure_city": {"type": "string", "min_length": 2, "max_length": 20},
                    "arrival_city": {"type": "string", "min_length": 2, "max_length": 20},
                    "departure_date": {"type": "string", "pattern": r"\d{4}-\d{2}-\d{2}"},
                    "passengers": {"type": "integer", "minimum": 1, "maximum": 9},
                },
            },
        },
    }


def _hotel_slot_extraction_config() -> Dict[str, Any]:
    return {
        "prompt": "hotel_slot_extraction",
        "structured_output": {
            "enabled": True,
            "schema": {
                "type": "object",
                "properties": {
                    "arrival_city": {"type": "string", "min_length": 2, "max_length": 20},
                    "departure_date": {"type": "string", "pattern": r"\d{4}-\d{2}-\d{2}"},
                    "nights": {"type": "integer", "minimum": 1, "maximum": 30},
                },
            },
        },
    }


def _flight_fields() -> list[dict[str, Any]]:
    return [
        {"name": "departure_city", "type": "text", "required": True, "label": "Departure city"},
        {"name": "arrival_city", "type": "text", "required": True, "label": "Arrival city"},
        {"name": "departure_date", "type": "date", "required": True, "label": "Departure date"},
        {"name": "passengers", "type": "number", "required": False, "label": "Passengers"},
    ]
