import importlib
import sys


def test_api_main_imports_without_missing_core_modules():
    for module_name in [
        "src.api.main",
        "src.core",
        "src.core.scheduler",
        "src.nodes.condition",
    ]:
        sys.modules.pop(module_name, None)

    module = importlib.import_module("src.api.main")

    assert module.app.title == "Workflow Engine API"
