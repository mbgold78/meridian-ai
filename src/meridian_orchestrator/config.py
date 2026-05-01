"""Service configuration loaded from environment via pydantic-settings."""

from __future__ import annotations

from pathlib import Path

from pydantic import Field, model_validator
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

    # M4c-engineer-supervisor: ships dormant. Flag flip is M5's behavioral
    # milestone (alongside PMSupervisor's analogous flag). See
    # `project_m5_engineer_supervisor_flag_flip` memory for the M5
    # verification checklist (subscription_5h attribution, compaction-at-80%,
    # heartbeat calibration).
    enable_engineer_supervisor: bool = Field(default=False)

    # M5-pm-supervisor-base: ships dormant. Flag flip is M5-flag-flips
    # alongside enable_engineer_supervisor. PMSupervisor doesn't need a
    # substrate working tree (no canonical-clone analog) — the
    # substrate_working_directory validator above is engineer-only.
    enable_pm_supervisor: bool = Field(default=False)

    # M5-wrapper-closure: the substrate working tree the daemon discovers
    # ``origin`` from at canonical-provisioning time. Outside ``data_dir``
    # because it points at Mike's actual local clone, not daemon state.
    # No default — daemon fails fast at provisioning if unset and the
    # supervisor flag is on (validator below).
    substrate_working_directory: Path | None = Field(default=None)

    @model_validator(mode="after")
    def _require_substrate_working_directory_when_supervisor_enabled(
        self,
    ) -> Settings:
        """Conditional fail-fast: substrate_working_directory is only
        required when the engineer-supervisor flag is on (canonical
        provisioning needs it). M5-wrapper-closure tests run with the
        supervisor off and don't need the env var."""
        if self.enable_engineer_supervisor and self.substrate_working_directory is None:
            raise ValueError(
                "MERIDIAN_ORCH_SUBSTRATE_WORKING_DIRECTORY must be set when "
                "MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=true; canonical-clone "
                "provisioning reads `origin` from this path"
            )
        return self

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

    @property
    def engineer_supervisor_dir(self) -> Path:
        """The engineer-supervisor's subprocess cwd. Per spec
        (`11-engineer-bridge.md` § Long-running supervisors): no git, no
        worktree — sub-agents handle code in dispatch worktrees off the
        canonical clone. This is a separate dedicated directory."""
        return self.data_dir / "engineer"

    @property
    def pm_continuous_dir(self) -> Path:
        """The PM continuous-mode subprocess cwd. Per spec
        (`11-engineer-bridge.md` § Long-running supervisors): no git, no
        worktree — PM's writes land via MCP tools (knowledge writes,
        task notes), not filesystem. Symmetric with
        engineer_supervisor_dir."""
        return self.data_dir / "pm-continuous"

    @property
    def substrate_canonical_dir(self) -> Path:
        """Daemon-owned canonical clone path. The dispatcher's
        BridgeDispatcher reads/writes here; per-dispatch worktrees branch
        from this clone. See `bridge/canonical.py` § CanonicalClone."""
        return self.data_dir / "substrate-canonical"

    @property
    def dispatch_worktrees_root(self) -> Path:
        """Root directory for per-dispatch worktrees. Each dispatch gets a
        subdirectory under this root, cleaned up on terminal state unless
        ``keep_worktree_on_failure`` is set."""
        return self.data_dir / "dispatch-worktrees"
