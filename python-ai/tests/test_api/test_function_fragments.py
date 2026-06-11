import httpx
import pytest

from src.api.main import app


@pytest.mark.asyncio
async def test_function_fragment_validate_endpoint_returns_validation_result():
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.post(
            "/api/function-fragments/validate",
            json={
                "language": "python",
                "function_name": "处理订单变量",
                "code": "ctx['local']['result'] = 'ok'",
                "timeout_ms": 3000,
            },
        )

    assert response.status_code == 200
    assert response.json()["valid"] is True


@pytest.mark.asyncio
async def test_function_fragment_test_run_endpoint_executes_and_returns_variables():
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.post(
            "/api/function-fragments/test-run",
            json={
                "language": "python",
                "function_name": "处理订单变量",
                "code": "print('开始处理')\nctx['local']['result'] = 'ok'",
                "timeout_ms": 3000,
                "variables": {
                    "global": {"user_name": "张三"},
                    "local": {"order_id": "A001"},
                },
            },
        )

    payload = response.json()
    assert response.status_code == 200
    assert payload["success"] is True
    assert payload["variables"]["global"] == {"user_name": "张三"}
    assert payload["variables"]["local"] == {"order_id": "A001", "result": "ok"}
    assert payload["stdout"] == "开始处理\n"
