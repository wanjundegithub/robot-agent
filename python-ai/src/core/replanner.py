class RePlanner:
    def should_replan(self, result, node_def) -> bool:
        return bool(result.get("replan"))
