from pydantic_settings import BaseSettings, SettingsConfigDict


class RuntimeSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ROBOT_", extra="ignore")

    redis_url: str = "redis://localhost:6379/0"
    redis_enabled: bool = True

    vector_dsn: str = "postgresql://robot:robot@localhost:5432/robot_vector"
    vector_enabled: bool = True
    vector_table: str = "knowledge_chunks"
    vector_dimension: int = 4096

    knowledge_embedding_default_model_code: str = "model-431c4581ab84"
    knowledge_embedding_batch_size: int = 32
    knowledge_retrieval_vector_weight: float = 0.7
    knowledge_retrieval_keyword_weight: float = 0.3
    knowledge_retrieval_metadata_boost: float = 0.05
    knowledge_retrieval_vector_top_k: int = 20
    knowledge_retrieval_keyword_top_k: int = 20
    knowledge_retrieval_query_ngram_min: int = 2
    knowledge_retrieval_query_ngram_max: int = 4


settings = RuntimeSettings()
