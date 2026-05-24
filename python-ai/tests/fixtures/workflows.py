from __future__ import annotations

from copy import deepcopy
from typing import Any, Dict


def simple_workflow(workflow_code: str = "test_workflow", workflow_version: str = "1.0.0") -> Dict[str, Any]:
    return {
        "workflow_code": workflow_code,
        "workflow_version": workflow_version,
        "entry": "start",
        "nodes": {
            "start": {"id": "start", "type": "start", "config": {"initial_variables": {}}},
            "end": {"id": "end", "type": "end", "config": {}},
        },
        "transitions": {"start": "end", "end": None},
    }


def planner_workflow() -> Dict[str, Any]:
    return {
        "workflow_code": "flight_booking",
        "workflow_version": "1.0.0",
        "entry": "start",
        "nodes": {
            "start": {"id": "start", "type": "start", "config": {"initial_variables": {}}},
            "collect_info": {
                "id": "collect_info",
                "type": "form",
                "config": {
                    "fields": [
                        {"name": "departure_city", "type": "text", "required": True},
                        {"name": "arrival_city", "type": "text", "required": True},
                        {"name": "departure_date", "type": "date", "required": True},
                    ],
                },
            },
            "end": {"id": "end", "type": "end", "config": {}},
        },
        "transitions": {"start": "collect_info", "collect_info": "end", "end": None},
    }


def seat_check_workflow() -> Dict[str, Any]:
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
                    "url": "https://tools.example.com/seat-inventory",
                    "method": "POST",
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
        "transitions": {"start": "check_inventory", "check_inventory": "end", "end": None},
    }


def workflow_fixture(factory):
    return deepcopy(factory())
