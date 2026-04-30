"""Service configuration loaded from environment via pydantic-settings."""

from __future__ import annotations

from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


def _default_data_dir() -> Path:
    return Path.home() / ".meridian-orchestrator"


class Settings(BaseSettings):
    """Service-level configuration.

    Service-level fields use the ``MERIDIAN_ORCH_`` env prefix. External-credential
    fields added in later milestones (Telegram tokens at M6, etc.) read with their
    natural names via per-field aliases.
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_prefix="MERIDIAN_ORCH_",
        extra="ignore",
    )

    data_dir: Path = Field(default_factory=_default_data_dir)
    log_level: str = Field(default="INFO")

    mcp_host: str = Field(default="127.0.0.1")
    mcp_port: int = Field(default=8765, ge=1, le=65535)
    mcp_allow_non_loopback: bool = Field(default=False)

    @property
    def db_path(self) -> Path:
        return self.data_dir / "data" / "orchestrator.db"

    @property
    def logs_dir(self) -> Path:
        return self.data_dir / "logs"

    @property
    def backups_dir(self) -> Path:
        return self.data_dir / "backups"

    @property
    def knowledge_dir(self) -> Path:
        return self.data_dir / "knowledge"

    @property
    def config_dir(self) -> Path:
        return self.data_dir / "config"

    @property
    def agents_dir(self) -> Path:
        return self.data_dir / "config" / "agents"
