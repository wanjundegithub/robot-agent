import re
from typing import Any, Dict, Iterable, Mapping


class InvalidOutputError(Exception):
    def __init__(self, message: str, details: Dict[str, Any] | None = None):
        super().__init__(message)
        self.details = details or {}


class PromptSanitizer:
    INJECTION_PATTERNS = (
        r"\b(ignore|forget|disregard)\b.*\b(above|previous|instruction)\b",
        r"\b(as an?\s+)?(ai|assistant|model|system)\b",
        r"\b(output|print|display)\b.*\b(system|prompt|instruction)\b",
        r"<\|.*?\|>",
        r"```.*?```",
    )

    @classmethod
    def sanitize(cls, text: str) -> str:
        sanitized = text or ""
        for pattern in cls.INJECTION_PATTERNS:
            sanitized = re.sub(pattern, "[REDACTED]", sanitized, flags=re.IGNORECASE | re.DOTALL)
        return sanitized


SENSITIVE_PATTERNS = (
    (r"password", "password"),
    (r"secret", "secret"),
    (r"(^|[_-])token($|[_-])", "token"),
    (r"api[_-]?key", "api_key"),
    (r"phone", "phone"),
    (r"mobile", "mobile"),
    (r"id[_-]?card", "id_card"),
)


def mask_sensitive_fields(data: Any) -> Any:
    if isinstance(data, Mapping):
        return {key: _mask_value(str(key), value) for key, value in data.items()}
    if isinstance(data, list):
        return [mask_sensitive_fields(item) for item in data]
    return data


def _mask_value(field_name: str, value: Any) -> Any:
    field_lower = field_name.lower()
    for pattern, _ in SENSITIVE_PATTERNS:
        if re.search(pattern, field_lower):
            return _mask_scalar(value)
    return mask_sensitive_fields(value)


def _mask_scalar(value: Any) -> Any:
    if isinstance(value, str) and len(value) > 4:
        return value[:2] + "****" + value[-2:]
    return "[MASKED]"


class StructuredOutputValidator:
    @classmethod
    def validate(cls, payload: Any, schema: Dict[str, Any]) -> Dict[str, Any]:
        cls._validate_value(payload, schema, "$")
        return payload

    @classmethod
    def _validate_value(cls, value: Any, schema: Dict[str, Any], path: str) -> None:
        schema_type = schema.get("type")
        if schema_type == "object":
            cls._validate_object(value, schema, path)
            return
        if schema_type == "array":
            cls._validate_array(value, schema, path)
            return
        if schema_type == "string":
            cls._validate_string(value, schema, path)
            return
        if schema_type == "integer":
            cls._validate_integer(value, schema, path)
            return
        if schema_type == "number":
            cls._validate_number(value, schema, path)
            return
        if schema_type == "boolean":
            if not isinstance(value, bool):
                raise InvalidOutputError("Boolean field validation failed", {"path": path, "value": value})

    @classmethod
    def _validate_object(cls, value: Any, schema: Dict[str, Any], path: str) -> None:
        if not isinstance(value, dict):
            raise InvalidOutputError("Object validation failed", {"path": path, "value": value})

        required = schema.get("required", [])
        for field in required:
            if field not in value:
                raise InvalidOutputError("Missing required field", {"path": f"{path}.{field}"})

        properties = schema.get("properties", {})
        for field, field_schema in properties.items():
            if field in value:
                cls._validate_value(value[field], field_schema, f"{path}.{field}")

    @classmethod
    def _validate_array(cls, value: Any, schema: Dict[str, Any], path: str) -> None:
        if not isinstance(value, list):
            raise InvalidOutputError("Array validation failed", {"path": path, "value": value})

        item_schema = schema.get("items")
        if not item_schema:
            return
        for index, item in enumerate(value):
            cls._validate_value(item, item_schema, f"{path}[{index}]")

    @classmethod
    def _validate_string(cls, value: Any, schema: Dict[str, Any], path: str) -> None:
        if not isinstance(value, str):
            raise InvalidOutputError("String validation failed", {"path": path, "value": value})

        min_length = schema.get("min_length")
        max_length = schema.get("max_length")
        pattern = schema.get("pattern")
        enum_values: Iterable[str] | None = schema.get("enum")

        if min_length is not None and len(value) < min_length:
            raise InvalidOutputError("String shorter than expected", {"path": path, "value": value})
        if max_length is not None and len(value) > max_length:
            raise InvalidOutputError("String longer than expected", {"path": path, "value": value})
        if pattern and not re.fullmatch(pattern, value):
            raise InvalidOutputError("String pattern validation failed", {"path": path, "value": value})
        if enum_values is not None and value not in enum_values:
            raise InvalidOutputError("String enum validation failed", {"path": path, "value": value})

    @classmethod
    def _validate_integer(cls, value: Any, schema: Dict[str, Any], path: str) -> None:
        if isinstance(value, bool) or not isinstance(value, int):
            raise InvalidOutputError("Integer validation failed", {"path": path, "value": value})
        cls._validate_range(value, schema, path)

    @classmethod
    def _validate_number(cls, value: Any, schema: Dict[str, Any], path: str) -> None:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise InvalidOutputError("Number validation failed", {"path": path, "value": value})
        cls._validate_range(value, schema, path)

    @classmethod
    def _validate_range(cls, value: int | float, schema: Dict[str, Any], path: str) -> None:
        minimum = schema.get("minimum")
        maximum = schema.get("maximum")
        if minimum is not None and value < minimum:
            raise InvalidOutputError("Number below minimum", {"path": path, "value": value})
        if maximum is not None and value > maximum:
            raise InvalidOutputError("Number above maximum", {"path": path, "value": value})
