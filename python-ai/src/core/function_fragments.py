import ast
import io
import json
import multiprocessing
import textwrap
import time
import traceback
from contextlib import redirect_stdout
from typing import Any, Dict, Optional


FUNCTION_NAME = "__robot_function__"
WRAPPER_LINE_OFFSET = 1
MAX_STDOUT_BYTES = 64 * 1024
MAX_VARIABLES_JSON_BYTES = 512 * 1024
DEFAULT_TIMEOUT_MS = 3000
MIN_TIMEOUT_MS = 1
MAX_TIMEOUT_MS = 30000

FORBIDDEN_CALLS = {
    "open",
    "eval",
    "exec",
    "__import__",
    "compile",
    "globals",
    "locals",
    "vars",
    "dir",
    "getattr",
    "setattr",
    "delattr",
    "input",
}

SAFE_BUILTINS = {
    "abs": abs,
    "all": all,
    "any": any,
    "bool": bool,
    "dict": dict,
    "enumerate": enumerate,
    "float": float,
    "int": int,
    "isinstance": isinstance,
    "len": len,
    "list": list,
    "max": max,
    "min": min,
    "print": print,
    "range": range,
    "round": round,
    "set": set,
    "sorted": sorted,
    "str": str,
    "sum": sum,
    "tuple": tuple,
}


def validate_function_fragment(code: str) -> Dict[str, Any]:
    try:
        tree = _parse_wrapped(code)
        _validate_ast(tree)
    except FunctionFragmentError as exc:
        return _validation_response(False, exc.message, exc.line, exc.column)
    except SyntaxError as exc:
        return _validation_response(False, exc.msg, _to_user_line(exc.lineno), _to_user_column(exc.offset))
    return _validation_response(True, None, None, None)


def run_function_fragment(
    code: str,
    variables: Optional[Dict[str, Any]] = None,
    timeout_ms: int = DEFAULT_TIMEOUT_MS,
) -> Dict[str, Any]:
    started_at = time.perf_counter()
    validation = validate_function_fragment(code)
    if not validation["valid"]:
        return _run_response(
            success=False,
            variables=_normalize_variables(variables),
            stdout="",
            error_message=validation["error_message"],
            line=validation["line"],
            column=validation["column"],
            started_at=started_at,
        )

    normalized_timeout = _normalize_timeout(timeout_ms)
    parent_conn, child_conn = multiprocessing.Pipe(duplex=False)
    process = multiprocessing.Process(
        target=_child_run,
        args=(child_conn, code, _normalize_variables(variables)),
        daemon=True,
    )
    process.start()
    child_conn.close()
    process.join(normalized_timeout / 1000)

    if process.is_alive():
        process.terminate()
        process.join(1)
        return _run_response(
            success=False,
            variables=_normalize_variables(variables),
            stdout="",
            error_message="运行超时",
            line=None,
            column=None,
            started_at=started_at,
        )

    if parent_conn.poll():
        payload = parent_conn.recv()
        payload["duration_ms"] = _duration_ms(started_at)
        return payload

    return _run_response(
        success=False,
        variables=_normalize_variables(variables),
        stdout="",
        error_message="执行进程未返回结果",
        line=None,
        column=None,
        started_at=started_at,
    )


def wrap_function_fragment(code: str) -> str:
    body = code if code and code.strip() else "pass"
    return f"def {FUNCTION_NAME}(ctx):\n{textwrap.indent(body, '    ')}\n"


def _child_run(conn, code: str, variables: Dict[str, Any]) -> None:
    started_at = time.perf_counter()
    stdout_buffer = _LimitedStringIO(MAX_STDOUT_BYTES)
    try:
        namespace: Dict[str, Any] = {"__builtins__": SAFE_BUILTINS}
        compiled = compile(wrap_function_fragment(code), "<function_fragment>", "exec")
        exec(compiled, namespace, namespace)
        ctx = {
            "global": dict(variables.get("global", {})),
            "local": dict(variables.get("local", {})),
        }
        with redirect_stdout(stdout_buffer):
            namespace[FUNCTION_NAME](ctx)
        _validate_context(ctx)
        _validate_variables_size(ctx)
        conn.send(_run_response(
            success=True,
            variables=ctx,
            stdout=stdout_buffer.getvalue(),
            error_message=None,
            line=None,
            column=None,
            started_at=started_at,
        ))
    except FunctionFragmentError as exc:
        conn.send(_run_response(
            success=False,
            variables=variables,
            stdout=stdout_buffer.getvalue(),
            error_message=exc.message,
            line=exc.line,
            column=exc.column,
            started_at=started_at,
        ))
    except Exception as exc:
        tb = traceback.extract_tb(exc.__traceback__)
        fragment_frame = next(
            (frame for frame in reversed(tb) if frame.filename == "<function_fragment>"),
            None,
        )
        conn.send(_run_response(
            success=False,
            variables=variables,
            stdout=stdout_buffer.getvalue(),
            error_message=str(exc),
            line=_to_user_line(fragment_frame.lineno) if fragment_frame else None,
            column=None,
            started_at=started_at,
        ))
    finally:
        conn.close()


def _parse_wrapped(code: str) -> ast.AST:
    return ast.parse(wrap_function_fragment(code), filename="<function_fragment>")


def _validate_ast(tree: ast.AST) -> None:
    for node in ast.walk(tree):
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            raise FunctionFragmentError("禁用语法: import", _to_user_line(node.lineno), _to_user_column(node.col_offset + 1))
        if isinstance(node, ast.Call):
            call_name = _call_name(node.func)
            if call_name in FORBIDDEN_CALLS:
                raise FunctionFragmentError(f"危险调用: {call_name}", _to_user_line(node.lineno), _to_user_column(node.col_offset + 1))
        if isinstance(node, ast.Attribute) and node.attr.startswith("__") and node.attr.endswith("__"):
            raise FunctionFragmentError(f"危险属性访问: {node.attr}", _to_user_line(node.lineno), _to_user_column(node.col_offset + 1))


def _call_name(node: ast.AST) -> Optional[str]:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return node.attr
    return None


def _validate_context(ctx: Dict[str, Any]) -> None:
    if not isinstance(ctx.get("global"), dict):
        raise FunctionFragmentError("ctx['global'] 必须是对象", None, None)
    if not isinstance(ctx.get("local"), dict):
        raise FunctionFragmentError("ctx['local'] 必须是对象", None, None)


def _validate_variables_size(ctx: Dict[str, Any]) -> None:
    encoded = json.dumps(ctx, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(encoded) > MAX_VARIABLES_JSON_BYTES:
        raise FunctionFragmentError("变量 JSON 体积过大", None, None)


def _normalize_variables(variables: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    source = variables if isinstance(variables, dict) else {}
    global_vars = source.get("global", {})
    local_vars = source.get("local", {})
    return {
        "global": _normalize_scope(global_vars),
        "local": _normalize_scope(local_vars),
    }


def _normalize_scope(scope: Any) -> Dict[str, Any]:
    if not isinstance(scope, dict):
        return {}
    normalized: Dict[str, Any] = {}
    for key, value in scope.items():
        if str(key).startswith("_") or callable(value):
            continue
        try:
            json.dumps(value, ensure_ascii=False)
        except (TypeError, ValueError):
            continue
        normalized[key] = value
    return normalized


def _normalize_timeout(timeout_ms: Any) -> int:
    try:
        value = int(timeout_ms)
    except (TypeError, ValueError):
        value = DEFAULT_TIMEOUT_MS
    return max(MIN_TIMEOUT_MS, min(MAX_TIMEOUT_MS, value))


def _validation_response(valid: bool, error_message: Optional[str], line: Optional[int], column: Optional[int]) -> Dict[str, Any]:
    return {
        "valid": valid,
        "error_message": error_message,
        "line": line,
        "column": column,
    }


def _run_response(
    success: bool,
    variables: Dict[str, Any],
    stdout: str,
    error_message: Optional[str],
    line: Optional[int],
    column: Optional[int],
    started_at: float,
) -> Dict[str, Any]:
    return {
        "success": success,
        "variables": variables,
        "stdout": stdout,
        "error_message": error_message,
        "line": line,
        "column": column,
        "duration_ms": _duration_ms(started_at),
    }


def _duration_ms(started_at: float) -> int:
    return max(0, int((time.perf_counter() - started_at) * 1000))


def _to_user_line(line: Optional[int]) -> Optional[int]:
    if line is None:
        return None
    return max(1, line - WRAPPER_LINE_OFFSET)


def _to_user_column(column: Optional[int]) -> Optional[int]:
    if column is None:
        return None
    return max(1, column - 4)


class FunctionFragmentError(Exception):
    def __init__(self, message: str, line: Optional[int], column: Optional[int]):
        super().__init__(message)
        self.message = message
        self.line = line
        self.column = column


class _LimitedStringIO(io.StringIO):
    def __init__(self, max_bytes: int):
        super().__init__()
        self.max_bytes = max_bytes
        self.current_bytes = 0

    def write(self, value: str) -> int:
        byte_count = len(value.encode("utf-8"))
        if self.current_bytes + byte_count > self.max_bytes:
            raise FunctionFragmentError("输出日志过大", None, None)
        self.current_bytes += byte_count
        return super().write(value)
