from pydantic_settings import BaseSettings, SettingsConfigDict


class RuntimeSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ROBOT_", extra="ignore")

    redis_url: str = "redis://localhost:6379/0"
    redis_enabled: bool = True

    vector_dsn: str = "postgresql://robot:robot@localhost:5432/robot_vector"
    vector_enabled: bool = True
    vector_table: str = "knowledge_chunks"

    otel_enabled: bool = True
    otel_exporter_endpoint: str = "http://localhost:4317"
    otel_service_name: str = "workflow-engine"


settings = RuntimeSettings()
