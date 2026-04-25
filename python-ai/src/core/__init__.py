from importlib import import_module


__all__ = [
    "ExecutionContext",
    "ContextAssembler",
    "PlanningContext",
    "BranchEvaluator",
    "NodeReadinessChecker",
    "PathResolver",
    "ExecutionPlan",
    "Planner",
    "RePlanner",
    "ExecutionStateMachine",
    "ExecutionStatus",
    "TransitionEvent",
    "WorkflowScheduler",
    "ExecutionRegistry",
]


_EXPORTS = {
    "ExecutionContext": ("context", "ExecutionContext"),
    "ContextAssembler": ("context_assembler", "ContextAssembler"),
    "PlanningContext": ("context_assembler", "PlanningContext"),
    "BranchEvaluator": ("branch_evaluator", "BranchEvaluator"),
    "NodeReadinessChecker": ("node_readiness", "NodeReadinessChecker"),
    "PathResolver": ("path_resolver", "PathResolver"),
    "ExecutionPlan": ("planner", "ExecutionPlan"),
    "Planner": ("planner", "Planner"),
    "RePlanner": ("replanner", "RePlanner"),
    "ExecutionStateMachine": ("state_machine", "ExecutionStateMachine"),
    "ExecutionStatus": ("state_machine", "ExecutionStatus"),
    "TransitionEvent": ("state_machine", "TransitionEvent"),
    "WorkflowScheduler": ("scheduler", "WorkflowScheduler"),
    "ExecutionRegistry": ("registry", "ExecutionRegistry"),
}


def __getattr__(name):
    if name not in _EXPORTS:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")

    module_name, attribute_name = _EXPORTS[name]
    module = import_module(f".{module_name}", __name__)
    value = getattr(module, attribute_name)
    globals()[name] = value
    return value
