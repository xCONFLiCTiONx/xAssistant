from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="XASSISTANT_",
        env_file=".env",
        extra="ignore",
    )

    app_name: str = "xAssistant"
    environment: str = "development"
    host: str = "127.0.0.1"
    port: int = 5081

    ollama_url: str = "http://192.168.88.50:11434"
    ollama_model: str = "qwen3.5:4b-q4_K_M"
    ollama_timeout_seconds: float = Field(default=60.0, gt=0)
    ollama_keep_alive: str = "5m"

    timezone: str = "America/Chicago"
    database_path: str = "./data/xassistant.sqlite3"


@lru_cache
def get_settings() -> Settings:
    return Settings()
