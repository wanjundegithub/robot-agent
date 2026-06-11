from src.core.function_fragments import run_function_fragment, validate_function_fragment


def test_validate_function_fragment_accepts_body_snippet_with_return():
    result = validate_function_fragment(
        "print('开始处理')\n"
        "if not ctx['local'].get('order_id'):\n"
        "    return\n"
        "ctx['local']['checked'] = True"
    )

    assert result["valid"] is True
    assert result["error_message"] is None
    assert result["line"] is None
    assert result["column"] is None


def test_validate_function_fragment_rejects_import_and_reports_user_line():
    result = validate_function_fragment("print('before')\nimport os")

    assert result["valid"] is False
    assert result["error_message"] == "禁用语法: import"
    assert result["line"] == 2
    assert result["column"] == 1


def test_validate_function_fragment_rejects_dangerous_calls():
    result = validate_function_fragment("open('/tmp/a.txt', 'w')")

    assert result["valid"] is False
    assert result["error_message"] == "危险调用: open"
    assert result["line"] == 1
    assert result["column"] == 1


def test_validate_function_fragment_rejects_dunder_attribute_access():
    result = validate_function_fragment("value = ctx.__class__")

    assert result["valid"] is False
    assert result["error_message"] == "危险属性访问: __class__"
    assert result["line"] == 1
    assert result["column"] == 9


def test_run_function_fragment_updates_variables_and_captures_stdout():
    result = run_function_fragment(
        "print('开始处理')\n"
        "ctx['global']['user_name'] = ctx['global']['user_name'].strip()\n"
        "ctx['local']['result'] = ctx['global']['user_name'] + ' 已处理'\n"
        "return ctx",
        {
            "global": {"user_name": " 张三 "},
            "local": {"order_id": "A001"},
        },
        timeout_ms=3000,
    )

    assert result["success"] is True
    assert result["variables"]["global"] == {"user_name": "张三"}
    assert result["variables"]["local"] == {"order_id": "A001", "result": "张三 已处理"}
    assert result["stdout"] == "开始处理\n"
    assert result["error_message"] is None
    assert result["line"] is None
    assert result["column"] is None
    assert result["duration_ms"] >= 0


def test_run_function_fragment_rejects_non_object_context_scope():
    result = run_function_fragment(
        "ctx['local'] = []",
        {
            "global": {},
            "local": {},
        },
        timeout_ms=3000,
    )

    assert result["success"] is False
    assert result["error_message"] == "ctx['local'] 必须是对象"


def test_run_function_fragment_times_out():
    result = run_function_fragment(
        "while True:\n"
        "    pass",
        {
            "global": {},
            "local": {},
        },
        timeout_ms=200,
    )

    assert result["success"] is False
    assert result["error_message"] == "运行超时"
